package com.samin.notouchgesture.capture

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
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
import java.util.concurrent.atomic.AtomicBoolean

/** Keeps one user-approved MediaProjection session alive and listens for PalmLink gestures. */
class ScreenCaptureService : LifecycleService() {
    private lateinit var captureThread: HandlerThread
    private lateinit var captureHandler: Handler
    private val captureRequested = AtomicBoolean(false)

    private var projection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var gestureController: GestureRecognizerController? = null
    private var foregroundStarted = false
    @Volatile private var nearbyPendingOffer = false
    @Volatile private var gestureReady = false
    private lateinit var nearby: NearbyTransferManager
    private lateinit var captureStore: CaptureStore

    private val nearbyStateListener: (NearbyTransferManager.State) -> Unit = { state ->
        state.lastReceivedFile?.let(captureStore::saveLatest)
        nearbyPendingOffer = state.pendingOffer != null
        broadcastNearbyState(state)
    }

    companion object {
        @Volatile
        var running: Boolean = false
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
        const val EXTRA_PATH = "path"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_LABEL = "label"
        const val EXTRA_CONFIDENCE = "confidence"
        const val EXTRA_HAND_QUALITY = "hand_quality"
        const val EXTRA_RUNNING = "running"
        const val EXTRA_NEARBY_MESSAGE = "nearby_message"
        const val EXTRA_NEARBY_CONNECTED = "nearby_connected"
        const val EXTRA_NEARBY_PENDING_OFFER = "nearby_pending_offer"
        private const val CHANNEL_ID = "palmlink_background"
        private const val NOTIFICATION_ID = 43
    }

    override fun onCreate() {
        super.onCreate()
        captureThread = HandlerThread("PalmLink-Capture").also { it.start() }
        captureHandler = Handler(captureThread.looper)
        captureStore = CaptureStore(this)
        nearby = NearbyRuntime.get(this)
        nearby.addStateListener(nearbyStateListener)
        running = false
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                finishStarting()
                broadcastState("PalmLink stopped.", running = false)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CAPTURE_NOW -> requestCapture("manual")
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val resultData = intent?.getParcelableExtraCompat<Intent>(EXTRA_RESULT_DATA)

        // A running service may receive a command without new MediaProjection consent.
        if (projection != null) {
            finishStarting()
            if (!running) {
                running = true
                broadcastState("PalmLink is active. Use open palm → fist to capture.")
            }
            return START_NOT_STICKY
        }

        if (resultCode == -1 || resultData == null) {
            finishStarting()
            if (!foregroundStarted) {
                broadcastState("Screen capture permission is required to enable PalmLink.", running = false)
                stopSelf()
            }
            return START_NOT_STICKY
        }

        if (!hasCameraPermission()) {
            finishStarting()
            broadcastState("Camera permission is required for background gestures.", running = false)
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            // Android checks the declared FGS type prerequisites at promotion time. The
            // Activity starts this service immediately after the user's visible consent flow.
            startAsForeground()
            startProjection(resultCode, resultData)
            startGestureRecognition()
            // The service is considered fully active only after CameraX has bound.
            // Until then the notification/state says that startup is still in progress.
            if (gestureReady) {
                finishStarting()
                running = true
                broadcastState("PalmLink is active. Use open palm → fist to capture.")
            } else {
                broadcastState("Starting camera gesture engine…", running = false)
            }
        } catch (error: SecurityException) {
            finishStarting()
            running = false
            stopGestureRecognition()
            cleanupProjection()
            broadcastState("PalmLink could not start: ${error.message ?: "permission denied"}", running = false)
            stopSelf()
        } catch (error: Exception) {
            finishStarting()
            running = false
            stopGestureRecognition()
            cleanupProjection()
            broadcastState("PalmLink could not start: ${error.message ?: "unknown error"}", running = false)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        if (foregroundStarted) return

        // Keep the foreground service scope limited to the two capabilities this service
        // actually uses. Nearby runs through the shared client and does not need the
        // connectedDevice FGS type here, which removes an unnecessary runtime prerequisite.
        val types = when {
            Build.VERSION.SDK_INT >= 29 -> {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            else -> 0
        }

        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), types)
        } else {
            startForeground(NOTIFICATION_ID, notification())
        }
        foregroundStarted = true
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        if (projection != null) return

        val manager = getSystemService(MediaProjectionManager::class.java)
            ?: error("MediaProjection service unavailable")
        val newProjection = manager.getMediaProjection(resultCode, data)
            ?: error("MediaProjection permission was not granted")

        projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                running = false
                broadcastState("Screen capture permission ended. Enable PalmLink again.", running = false)
                cleanupProjection(stopProjection = false)
                stopSelf()
            }
        }
        newProjection.registerCallback(projectionCallback!!, captureHandler)
        projection = newProjection

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        val density = metrics.densityDpi.coerceAtLeast(1)

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        imageReader!!.setOnImageAvailableListener({ reader ->
            val image = try {
                reader.acquireLatestImage()
            } catch (error: Exception) {
                captureRequested.set(false)
                if (projection != null) {
                    running = false
                    broadcastState("Screen capture failed: ${error.message ?: "could not read a screen frame"}", running = false)
                    stopSelf()
                }
                return@setOnImageAvailableListener
            } ?: return@setOnImageAvailableListener
            if (!captureRequested.compareAndSet(true, false)) {
                image.close()
                return@setOnImageAvailableListener
            }
            saveProjectionImage(image, width)
        }, captureHandler)

        virtualDisplay = newProjection.createVirtualDisplay(
            "PalmLinkScreenCapture",
            width,
            height,
            density,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            captureHandler,
        ) ?: error("Could not create the screen capture display")
    }

    private fun startGestureRecognition() {
        if (gestureController != null) return
        gestureReady = false
        gestureController = GestureRecognizerController(
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
                finishStarting()
                if (projection != null && foregroundStarted) {
                    running = true
                    broadcastState("PalmLink is active. Use open palm → fist to capture.")
                }
            },
            onFatalError = { message ->
                finishStarting()
                if (!running) {
                    gestureReady = false
                    broadcastState(message, running = false)
                    stopSelf()
                }
            },
            onAction = { action ->
                when (action) {
                    GestureStateMachine.Action.CAPTURE -> requestCapture("gesture")
                    GestureStateMachine.Action.SEND_OFFER -> captureStore.latestFile()?.let(nearby::sendOffer)
                    GestureStateMachine.Action.RECEIVE_ACCEPT -> nearby.acceptPendingOffer()
                    GestureStateMachine.Action.ARMED,
                    GestureStateMachine.Action.NONE -> Unit
                }
            },
        )
        gestureController!!.start()
    }

    private fun requestCapture(source: String) {
        if (projection == null || imageReader == null) {
            broadcastState("Screen capture is not ready. Enable PalmLink again.")
            return
        }
        if (!captureRequested.compareAndSet(false, true)) return
        broadcastState(if (source == "gesture") "Gesture recognized. Capturing…" else "Capturing…")
    }

    private fun saveProjectionImage(image: Image, width: Int) {
        var bitmap: Bitmap? = null
        var cropped: Bitmap? = null
        try {
            val plane = image.planes.firstOrNull() ?: return
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            if (pixelStride <= 0 || rowStride <= 0) return

            val rowPadding = rowStride - pixelStride * width
            val paddedWidth = (width + rowPadding / pixelStride).coerceAtLeast(width)
            bitmap = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(plane.buffer)
            cropped = if (paddedWidth == width) bitmap else Bitmap.createBitmap(bitmap, 0, 0, width, image.height)

            val dir = File(filesDir, "captures").apply { mkdirs() }
            val output = File(dir, "capture-${System.currentTimeMillis()}.png")
            val written = FileOutputStream(output).use { stream ->
                cropped!!.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }

            if (!written) {
                output.delete()
                broadcastState("Screenshot failed: could not encode the screen image")
                return
            }

            captureStore.saveLatest(output)
            sendBroadcast(
                Intent(ACTION_CAPTURE_COMPLETE)
                    .setPackage(packageName)
                    .putExtra(EXTRA_PATH, output.absolutePath),
            )
            broadcastState("Screenshot captured. PalmLink remains active in the background.")
        } catch (error: Exception) {
            broadcastState("Screenshot failed: ${error.message ?: "could not read screen frame"}")
        } finally {
            if (cropped != null && cropped !== bitmap) runCatching { cropped.recycle() }
            if (bitmap != null) runCatching { bitmap.recycle() }
            image.close()
        }
    }

    private fun broadcastNearbyState(state: NearbyTransferManager.State) {
        sendBroadcast(
            Intent(ACTION_NEARBY_STATE)
                .setPackage(packageName)
                .putExtra(EXTRA_NEARBY_MESSAGE, state.message)
                .putExtra(EXTRA_NEARBY_CONNECTED, state.connected)
                .putExtra(EXTRA_NEARBY_PENDING_OFFER, state.pendingOffer != null),
        )
    }

    private fun stopGestureRecognition() {
        gestureReady = false
        gestureController?.stop()
        gestureController = null
    }

    private fun cleanupProjection(stopProjection: Boolean = true) {
        captureRequested.set(false)
        runCatching { imageReader?.setOnImageAvailableListener(null, null) }
        runCatching { virtualDisplay?.release() }
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
        running = false
        finishStarting()
        stopGestureRecognition()
        nearby.removeStateListener(nearbyStateListener)
        cleanupProjection()
        foregroundStarted = false
        runCatching { if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) }
        if (::captureHandler.isInitialized) captureHandler.removeCallbacksAndMessages(null)
        if (::captureThread.isInitialized) captureThread.quitSafely()
        sendBroadcast(
            Intent(ACTION_SERVICE_STATE)
                .setPackage(packageName)
                .putExtra(EXTRA_MESSAGE, "PalmLink stopped.")
                .putExtra(EXTRA_RUNNING, false),
        )
        super.onDestroy()
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun notification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher)
        .setContentTitle(getString(R.string.app_name))
        .setContentText("PalmLink is listening for hand gestures")
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
                .putExtra(EXTRA_RUNNING, running),
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
