package com.samin.notouchgesture.capture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import android.content.pm.ServiceInfo
import com.samin.notouchgesture.R
import java.io.File
import java.io.FileOutputStream

class ScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var resultData: Intent? = null
    private val handler = Handler(Looper.getMainLooper())
    private var projectionCallback: MediaProjection.Callback? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        resultData = intent?.getParcelableExtraCompat(EXTRA_RESULT_DATA)
        if (resultCode == -1 || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification())
        }
        handler.post { captureOnce(resultCode, resultData!!) }
        return START_NOT_STICKY
    }

    private fun captureOnce(resultCode: Int, data: Intent) {
        val manager = getSystemService<MediaProjectionManager>() ?: return stopSelf()
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        projection = manager.getMediaProjection(resultCode, data)
        projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                cleanup()
            }
        }
        projection?.registerCallback(projectionCallback!!, handler)

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection?.createVirtualDisplay(
            "NoTouchGestureCapture",
            width,
            height,
            density,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            handler,
        )

        handler.postDelayed({
            val image = imageReader?.acquireLatestImage()
            if (image == null) {
                stopSelf()
                return@postDelayed
            }
            try {
                val plane = image.planes.first()
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width
                val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(plane.buffer)
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                bitmap.recycle()

                val dir = File(filesDir, "captures").apply { mkdirs() }
                val output = File(dir, "capture-${System.currentTimeMillis()}.png")
                FileOutputStream(output).use { stream ->
                    cropped.compress(Bitmap.CompressFormat.PNG, 100, stream)
                }
                cropped.recycle()
                CaptureStore(this).saveLatest(output)

                sendBroadcast(Intent(ACTION_CAPTURE_COMPLETE).setPackage(packageName).putExtra(EXTRA_PATH, output.absolutePath))
            } finally {
                image.close()
                stopSelf()
            }
        }, 250L)
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher)
        .setContentTitle(getString(R.string.capture_notification_title))
        .setContentText("Finishing the current screenshot capture")
        .setOngoing(true)
        .setSilent(true)
        .build()

    private fun createNotificationChannel() {
        val manager = getSystemService<NotificationManager>() ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.capture_channel_name), NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun cleanup() {
        val display = virtualDisplay
        val reader = imageReader
        val currentProjection = projection
        val callback = projectionCallback
        virtualDisplay = null
        imageReader = null
        projection = null
        projectionCallback = null
        runCatching { display?.release() }
        runCatching { reader?.close() }
        if (currentProjection != null) {
            runCatching { callback?.let { currentProjection.unregisterCallback(it) } }
            runCatching { currentProjection.stop() }
        }
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private inline fun <reified T : android.os.Parcelable> Intent.getParcelableExtraCompat(key: String): T? {
        return if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, T::class.java) else @Suppress("DEPRECATION") getParcelableExtra(key)
    }

    companion object {
        const val ACTION_CAPTURE_COMPLETE = "com.samin.notouchgesture.CAPTURE_COMPLETE"
        const val EXTRA_PATH = "path"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 43
    }
}
