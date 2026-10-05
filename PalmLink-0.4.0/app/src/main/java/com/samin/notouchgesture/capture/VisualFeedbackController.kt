package com.samin.notouchgesture.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.samin.notouchgesture.R
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Lightweight system-overlay visual feedback used when PalmLink operates outside its Activity.
 * The overlay is non-interactive so it never steals touches from the app beneath it.
 */
class VisualFeedbackController(context: Context) {
    enum class Direction { SENDING, RECEIVING }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = appContext.getSystemService(WindowManager::class.java)
    private val iconBitmap = BitmapFactory.decodeResource(appContext.resources, R.drawable.palmlink_logo)
    private var view: FeedbackView? = null
    private var attached = false

    fun showCapture(file: java.io.File?, gallerySaved: Boolean) {
        post { replace(FeedbackMode.CAPTURE, file, Direction.SENDING, gallerySaved) }
    }

    fun showTransfer(file: java.io.File?, direction: Direction, preview: Bitmap? = null) {
        post { replace(FeedbackMode.TRANSFER, file, direction, false, preview) }
    }

    fun updateTransferPreview(preview: Bitmap) {
        post {
            val current = view ?: return@post
            if (current.mode != FeedbackMode.TRANSFER || current.direction != Direction.RECEIVING) return@post
            current.setBitmap(preview)
        }
    }

    fun updateCaptureGallerySaved(saved: Boolean) {
        post {
            val current = view ?: return@post
            if (current.mode != FeedbackMode.CAPTURE) return@post
            current.setCaptureGallerySaved(saved)
        }
    }

    fun showSent(file: java.io.File?) {
        post { replace(FeedbackMode.COMPLETE, file, Direction.SENDING, false, status = "Screenshot sent ✓") }
    }

    fun showReceived(file: java.io.File, gallerySaved: Boolean) {
        post {
            replace(
                FeedbackMode.COMPLETE,
                file,
                Direction.RECEIVING,
                gallerySaved,
                status = if (gallerySaved) "Screenshot received ✓\nSaved to Gallery" else "Screenshot received ✓\nSaved in PalmLink",
            )
        }
    }

    fun showFailure(message: String) {
        post { replace(FeedbackMode.FAILURE, null, Direction.RECEIVING, false, status = message) }
    }

    fun clear() {
        post { removeCurrent() }
    }

    fun canShow(): Boolean = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(appContext)

    fun isActive(): Boolean = attached && view != null

    private fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun replace(
        mode: FeedbackMode,
        file: java.io.File?,
        direction: Direction,
        gallerySaved: Boolean,
        preview: Bitmap? = null,
        status: String? = null,
    ) {
        if (!canShow() || windowManager == null) return
        removeCurrent()
        val bitmap = preview ?: file?.let(::decodePreview)
        val feedback = FeedbackView(
            context = appContext,
            mode = mode,
            direction = direction,
            initialBitmap = bitmap,
            iconBitmap = iconBitmap,
            gallerySaved = gallerySaved,
            status = status,
            onFinished = { removeCurrent() },
        )
        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            flags,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "PalmLink visual feedback"
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        runCatching {
            windowManager.addView(feedback, params)
            view = feedback
            attached = true
            feedback.start()
        }
    }

    private fun removeCurrent() {
        val current = view ?: return
        view = null
        if (attached) runCatching { windowManager?.removeView(current) }
        attached = false
    }

    private fun decodePreview(file: java.io.File): Bitmap? {
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val target = 520
        var sample = 1
        while (bounds.outWidth / sample > target || bounds.outHeight / sample > target) sample *= 2
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private enum class FeedbackMode { CAPTURE, TRANSFER, COMPLETE, FAILURE }

    private class FeedbackView(
        context: Context,
        val mode: FeedbackMode,
        val direction: Direction,
        initialBitmap: Bitmap?,
        private val iconBitmap: Bitmap?,
        private val gallerySaved: Boolean,
        private val status: String?,
        private val onFinished: () -> Unit,
    ) : View(context) {
        private val density = resources.displayMetrics.density
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.create("sans", android.graphics.Typeface.NORMAL) }
        private val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD) }
        private val path = Path()
        private var startedAt = 0L
        private var bitmap: Bitmap? = initialBitmap
        private var captureGallerySaved = gallerySaved
        private var donePosted = false

        init {
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }

        fun setBitmap(newBitmap: Bitmap) {
            bitmap?.takeIf { it !== newBitmap }?.let { runCatching { it.recycle() } }
            bitmap = newBitmap
            invalidate()
        }

        fun setCaptureGallerySaved(saved: Boolean) {
            captureGallerySaved = saved
            invalidate()
        }

        override fun onDetachedFromWindow() {
            bitmap?.let { runCatching { it.recycle() } }
            bitmap = null
            super.onDetachedFromWindow()
        }

        fun start() {
            startedAt = SystemClock.uptimeMillis()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val elapsed = (SystemClock.uptimeMillis() - startedAt).coerceAtLeast(0L)
            val width = width.toFloat()
            val height = height.toFloat()
            when (mode) {
                FeedbackMode.CAPTURE -> drawCapture(canvas, width, height, elapsed)
                FeedbackMode.TRANSFER -> drawTransfer(canvas, width, height, elapsed)
                FeedbackMode.COMPLETE -> drawComplete(canvas, width, height, elapsed)
                FeedbackMode.FAILURE -> drawFailure(canvas, width, height, elapsed)
            }
            val duration = when (mode) {
                FeedbackMode.CAPTURE -> 3000L
                FeedbackMode.TRANSFER -> 15_000L
                FeedbackMode.COMPLETE -> 3200L
                FeedbackMode.FAILURE -> 2600L
            }
            if (elapsed >= duration && !donePosted) {
                donePosted = true
                mainHandlerPost { onFinished() }
            } else if (!donePosted) {
                postInvalidateOnAnimation()
            }
        }

        private fun drawCapture(canvas: Canvas, w: Float, h: Float, elapsed: Long) {
            val flashProgress = (elapsed / 220f).coerceIn(0f, 1f)
            val flashAlpha = if (flashProgress < 0.35f) 0.96f else (1f - flashProgress) * 0.52f
            if (flashAlpha > 0f) {
                fillPaint.color = Color.WHITE
                fillPaint.alpha = (flashAlpha * 255f).toInt().coerceIn(0, 255)
                canvas.drawRect(0f, 0f, w, h, fillPaint)
            }
            val outlineAlpha = when {
                elapsed < 220L -> 0.18f
                elapsed < 1200L -> 0.88f
                else -> 0.88f * (1f - ((elapsed - 1200L) / 1800f).coerceIn(0f, 1f))
            }
            drawGlowBorder(canvas, w, h, outlineAlpha, 1.4f + pulse(elapsed) * 1.5f)
            if (elapsed > 140L) {
                val subtitle = when {
                    captureGallerySaved -> "Saved to Gallery"
                    elapsed < 900L && !captureGallerySaved -> "Saving to Gallery…"
                    else -> "Saved in PalmLink"
                }
                drawStatusPill(canvas, w, h, "Screenshot captured ✓", subtitle)
            }
        }

        private fun drawTransfer(canvas: Canvas, w: Float, h: Float, elapsed: Long) {
            drawGlowBorder(canvas, w, h, 0.62f + pulse(elapsed) * 0.18f, 1.2f + pulse(elapsed) * 1.2f)
            fillPaint.color = Color.BLACK
            fillPaint.alpha = 55
            canvas.drawRect(0f, 0f, w, h, fillPaint)

            val cx = w / 2f
            val cy = h / 2f
            val radiusX = min(w, h) * 0.29f
            val radiusY = min(w, h) * 0.17f
            val t = (elapsed % 1800L) / 1800f
            val angle = (t * Math.PI * 2).toFloat() + if (direction == Direction.RECEIVING) Math.PI.toFloat() else 0f
            val orbitX = cx + cos(angle.toDouble()).toFloat() * radiusX
            val orbitY = cy + sin(angle.toDouble()).toFloat() * radiusY

            borderPaint.color = Color.rgb(30, 145, 255)
            borderPaint.alpha = 120
            borderPaint.strokeWidth = 1.4f * density
            path.reset()
            val rect = RectF(cx - radiusX, cy - radiusY, cx + radiusX, cy + radiusY)
            canvas.drawOval(rect, borderPaint)

            drawLogo(canvas, cx, cy, 38f * density, 0.92f)
            val photoW = 112f * density
            val photoH = 150f * density
            val photoRect = RectF(orbitX - photoW / 2f, orbitY - photoH / 2f, orbitX + photoW / 2f, orbitY + photoH / 2f)
            canvas.save()
            canvas.rotate((sin(angle.toDouble()) * 8.0).toFloat(), orbitX, orbitY)
            drawPhotoCard(canvas, photoRect, bitmap, if (direction == Direction.SENDING) "SENDING" else "RECEIVING")
            canvas.restore()

            val title = if (direction == Direction.SENDING) "Sending screenshot…" else "Receiving screenshot…"
            drawCenteredText(canvas, title, cx, cy + radiusY + 86f * density, boldPaint, 18f * density, Color.WHITE)
            drawCenteredText(canvas, "PalmLink", cx, cy + radiusY + 114f * density, textPaint, 13f * density, Color.rgb(125, 190, 255))
        }

        private fun drawComplete(canvas: Canvas, w: Float, h: Float, elapsed: Long) {
            val entry = (elapsed / 420f).coerceIn(0f, 1f)
            val alpha = (if (entry < 0.35f) entry / 0.35f else 1f).coerceIn(0f, 1f)
            fillPaint.color = Color.BLACK
            fillPaint.alpha = (48 * alpha).toInt()
            canvas.drawRect(0f, 0f, w, h, fillPaint)

            val panelW = min(w * 0.88f, 520f * density)
            val panelH = min(h * 0.72f, 760f * density)
            val left = (w - panelW) / 2f
            val top = (h - panelH) / 2f + (1f - alpha) * 50f * density
            val panel = RectF(left, top, left + panelW, top + panelH)

            fillPaint.color = Color.rgb(7, 19, 34)
            fillPaint.alpha = (238 * alpha).toInt()
            canvas.drawRoundRect(panel, 28f * density, 28f * density, fillPaint)
            drawGlowRoundRect(canvas, panel, alpha)
            drawLogo(canvas, panel.centerX(), panel.top + 44f * density, 28f * density, alpha)
            drawCenteredText(canvas, "PalmLink", panel.centerX(), panel.top + 92f * density, boldPaint, 17f * density, Color.WHITE, alpha)

            val imageRect = RectF(panel.left + 22f * density, panel.top + 116f * density, panel.right - 22f * density, panel.bottom - 105f * density)
            drawPhotoInside(canvas, imageRect, bitmap, alpha)
            status?.split("\n")?.forEachIndexed { index, line ->
                drawCenteredText(
                    canvas,
                    line,
                    panel.centerX(),
                    panel.bottom - (64f - index * 24f) * density,
                    if (index == 0) boldPaint else textPaint,
                    if (index == 0) 17f * density else 13f * density,
                    if (line.contains("Gallery", true)) Color.rgb(120, 190, 255) else Color.WHITE,
                    alpha,
                )
            }
        }

        private fun drawFailure(canvas: Canvas, w: Float, h: Float, elapsed: Long) {
            val alpha = (elapsed / 280f).coerceIn(0f, 1f)
            val panel = RectF(w * 0.09f, h * 0.42f, w * 0.91f, h * 0.58f)
            fillPaint.color = Color.rgb(18, 24, 32)
            fillPaint.alpha = (235 * alpha).toInt()
            canvas.drawRoundRect(panel, 22f * density, 22f * density, fillPaint)
            borderPaint.color = Color.rgb(120, 190, 255)
            borderPaint.alpha = (220 * alpha).toInt()
            borderPaint.strokeWidth = 2f * density
            canvas.drawRoundRect(panel, 22f * density, 22f * density, borderPaint)
            drawCenteredText(canvas, "PalmLink", panel.centerX(), panel.top + 42f * density, boldPaint, 16f * density, Color.WHITE, alpha)
            drawCenteredText(canvas, status ?: "Transfer failed", panel.centerX(), panel.centerY() + 18f * density, textPaint, 14f * density, Color.WHITE, alpha)
        }

        private fun drawGlowBorder(canvas: Canvas, w: Float, h: Float, alpha: Float, widthDp: Float) {
            val inset = 7f * density
            val rect = RectF(inset, inset, w - inset, h - inset)
            val radius = 24f * density
            val widths = floatArrayOf(16f, 10f, 6f, 2.4f)
            val alphas = floatArrayOf(0.035f, 0.065f, 0.11f, 0.92f)
            widths.forEachIndexed { i, width ->
                borderPaint.color = Color.rgb(18, 135, 255)
                borderPaint.alpha = (alpha * alphas[i] * 255f).toInt().coerceIn(0, 255)
                borderPaint.strokeWidth = width * density
                canvas.drawRoundRect(rect, radius, radius, borderPaint)
            }
            borderPaint.alpha = (alpha * 220f).toInt().coerceIn(0, 255)
            borderPaint.strokeWidth = widthDp * density
            canvas.drawRoundRect(rect, radius, radius, borderPaint)
        }

        private fun drawGlowRoundRect(canvas: Canvas, rect: RectF, alpha: Float) {
            val layers = listOf(12f to 0.05f, 7f to 0.09f, 3.5f to 0.16f, 2f to 0.9f)
            layers.forEach { (w, a) ->
                borderPaint.color = Color.rgb(22, 143, 255)
                borderPaint.alpha = (alpha * a * 255f).toInt().coerceIn(0, 255)
                borderPaint.strokeWidth = w * density
                canvas.drawRoundRect(rect, 28f * density, 28f * density, borderPaint)
            }
        }

        private fun drawStatusPill(canvas: Canvas, w: Float, h: Float, title: String, subtitle: String) {
            val panelW = min(w * 0.82f, 500f * density)
            val panelH = 88f * density
            val rect = RectF((w - panelW) / 2f, h - 140f * density, (w + panelW) / 2f, h - 52f * density)
            fillPaint.color = Color.rgb(5, 17, 30)
            fillPaint.alpha = 235
            canvas.drawRoundRect(rect, 20f * density, 20f * density, fillPaint)
            borderPaint.color = Color.rgb(24, 143, 255)
            borderPaint.alpha = 170
            borderPaint.strokeWidth = 1.8f * density
            canvas.drawRoundRect(rect, 20f * density, 20f * density, borderPaint)
            drawCenteredText(canvas, title, rect.centerX(), rect.top + 33f * density, boldPaint, 16f * density, Color.WHITE)
            drawCenteredText(canvas, subtitle, rect.centerX(), rect.top + 60f * density, textPaint, 12.5f * density, Color.rgb(130, 195, 255))
        }

        private fun drawPhotoCard(canvas: Canvas, rect: RectF, source: Bitmap?, label: String) {
            fillPaint.color = Color.rgb(12, 28, 44)
            fillPaint.alpha = 245
            canvas.drawRoundRect(rect, 18f * density, 18f * density, fillPaint)
            borderPaint.color = Color.rgb(22, 143, 255)
            borderPaint.alpha = 235
            borderPaint.strokeWidth = 2f * density
            canvas.drawRoundRect(rect, 18f * density, 18f * density, borderPaint)
            val image = RectF(rect.left + 8f * density, rect.top + 8f * density, rect.right - 8f * density, rect.bottom - 30f * density)
            drawPhotoInside(canvas, image, source, 1f)
            drawCenteredText(canvas, label, rect.centerX(), rect.bottom - 11f * density, boldPaint, 10f * density, Color.rgb(145, 205, 255))
        }

        private fun drawPhotoInside(canvas: Canvas, dest: RectF, source: Bitmap?, alpha: Float) {
            if (source == null || source.isRecycled) {
                drawLogo(canvas, dest.centerX(), dest.centerY(), min(dest.width(), dest.height()) * 0.18f, alpha)
                return
            }
            val srcRatio = source.width.toFloat() / source.height.toFloat()
            val dstRatio = dest.width() / dest.height()
            val drawRect = if (srcRatio > dstRatio) {
                val h = dest.width() / srcRatio
                RectF(dest.left, dest.centerY() - h / 2f, dest.right, dest.centerY() + h / 2f)
            } else {
                val w = dest.height() * srcRatio
                RectF(dest.centerX() - w / 2f, dest.top, dest.centerX() + w / 2f, dest.bottom)
            }
            fillPaint.color = Color.BLACK
            fillPaint.alpha = (210 * alpha).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(dest, 12f * density, 12f * density, fillPaint)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.alpha = (255 * alpha).toInt().coerceIn(0, 255); isFilterBitmap = true }
            canvas.drawBitmap(source, null, drawRect, paint)
        }

        private fun drawLogo(canvas: Canvas, cx: Float, cy: Float, radius: Float, alpha: Float) {
            if (iconBitmap == null || iconBitmap.isRecycled) return
            val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.alpha = (255 * alpha).toInt().coerceIn(0, 255); isFilterBitmap = true }
            canvas.drawBitmap(iconBitmap, null, rect, paint)
        }

        private fun drawCenteredText(canvas: Canvas, text: String, x: Float, baselineY: Float, paintSource: Paint, size: Float, color: Int, alpha: Float = 1f) {
            val paint = Paint(paintSource).apply {
                textSize = size
                this.color = color
                this.alpha = (255 * alpha).toInt().coerceIn(0, 255)
                textAlign = Paint.Align.CENTER
            }
            val fm = paint.fontMetrics
            canvas.drawText(text, x, baselineY - (fm.ascent + fm.descent) / 2f, paint)
        }

        private fun pulse(elapsed: Long): Float = (0.5f + 0.5f * sin((elapsed / 310f).toDouble())).toFloat().coerceIn(0f, 1f)

        private fun mainHandlerPost(block: () -> Unit) {
            Handler(Looper.getMainLooper()).post(block)
        }
    }
}
