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
import android.os.IBinder
import android.os.Looper
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

/** Keeps one user-approved screen projection alive and listens for PalmLink gestures. */
class ScreenCaptureService : LifecycleService() {
    private val handler = Handler(Looper.getMainLooper())
    private val captureRequested = AtomicBoolean(false)

    private var projection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var gestureController: GestureRecognizerController? = null
    private var foregroundStarted = false
    private var nearbyPendingOffer = false
    private lateinit var nearby: NearbyTransferManager
    private val nearbyStateListener: (NearbyTransferManager.State) -> Unit = { state ->
        state.lastReceivedFile?.let { CaptureStore(this).saveLatest(it) }
        nearbyPendingOffer = state.pendingOffer != null
        broadcastNearbyState(state)
    }

    companion object {
        @Volatile
        var running: Boolean = false
            private set

        const val ACTION_CAPTURE_COMPLETE = "com.samin.notouchgesture.CAPTURE_COMPLETE"
        const val ACTION_SERVICE_STATE = "com.samin.notouchgesture.SERVICE_STATE"
        const val ACTION_GESTURE_STATE = "com.samin.notouchgesture.GESTURE_STATE"
        const val ACTION_GESTURE_SEND = "com.samin.notouchgesture.GESTURE_SEND"
        const val ACTION_GESTURE_RECEIVE = "com.samin.notouchgesture.GESTURE_RECEIVE"
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
        running = true
        createNotificationChannel()
        nearby = NearbyRuntime.get(this)
        nearby.addStateListener(nearbyStateListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                broadcastState("PalmLink stopped.", running = false)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CAPTURE_NOW -> requestCapture("manual")
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val resultData = intent?.getParcelableExtraCompat<Intent>(EXTRA_RESULT_DATA)

        // Commands may arrive after the projection session is already running.
        if (resultCode == -1 || resultData == null) {
            return if (foregroundStarted) START_NOT_STICKY else {
                stopSelf()
                START_NOT_STICKY
            }
        }

        if (!hasCameraPermission()) {
            broadcastState("Camera permission is required for background gestures.", running = false)
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            startAsForeground()
            startProjection(resultCode, resultData)
            startGestureRecognition()
            startNearbyIfPermitted()
        } catch (error: SecurityException) {
            broadcastState("PalmLink could not start: ${error.message ?: "permission denied"}", running = false)
            stopSelf()
        } catch (error: Exception) {
            broadcastState("PalmLink could not start: ${error.message ?: "unknown error"}", running = false)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        if (foregroundStarted) return
        val types = if (Build.VERSION.SDK_INT >= 29) {
            var value = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (hasNearbyPermissions()) {
                value = value or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            value
        } else 0

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
        projection = manager.getMediaProjection(resultCode, data)
            ?: error("MediaProjection permission was not granted")

        projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                broadcastState("Screen capture permission was revoked. Re-enable PalmLink.", running = false)
                cleanupProjection(stopProjection = false)
                stopSelf()
            }
        }
        projection!!.registerCallback(projectionCallback!!, handler)

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader!!.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            if (!captureRequested.compareAndSet(true, false)) {
                image.close()
                return@setOnImageAvailableListener
            }
            saveProjectionImage(image, width)
        }, handler)

        virtualDisplay = projection!!.createVirtualDisplay(
            "PalmLinkScreenCapture",
            width,
            height,
            density,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            handler,
        )
        broadcastState("PalmLink is active. Use open palm → fist to capture.")
    }

    private fun startGestureRecognition() {
        if (gestureController != null) return
        gestureController = GestureRecognizerController(
            context = this,
            lifecycleOwner = this,
            previewView = null,
            mode = GestureStateMachine.GestureMode.BACKGROUND,
            onState = { state ->
                broadcastGestureState(state.label.name, state.confidence, state.handQuality, state.message)
            },
            receivePending = { nearbyPendingOffer },
            onAction = { action ->
                when (action) {
                    GestureStateMachine.Action.CAPTURE -> requestCapture("gesture")
                    GestureStateMachine.Action.SEND_OFFER -> {
                        CaptureStore(this).latestFile()?.let { nearby.sendOffer(it) }
                    }
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
            broadcastState("Screen capture is not ready. Re-enable PalmLink first.")
            return
        }
        if (!captureRequested.compareAndSet(false, true)) return
        broadcastState(if (source == "gesture") "Gesture recognized. Capturing…" else "Capturing…")
    }

    private fun saveProjectionImage(image: Image, width: Int) {
        try {
            val plane = image.planes.firstOrNull() ?: return
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width
            val paddedWidth = width + rowPadding / pixelStride
            val bitmap = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(plane.buffer)
            val cropped = if (paddedWidth == width) bitmap else Bitmap.createBitmap(bitmap, 0, 0, width, image.height)
            if (cropped !== bitmap) bitmap.recycle()

            val dir = File(filesDir, "captures").apply { mkdirs() }
            val output = File(dir, "capture-${System.currentTimeMillis()}.png")
            FileOutputStream(output).use { stream ->
                cropped.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            cropped.recycle()
            CaptureStore(this).saveLatest(output)
            sendBroadcast(
                Intent(ACTION_CAPTURE_COMPLETE)
                    .setPackage(packageName)
                    .putExtra(EXTRA_PATH, output.absolutePath),
            )
            broadcastState("Screenshot captured. PalmLink remains active in the background.")
        } catch (error: Exception) {
            broadcastState("Screenshot failed: ${error.message ?: "could not read screen frame"}")
        } finally {
            image.close()
        }
    }

    private fun startNearbyIfPermitted() {
        if (!hasNearbyPermissions()) return
        nearby.startAdvertising()
        nearby.startDiscovery()
    }

    private fun hasNearbyPermissions(): Boolean {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        return permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
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

    private fun cleanupProjection(stopProjection: Boolean = true) {
        captureRequested.set(false)
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
        gestureController?.stop()
        gestureController = null
        nearby.removeStateListener(nearbyStateListener)
        cleanupProjection()
        foregroundStarted = false
        sendBroadcast(Intent(ACTION_SERVICE_STATE).setPackage(packageName).putExtra(EXTRA_MESSAGE, "PalmLink stopped.").putExtra(EXTRA_RUNNING, false))
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = null

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
                0,
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
        val manager = getSystemService<NotificationManager>() ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.capture_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun broadcastState(message: String, running: Boolean = true) {
        sendBroadcast(
            Intent(ACTION_SERVICE_STATE)
                .setPackage(packageName)
                .putExtra(EXTRA_MESSAGE, message)
                .putExtra(EXTRA_RUNNING, running),
        )
    }

    private fun broadcastGestureState(label: String, confidence: Float, handQuality: Float, message: String) {
        sendBroadcast(
            Intent(ACTION_GESTURE_STATE)
                .setPackage(packageName)
                .putExtra(EXTRA_LABEL, label)
                .putExtra(EXTRA_CONFIDENCE, confidence)
                .putExtra(EXTRA_HAND_QUALITY, handQuality)
                .putExtra(EXTRA_MESSAGE, message),
        )
    }

    private fun broadcastGestureAction(action: String) {
        sendBroadcast(Intent(action).setPackage(packageName))
    }

    private inline fun <reified T : android.os.Parcelable> Intent.getParcelableExtraCompat(key: String): T? {
        return if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, T::class.java)
        else @Suppress("DEPRECATION") getParcelableExtra(key)
    }
}
