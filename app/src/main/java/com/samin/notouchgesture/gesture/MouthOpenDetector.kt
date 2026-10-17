package com.samin.notouchgesture.gesture

import android.graphics.Bitmap
import android.os.SystemClock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Lightweight foreground-only mouth-open detector for the experimental Air Mic feature.
 * ML Kit is used only when a caller explicitly supplies an onMouthOpen callback.
 */
class MouthOpenDetector(private val onMouthOpen: () -> Unit) {
    private val detector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .setMinFaceSize(0.15f)
            .build(),
    )
    private val busy = AtomicBoolean(false)
    private var openSince = Long.MIN_VALUE
    private var lastTrigger = Long.MIN_VALUE

    fun analyze(bitmap: Bitmap, rotationDegrees: Int) {
        val now = SystemClock.uptimeMillis()
        if (now - lastTrigger < 2500L || !busy.compareAndSet(false, true)) return
        val copy = runCatching { bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: run {
            busy.set(false)
            return
        }
        detector.process(InputImage.fromBitmap(copy, rotationDegrees))
            .addOnSuccessListener { faces ->
                val open = faces.firstOrNull()?.let(::isMouthOpen) == true
                val t = SystemClock.uptimeMillis()
                if (open) {
                    if (openSince == Long.MIN_VALUE) openSince = t
                    if (t - openSince >= 260L && (lastTrigger == Long.MIN_VALUE || t - lastTrigger >= 2500L)) {
                        lastTrigger = t
                        openSince = Long.MIN_VALUE
                        onMouthOpen()
                    }
                } else {
                    openSince = Long.MIN_VALUE
                }
            }
            .addOnCompleteListener {
                busy.set(false)
                copy.recycle()
            }
    }

    private fun isMouthOpen(face: Face): Boolean {
        val top = face.getContour(FaceContour.UPPER_LIP_TOP)?.points ?: return false
        val bottom = face.getContour(FaceContour.LOWER_LIP_BOTTOM)?.points ?: return false
        if (top.isEmpty() || bottom.isEmpty()) return false
        val topY = top.map { it.y }.average().toFloat()
        val bottomY = bottom.map { it.y }.average().toFloat()
        val mouthHeight = abs(bottomY - topY)
        val faceWidth = face.boundingBox.width().toFloat().coerceAtLeast(1f)
        return mouthHeight / faceWidth > 0.035f
    }

    fun close() {
        detector.close()
    }
}
