package com.samin.notouchgesture.capture

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.MediaActionSound
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.LifecycleService
import com.samin.notouchgesture.R
import com.samin.notouchgesture.gesture.GestureRecognizerController
import com.samin.notouchgesture.gesture.GestureStateMachine
import com.samin.notouchgesture.nearby.NearbyRuntime
import com.samin.notouchgesture.nearby.NearbyTransferManager
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PalmLink's long-running runtime. It owns, for the whole time it is alive:
 *
 *  - the single Nearby session ([NearbyTransferManager]) - advertising / discovery / connection
 *    / transfers survive the Activity being stopped, destroyed or recreated;
 *  - the user-approved MediaProjection session and its screen capture pipeline;
 *  - the background CameraX + MediaPipe gesture engine.
 *
 * The Activity is only a UI/controller: it sends commands (intents) and observes state.
 *
 * Foreground-service lifecycle contract (what keeps Android from killing the process):
 *
 *  1. Every command delivered with startForegroundService() is answered FIRST by startForeground().
 *     Nothing may stop the service or throw before that call, otherwise the system terminates the
 *     app with "Context.startForegroundService() did not then call Service.startForeground()".
 *  2. Types are only ever added, one prerequisite at a time:
 *       connectedDevice  (Nearby; prerequisite: CHANGE_WIFI_STATE in the manifest)
 *       mediaProjection  (right after the user's consent; getMediaProjection() only AFTER this)
 *       camera           (API 30+, only after the CAMERA permission is confirmed and while the
 *                         app is still visible - Android's while-in-use rule)
 *  3. Every failure after step 1 goes through [degradeOrStop], which never throws: it releases the
 *     camera/projection and keeps the service only while Nearby still has a reason to run.
 */
class ScreenCaptureService : LifecycleService() {
    private lateinit var captureThread: HandlerThread
    private lateinit var captureHandler: Handler
    private val mainHandler = Handler(Looper.getMainLooper())

    private val captureInFlight = AtomicBoolean(false)
    @Volatile private var waitingForFrame = false
    private val frameLock = Any()
    private var latestFrame: Image? = null // guarded by frameLock

    @Volatile private var projection: MediaProjection? = null
    @Volatile private var imageReader: ImageReader? = null
    @Volatile private var virtualDisplay: VirtualDisplay? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var gestureController: GestureRecognizerController? = null
    private var foregroundStarted = false
    private var fgsTypes = 0
    private var lastForegroundError: String? = null
    @Volatile private var nearbyPendingOffer = false
    @Volatile private var gestureReady = false
    private lateinit var nearby: NearbyTransferManager
    private lateinit var captureStore: CaptureStore
    private lateinit var visualFeedback: VisualFeedbackController
    private lateinit var feedbackSettings: FeedbackSettings
    private lateinit var mediaExecutor: ExecutorService
    private var shutterSound: MediaActionSound? = null
    private var lastNearbySending = false
    private var lastNearbyReceiving = false
    private var lastNearbyPreviewTransferId: String? = null
    private var lastNearbyReceivedFile: File? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotificationText: String? = null
    private var lastSavedReceived: File? = null

    private val wakeTick = Runnable { refreshWakeLock() }

    private val frameTimeout = Runnable {
        if (waitingForFrame) {
            waitingForFrame = false
            captureInFlight.set(false)
            broadcastState("Screenshot failed: no screen frame arrived. Try again.")
        }
    }

    private val nearbyStateListener: (NearbyTransferManager.State) -> Unit = { state ->
        val becameSending = state.sending && !lastNearbySending
        val becameReceiving = state.receiving && !lastNearbyReceiving
        lastNearbySending = state.sending
        lastNearbyReceiving = state.receiving
        nearbyPendingOffer = state.pendingOffer != null

        if (becameSending) {
            val file = captureStore.latestFile()
            mainHandler.post { visualFeedback.showTransfer(file, VisualFeedbackController.Direction.SENDING) }
        } else if (becameReceiving) {
            mainHandler.post { visualFeedback.showTransfer(null, VisualFeedbackController.Direction.RECEIVING) }
        }

        if (state.previewTransferId != null && state.previewTransferId != lastNearbyPreviewTransferId && !state.previewBase64.isNullOrBlank()) {
            lastNearbyPreviewTransferId = state.previewTransferId
            val encoded = state.previewBase64
            mediaExecutor.execute {
                val bytes = runCatching { android.util.Base64.decode(encoded, android.util.Base64.DEFAULT) }.getOrNull()
                val preview = bytes?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
                if (preview != null && alive) mainHandler.post { visualFeedback.updateTransferPreview(preview) }
            }
        }

        val received = state.lastReceivedFile
        if (received != null && received != lastSavedReceived && received != lastNearbyReceivedFile) {
            lastSavedReceived = received
            lastNearbyReceivedFile = received
            captureStore.saveLatest(received)
            mediaExecutor.execute {
                val gallerySaved = MediaStoreSaver.saveToPictures(this, received)
                if (alive) mainHandler.post { visualFeedback.showReceived(received, gallerySaved) }
            }
        }

        mainHandler.post {
            if (state.message.startsWith("Screenshot delivered")) {
                val file = captureStore.latestFile()
                if (file != null) visualFeedback.showSent(file)
            }
            refreshNotification()
            refreshWakeLock()
        }
    }

    companion object {
        private const val TAG = "PalmLink"

        /** The service object exists (it may only be running Nearby). */
        @Volatile
        var alive: Boolean = false
            private set

        /** The background gesture engine (camera + MediaPipe) is up. */
        @Volatile
        var running: Boolean = false
            private set

        /** A MediaProjection session is active, so a screenshot can be taken right now. */
        @Volatile
        var captureReady: Boolean = false
            private set

        private val startupInProgress = AtomicBoolean(false)

        fun tryBeginStarting(): Boolean = startupInProgress.compareAndSet(false, true)

        fun finishStarting() {
            startupInProgress.set(false)
        }

        const val ACTION_CAPTURE_COMPLETE = "com.samin.notouchgesture.CAPTURE_COMPLETE"
        const val ACTION_SERVICE_STATE = "com.samin.notouchgesture.SERVICE_STATE"
        const val ACTION_GESTURE_STATE = "com.samin.notouchgesture.GESTURE_STATE"
        const val ACTION_NEARBY_STATE = "com.samin.notouchgesture.NEARBY_STATE"
        const val ACTION_CAPTURE_NOW = "com.samin.notouchgesture.CAPTURE_NOW"
        const val ACTION_STOP = "com.samin.notouchgesture.STOP"
        const val ACTION_NEARBY_ADVERTISE = "com.samin.notouchgesture.NEARBY_ADVERTISE"
        const val ACTION_NEARBY_DISCOVER = "com.samin.notouchgesture.NEARBY_DISCOVER"
        const val ACTION_NEARBY_STOP = "com.samin.notouchgesture.NEARBY_STOP"
        const val EXTRA_PATH = "path"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_LABEL = "label"
        const val EXTRA_CONFIDENCE = "confidence"
        const val EXTRA_HAND_QUALITY = "hand_quality"
        const val EXTRA_RUNNING = "running"
        const val EXTRA_CAPTURE_READY = "capture_ready"
        const val EXTRA_NEARBY_MESSAGE = "nearby_message"
        const val EXTRA_NEARBY_CONNECTED = "nearby_connected"
        const val EXTRA_NEARBY_PENDING_OFFER = "nearby_pending_offer"
        private const val CHANNEL_ID = "palmlink_background"
        private const val NOTIFICATION_ID = 43
        private const val MAX_IMAGES = 4
        private const val FRAME_WAIT_MS = 3_000L
        private const val WAKE_LOCK_MS = 10 * 60 * 1000L
    }

    override fun onCreate() {
        super.onCreate()
        captureThread = HandlerThread("PalmLink-Capture").also { it.start() }
        captureHandler = Handler(captureThread.looper)
        captureStore = CaptureStore(this)
        visualFeedback = VisualFeedbackController(this)
        feedbackSettings = FeedbackSettings(this)
        mediaExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "PalmLink-Media") }
        shutterSound = runCatching { MediaActionSound().also { it.load(MediaActionSound.SHUTTER_CLICK) } }.getOrNull()
        // The channel must exist before the first startForeground() call.
        createNotificationChannel()
        nearby = NearbyRuntime.get(this)
        alive = true
        running = false
        captureReady = false
        nearby.addStateListener(nearbyStateListener)
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        try {
            handleCommand(intent)
        } catch (error: Throwable) {
            if (error is VirtualMachineError) throw error
            Log.e(TAG, "Unhandled error while handling a service command", error)
            degradeOrStop("PalmLink could not start: ${describe(error)}")
        }
        return START_STICKY
    }

    private fun handleCommand(intent: Intent?) {
        when (intent?.action) {
            ACTION_NEARBY_STOP -> {
                nearby.stopAll()
                refreshNotification()
                stopSelf()
                return
            }
            ACTION_STOP -> {
                Log.i(TAG, "Stop requested")
                running = false
                captureReady = false
                finishStarting()
                broadcastState("PalmLink stopped.", running = false)
                stopSelf() // onDestroy() tears down camera, projection and Nearby
                return
            }
            ACTION_CAPTURE_NOW -> {
                // Delivered with startService(): no foreground obligation, and it must never
                // (re)create a MediaProjection without fresh consent.
                if (captureReady && imageReader != null) {
                    requestCapture("manual")
                } else {
                    broadcastState("Screen capture is not ready. Re-enable screen capture.")
                }
                // Delivered with startService(): if this service was not already running as a
                // foreground service there is nothing to keep alive.
                if (!foregroundStarted) stopSelf()
                return
            }
        }

        // Everything below may have been started with startForegroundService().
        // STEP 1 - promote immediately, before anything can fail or return early.
        val withProjection = intent?.hasExtra(EXTRA_RESULT_CODE) == true
        if (!foregroundStarted && !promoteInitial(withProjection)) {
            Log.e(TAG, "Could not enter the foreground: $lastForegroundError")
            broadcastState("PalmLink could not start: ${lastForegroundError ?: "Android refused the foreground service"}", running = false)
            finishStarting()
            stopSelf()
            return
        }

        if (intent == null && nearby.isActive()) {
            if (!addConnectedDeviceType()) {
                degradeOrStop("Android refused the connected-device foreground-service type after service restart.")
                return
            }
            // desiredRole is persisted inside NearbyTransferManager; resumeRole() is idempotent.
            nearby.resumePersistedRole()
        }

        when (intent?.action) {
            ACTION_NEARBY_ADVERTISE, ACTION_NEARBY_DISCOVER -> {
                if (!addConnectedDeviceType()) {
                    broadcastState("Android refused the connected-device foreground-service type. Nearby was not started.", running = running)
                    refreshNotification()
                    return
                }
                if (intent.action == ACTION_NEARBY_ADVERTISE) nearby.startAdvertising()
                else nearby.startDiscovery()
            }
        }

        if (withProjection && intent != null) startSession(intent)

        refreshNotification()
        refreshWakeLock()
        stopIfIdle()
    }

    /** Stops the service when neither Nearby, the camera nor a projection has a reason to run. */
    private fun stopIfIdle() {
        if (!nearby.isActive() && !gestureReady && projection == null && !startupInProgress.get()) {
            Log.i(TAG, "Nothing left to run; stopping")
            stopSelf()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Foreground promotion
    // ---------------------------------------------------------------------------------------

    private fun tryForeground(types: Int): Boolean = try {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), types)
        fgsTypes = types
        foregroundStarted = true
        lastForegroundError = null
        Log.i(TAG, "startForeground ok, types=0x${Integer.toHexString(types)}")
        true
    } catch (error: Exception) {
        lastForegroundError = describe(error)
        Log.e(TAG, "startForeground(types=0x${Integer.toHexString(types)}) failed", error)
        false
    }

    /**
     * First promotion. Candidates are tried from the richest to the safest type combination, so a
     * refused optional type (connectedDevice) never prevents the mandatory one.
     */
    private fun promoteInitial(withProjection: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < 29) return tryForeground(0) // typed FGS starts at API 29
        val device = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        val projectionType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        val candidates = if (withProjection) {
            intArrayOf(projectionType or device, projectionType, device)
        } else {
            intArrayOf(device)
        }
        for (types in candidates) {
            if (tryForeground(types)) return true
        }
        return false
    }

    private fun hasType(type: Int): Boolean = (fgsTypes and type) != 0

    private fun addProjectionType() {
        if (Build.VERSION.SDK_INT < 29) return
        val projectionType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        // Always (re-)assert the type before getMediaProjection(), also on a re-consent where the
        // type bit may still be set from an earlier session. startForeground() is idempotent.
        if (!tryForeground(fgsTypes or projectionType)) {
            throw IllegalStateException("Android refused the screen-capture foreground service: $lastForegroundError")
        }
    }

    private fun addConnectedDeviceType(): Boolean {
        if (Build.VERSION.SDK_INT < 29) return true
        val deviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (hasType(deviceType)) return true
        if (!tryForeground(fgsTypes or deviceType)) {
            Log.e(TAG, "Android refused the connected-device foreground-service type: $lastForegroundError")
            return false
        }
        return true
    }

    /**
     * FOREGROUND_SERVICE_TYPE_CAMERA was introduced in API 30; API 29 has no camera type, so
     * nothing can or needs to be added there. On API 30+ the type must be requested while the
     * app is visible and the CAMERA permission is granted.
     */
    private fun addCameraType() {
        if (Build.VERSION.SDK_INT < 30) return
        val cameraType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (hasType(cameraType)) return
        if (!hasCameraPermission()) throw SecurityException("Camera permission was revoked")
        if (!tryForeground(fgsTypes or cameraType)) {
            throw IllegalStateException("Android refused the camera foreground service: $lastForegroundError")
        }
    }

    // ---------------------------------------------------------------------------------------
    // MediaProjection + gesture session
    // ---------------------------------------------------------------------------------------

    private fun startSession(intent: Intent) {
        // A second consent while a session exists is a no-op.
        if (projection != null) {
            if (gestureReady) markRunning()
            return
        }

        // NOTE: Activity.RESULT_OK is -1, so -1 must never be used as an "absent" sentinel.
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData = intent.getParcelableExtraCompat<Intent>(EXTRA_RESULT_DATA)
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            degradeOrStop("Screen capture permission is required to enable PalmLink.")
            return
        }
        if (!hasCameraPermission()) {
            degradeOrStop("Camera permission is required for background gestures.")
            return
        }

        // STEP 2 - MediaProjection, only after the projection FGS type is active.
        addProjectionType()
        startProjection(resultCode, resultData)
        captureReady = true
        Log.i(TAG, "MediaProjection started")

        // STEP 3 - camera FGS type, then STEP 4 - CameraX / MediaPipe.
        addCameraType()
        startGestureRecognition()

        if (gestureReady) {
            markRunning()
        } else {
            broadcastState("Starting camera gesture engine…", running = false)
        }
    }

    private fun markRunning() {
        finishStarting()
        running = true
        refreshWakeLock()
        refreshNotification()
        Log.i(TAG, "PalmLink active (captureReady=$captureReady)")
        broadcastState(
            if (captureReady) "PalmLink is active. Use open palm → fist to capture."
            else "PalmLink is active for receiving. Re-enable screen capture to take screenshots.",
        )
    }

    private class ScreenSize(val width: Int, val height: Int, val densityDpi: Int)

    private fun screenSize(): ScreenSize {
        val metrics = resources.displayMetrics
        val windowManager = getSystemService<WindowManager>()
        var width = metrics.widthPixels
        var height = metrics.heightPixels
        if (windowManager != null) {
            if (Build.VERSION.SDK_INT >= 30) {
                val bounds = windowManager.maximumWindowMetrics.bounds
                width = bounds.width()
                height = bounds.height()
            } else {
                val real = DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(real)
                width = real.widthPixels
                height = real.heightPixels
            }
        }
        return ScreenSize(width.coerceAtLeast(1), height.coerceAtLeast(1), metrics.densityDpi.coerceAtLeast(1))
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        if (projection != null) return

        val manager = getSystemService(MediaProjectionManager::class.java)
            ?: error("MediaProjection service unavailable")
        val newProjection = manager.getMediaProjection(resultCode, data)
            ?: error("MediaProjection permission was not granted")

        // Android 14+: a callback must be registered before createVirtualDisplay().
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                // Also fires when the screen is locked (documented platform behavior).
                Log.i(TAG, "MediaProjection stopped by the system or user")
                mainHandler.post { onProjectionEnded() }
            }
        }
        newProjection.registerCallback(callback, captureHandler)
        projectionCallback = callback
        projection = newProjection

        val size = screenSize()
        val reader = ImageReader.newInstance(size.width, size.height, PixelFormat.RGBA_8888, MAX_IMAGES)
        imageReader = reader
        reader.setOnImageAvailableListener({ source -> onFrameAvailable(source) }, captureHandler)

        virtualDisplay = newProjection.createVirtualDisplay(
            "PalmLinkScreenCapture",
            size.width,
            size.height,
            size.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            captureHandler,
        ) ?: error("Could not create the screen capture display")
    }

    /**
     * The projection ended on its own (screen lock, the user stopped sharing, another app took
     * over). Nearby and the gesture engine keep running so the phone can still RECEIVE.
     */
    private fun onProjectionEnded() {
        if (projection == null) return
        cleanupProjection(stopProjection = false)
        captureReady = false
        val message = "Screen capture ended (the screen was locked or sharing was stopped). " +
            "Re-enable screen capture to take screenshots; receiving still works."
        if (gestureReady || nearby.isActive()) {
            broadcastState(message)
            refreshNotification()
        } else {
            degradeOrStop(message)
        }
    }

    private fun startGestureRecognition() {
        if (gestureController?.isRunning == true) return
        gestureController?.stop()
        gestureReady = false
        val controller = GestureRecognizerController(
            context = this,
            lifecycleOwner = this,
            previewView = null,
            mode = GestureStateMachine.GestureMode.BACKGROUND,
            onState = { state ->
                broadcastGestureState(state.label.name, state.confidence, state.handQuality, state.message)
            },
            receivePending = { nearbyPendingOffer },
            onReady = {
                gestureReady = true
                if (foregroundStarted) markRunning()
            },
            onFatalError = { message ->
                Log.e(TAG, "Gesture engine fatal error: $message")
                gestureReady = false
                running = false
                degradeOrStop(message)
            },
            onAction = { action ->
                when (action) {
                    // Open palm -> fist: capture; the finished screenshot is sent automatically.
                    GestureStateMachine.Action.CAPTURE -> requestCapture("gesture")
                    // Fist -> open palm: authorize the pending (or imminent) incoming screenshot.
                    GestureStateMachine.Action.RECEIVE_ACCEPT -> nearby.authorizeReceive()
                    else -> Unit
                }
            },
        )
        gestureController = controller
        controller.start()
    }

    // ---------------------------------------------------------------------------------------
    // Capture pipeline
    //
    // A VirtualDisplay only delivers a frame when the screen content changes. Waiting for "the
    // next frame" after a gesture would therefore stall on a static screen. Instead the newest
    // frame is always kept (one Image held), and a capture copies that frame immediately.
    // ---------------------------------------------------------------------------------------

    private fun onFrameAvailable(reader: ImageReader) {
        try {
            val image = reader.acquireLatestImage() ?: return
            synchronized(frameLock) {
                latestFrame?.close()
                latestFrame = image
            }
            if (waitingForFrame) {
                waitingForFrame = false
                captureHandler.removeCallbacks(frameTimeout)
                captureLatestFrame()
            }
        } catch (error: Exception) {
            // Closed reader during shutdown, or a transient buffer error: never crash on the
            // capture thread.
            Log.w(TAG, "Could not read a screen frame", error)
        }
    }

    private fun requestCapture(source: String) {
        if (!captureReady || projection == null || imageReader == null) {
            broadcastState("Screen capture is not ready. Re-enable screen capture.")
            return
        }
        if (!captureInFlight.compareAndSet(false, true)) return
        broadcastState(if (source == "gesture") "Gesture recognized. Capturing…" else "Capturing…")
        val feedbackWasVisible = visualFeedback.isActive()
        if (feedbackWasVisible) mainHandler.post { visualFeedback.clear() }
        val posted = try {
            // If visual feedback was already on screen, give the display composition a moment to
            // settle before taking the next frame so the previous overlay can never become part
            // of a subsequent screenshot.
            if (feedbackWasVisible) {
                captureHandler.postDelayed({ captureLatestFrame() }, 180L)
            } else {
                captureHandler.post { captureLatestFrame() }
            }
            true
        } catch (error: Exception) {
            false
        }
        if (!posted) captureInFlight.set(false)
    }

    /** Runs on the capture thread. */
    private fun captureLatestFrame() {
        var bitmap: Bitmap? = null
        try {
            bitmap = synchronized(frameLock) { latestFrame?.let { copyFrameToBitmap(it) } }
            if (bitmap == null) {
                waitingForFrame = true
                captureHandler.removeCallbacks(frameTimeout)
                captureHandler.postDelayed(frameTimeout, FRAME_WAIT_MS)
                return
            }
            writePng(bitmap)
        } catch (error: Exception) {
            Log.e(TAG, "Screenshot failed", error)
            broadcastState("Screenshot failed: ${error.message ?: "could not read screen frame"}")
        } finally {
            bitmap?.let { runCatching { it.recycle() } }
            if (!waitingForFrame) captureInFlight.set(false)
        }
    }

    private fun copyFrameToBitmap(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride <= 0 || rowStride <= 0) return null

        val width = image.width
        val height = image.height
        val paddedWidth = (rowStride / pixelStride).coerceAtLeast(width)

        val source = plane.buffer
        source.rewind()
        val needed = paddedWidth * pixelStride * height
        // The last row of some buffers is not padded to the full stride; give the Bitmap a
        // buffer that is guaranteed to be large enough.
        val buffer: ByteBuffer = if (source.remaining() >= needed) {
            source
        } else {
            ByteBuffer.allocateDirect(needed).also {
                it.put(source)
                it.rewind()
            }
        }

        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        try {
            padded.copyPixelsFromBuffer(buffer)
        } catch (error: Exception) {
            padded.recycle()
            throw error
        }
        if (paddedWidth == width) return padded
        return try {
            Bitmap.createBitmap(padded, 0, 0, width, height)
        } finally {
            padded.recycle()
        }
    }

    private fun writePng(bitmap: Bitmap) {
        val dir = File(filesDir, "captures").apply { mkdirs() }
        val output = File(dir, "capture-${System.currentTimeMillis()}.png")
        val written = try {
            FileOutputStream(output).use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream) }
        } catch (error: Exception) {
            output.delete()
            throw error
        }
        if (!written) {
            output.delete()
            broadcastState("Screenshot failed: could not encode the screen image")
            return
        }
        captureStore.saveLatest(output)
        mainHandler.post {
            if (feedbackSettings.shutterSoundEnabled) runCatching { shutterSound?.play(MediaActionSound.SHUTTER_CLICK) }
            visualFeedback.showCapture(output, gallerySaved = false)
        }
        mediaExecutor.execute {
            val gallerySaved = MediaStoreSaver.saveToPictures(this, output)
            if (alive) mainHandler.post { visualFeedback.updateCaptureGallerySaved(gallerySaved) }
        }
        sendBroadcast(
            Intent(ACTION_CAPTURE_COMPLETE)
                .setPackage(packageName)
                .putExtra(EXTRA_PATH, output.absolutePath),
        )
        Log.i(TAG, "Screenshot saved: ${output.name}")
        broadcastState("Screenshot captured. Sending to the connected device…")
        // The capture succeeded, so hand it to the Nearby runtime. Open palm -> fist therefore
        // means "capture and send". With no peer the runtime keeps the file and offers it on
        // reconnect; the screenshot itself is never lost.
        mainHandler.post {
            try {
                nearby.sendScreenshot(output)
            } catch (error: Exception) {
                Log.e(TAG, "Automatic send failed", error)
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Failure handling and teardown
    // ---------------------------------------------------------------------------------------

    /**
     * Single exit for every startup/runtime failure. Safe at any point after the first successful
     * startForeground(); it never throws. It releases the camera and the projection, and keeps the
     * service alive only while Nearby still has something to do.
     */
    private fun degradeOrStop(message: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            // CameraX unbinding and service teardown belong on the main thread.
            mainHandler.post { degradeOrStop(message) }
            return
        }
        Log.e(TAG, "Degrading/stopping: $message")
        runCatching { stopGestureRecognition() }
        runCatching { cleanupProjection(true) }
        running = false
        captureReady = false
        finishStarting()
        runCatching { broadcastState(message, running = false) }
        if (!foregroundStarted) runCatching { promoteInitial(false) } // answer startForegroundService()
        if (!nearby.isActive()) {
            runCatching { stopSelf() }
        } else {
            runCatching { refreshNotification() }
            runCatching { refreshWakeLock() }
        }
    }

    private fun describe(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

    private fun stopGestureRecognition() {
        gestureReady = false
        val controller = gestureController
        gestureController = null
        controller?.stop()
    }

    @Synchronized
    private fun cleanupProjection(stopProjection: Boolean = true) {
        waitingForFrame = false
        captureInFlight.set(false)
        if (::captureHandler.isInitialized) captureHandler.removeCallbacks(frameTimeout)

        runCatching { imageReader?.setOnImageAvailableListener(null, null) }
        runCatching { virtualDisplay?.release() }
        synchronized(frameLock) {
            runCatching { latestFrame?.close() }
            latestFrame = null
        }
        runCatching { imageReader?.close() }
        virtualDisplay = null
        imageReader = null

        val currentProjection = projection
        val callback = projectionCallback
        projection = null
        projectionCallback = null
        if (currentProjection != null && callback != null) {
            runCatching { currentProjection.unregisterCallback(callback) }
        }
        if (stopProjection) runCatching { currentProjection?.stop() }
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroying")
        alive = false
        running = false
        captureReady = false
        finishStarting()
        runCatching { mainHandler.removeCallbacksAndMessages(null) }
        runCatching { stopGestureRecognition() }
        if (::nearby.isInitialized) {
            runCatching { nearby.removeStateListener(nearbyStateListener) }
            // The service owns the Nearby session: when it ends, the session ends.
            runCatching { nearby.stopAll() }
        }
        runCatching { cleanupProjection() }
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        if (foregroundStarted) {
            foregroundStarted = false
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        }
        if (::captureHandler.isInitialized) runCatching { captureHandler.removeCallbacksAndMessages(null) }
        if (::captureThread.isInitialized) runCatching { captureThread.quitSafely() }
        runCatching { visualFeedback.clear() }
        runCatching { shutterSound?.release() }
        shutterSound = null
        if (::mediaExecutor.isInitialized) runCatching { mediaExecutor.shutdownNow() }
        runCatching {
            sendBroadcast(
                Intent(ACTION_SERVICE_STATE)
                    .setPackage(packageName)
                    .putExtra(EXTRA_MESSAGE, "PalmLink stopped.")
                    .putExtra(EXTRA_RUNNING, false)
                    .putExtra(EXTRA_CAPTURE_READY, false),
            )
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------
    // Keep-awake, notification, broadcasts
    // ---------------------------------------------------------------------------------------

    /**
     * Gestures and Nearby must keep working with the screen off, where Android would otherwise
     * let the CPU sleep. A timed partial wake lock is held (and renewed) only while the session
     * is actually doing something.
     */
    private fun refreshWakeLock() {
        try {
            val needed = running || (::nearby.isInitialized && nearby.isActive())
            var lock = wakeLock
            if (lock == null) {
                lock = getSystemService<PowerManager>()?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PalmLink:session")
                lock?.setReferenceCounted(false)
                wakeLock = lock
            }
            if (lock == null) return
            mainHandler.removeCallbacks(wakeTick)
            if (needed) {
                lock.acquire(WAKE_LOCK_MS)
                mainHandler.postDelayed(wakeTick, WAKE_LOCK_MS - 60_000L)
            } else if (lock.isHeld) {
                lock.release()
            }
        } catch (error: Exception) {
            Log.w(TAG, "Wake lock unavailable", error)
        }
    }

    private fun statusLine(): String {
        val s = nearby.currentState()
        val peer = if (s.connected) (s.endpointName ?: "a device") else null
        return when {
            s.pendingOffer != null -> "Screenshot incoming - make a fist, then open your palm"
            s.receiving -> "Receiving a screenshot…"
            s.sending -> "Sending a screenshot…"
            captureReady && peer != null -> "Connected to $peer - open palm → fist to capture"
            captureReady -> "Listening for gestures - no device connected"
            running && peer != null -> "Connected to $peer - make a fist, then open your palm to receive"
            peer != null -> "Connected to $peer"
            s.advertising -> "Visible to nearby devices"
            s.discovering -> "Looking for nearby devices"
            else -> "PalmLink is running"
        }
    }

    private fun refreshNotification() {
        if (!foregroundStarted) return
        val text = statusLine()
        if (text == lastNotificationText) return
        lastNotificationText = text
        runCatching { getSystemService<NotificationManager>()?.notify(NOTIFICATION_ID, notification(text)) }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun notification(text: String = defaultStatusLine()) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(text)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setOngoing(true)
        .setSilent(true)
        .addAction(
            NotificationCompat.Action.Builder(
                R.drawable.ic_launcher,
                "Stop",
                android.app.PendingIntent.getService(
                    this,
                    10,
                    Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
                ),
            ).build(),
        )
        .build()

    private fun defaultStatusLine(): String =
        if (::nearby.isInitialized) statusLine() else "PalmLink is running"

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService<NotificationManager>() ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.capture_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun broadcastState(message: String, running: Boolean = ScreenCaptureService.running) {
        sendBroadcast(
            Intent(ACTION_SERVICE_STATE)
                .setPackage(packageName)
                .putExtra(EXTRA_MESSAGE, message)
                .putExtra(EXTRA_RUNNING, running)
                .putExtra(EXTRA_CAPTURE_READY, captureReady),
        )
    }

    private fun broadcastGestureState(label: String, confidence: Float?, handQuality: Float, message: String) {
        val intent = Intent(ACTION_GESTURE_STATE)
            .setPackage(packageName)
            .putExtra(EXTRA_LABEL, label)
            .putExtra(EXTRA_HAND_QUALITY, handQuality)
            .putExtra(EXTRA_MESSAGE, message)
        confidence?.let { intent.putExtra(EXTRA_CONFIDENCE, it) }
        sendBroadcast(intent)
    }

    private inline fun <reified T : android.os.Parcelable> Intent.getParcelableExtraCompat(key: String): T? {
        return if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, T::class.java)
        else @Suppress("DEPRECATION") getParcelableExtra(key)
    }
}
