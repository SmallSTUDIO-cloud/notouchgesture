package com.samin.notouchgesture.control

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.content.Context

/** Small non-interactive cursor overlay used by the optional pinch-to-select mode. */
class CursorOverlayController(private val context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var view: CursorView? = null
    private var params: WindowManager.LayoutParams? = null

    private var lastUpdateAt = Long.MIN_VALUE

    fun canShow(): Boolean = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context)

    fun show(x: Float, y: Float) {
        if (!canShow()) return
        val wm = windowManager ?: return
        if (view == null) {
            val v = CursorView(context)
            val type = if (Build.VERSION.SDK_INT >= 26) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val p = WindowManager.LayoutParams(
                42,
                42,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.START }
            view = v
            params = p
            runCatching { wm.addView(v, p) }.onFailure {
                view = null
                params = null
                return
            }
        }
        val v = view ?: return
        val p = params ?: return
        val maxX = (context.resources.displayMetrics.widthPixels - p.width).coerceAtLeast(0)
        val maxY = (context.resources.displayMetrics.heightPixels - p.height).coerceAtLeast(0)
        val targetX = (x.coerceIn(0f, 1f) * maxX).toInt()
        val targetY = (y.coerceIn(0f, 1f) * maxY).toInt()
        val now = android.os.SystemClock.uptimeMillis()

        // Dead-zone and rate-limit to avoid WindowManager IPC spam
        if (kotlin.math.abs(targetX - p.x) < 4 && kotlin.math.abs(targetY - p.y) < 4) return
        if (lastUpdateAt != Long.MIN_VALUE && now - lastUpdateAt < 30L) return
        lastUpdateAt = now

        p.x = targetX
        p.y = targetY
        runCatching { wm.updateViewLayout(v, p) }
    }

    fun hide() {
        val v = view ?: return
        runCatching { windowManager?.removeView(v) }
        view = null
        params = null
        lastUpdateAt = Long.MIN_VALUE
    }

    private class CursorView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xCC1976D2.toInt()
        }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = 0xFFFFFFFF.toInt()
        }
        override fun onDraw(canvas: Canvas) {
            val c = width / 2f
            canvas.drawCircle(c, height / 2f, width * 0.27f, paint)
            canvas.drawCircle(c, height / 2f, width * 0.36f, ring)
        }
    }
}
