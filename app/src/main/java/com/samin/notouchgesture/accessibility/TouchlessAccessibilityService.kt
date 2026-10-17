package com.samin.notouchgesture.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Optional, user-enabled bridge for touchless actions that Android deliberately keeps behind
 * AccessibilityService: Back/Home/Recents/notifications, scrolling and a screen-coordinate tap.
 * It contains no hidden API and performs no silent permission escalation.
 */
class TouchlessAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun global(action: Int): Boolean = runCatching { performGlobalAction(action) }.getOrDefault(false)

    fun insertText(text: String): Boolean {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return false
        if (!node.isEditable) return false

        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text,
            )
        }

        return runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }.getOrDefault(false)
    }

    fun scroll(up: Boolean): Boolean {
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels * 0.5f
        val startY = if (up) metrics.heightPixels * 0.68f else metrics.heightPixels * 0.32f
        val endY = if (up) metrics.heightPixels * 0.32f else metrics.heightPixels * 0.68f
        val path = Path().apply {
            moveTo(x, startY)
            lineTo(x, endY)
        }
        return dispatch(path, 320L)
    }

    fun swipe(left: Boolean): Boolean {
        val metrics = resources.displayMetrics
        val y = metrics.heightPixels * 0.5f
        val startX = if (left) metrics.widthPixels * 0.78f else metrics.widthPixels * 0.22f
        val endX = if (left) metrics.widthPixels * 0.22f else metrics.widthPixels * 0.78f
        val path = Path().apply {
            moveTo(startX, y)
            lineTo(endX, y)
        }
        return dispatch(path, 280L)
    }

    fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatch(path, 80L)
    }

    fun pinch(centerX: Float, centerY: Float, zoomIn: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        val metrics = resources.displayMetrics
        val cx = centerX.coerceIn(0f, 1f) * metrics.widthPixels
        val cy = centerY.coerceIn(0f, 1f) * metrics.heightPixels
        val delta = minOf(metrics.widthPixels, metrics.heightPixels) * 0.12f
        val a = if (zoomIn) delta * 0.45f else delta * 0.95f
        val b = if (zoomIn) delta * 0.95f else delta * 0.45f
        val first = Path().apply { moveTo(cx - a, cy); lineTo(cx - b, cy) }
        val second = Path().apply { moveTo(cx + a, cy); lineTo(cx + b, cy) }
        val strokes = listOf(
            GestureDescription.StrokeDescription(first, 0L, 220L),
            GestureDescription.StrokeDescription(second, 0L, 220L),
        )
        return runCatching {
            dispatchGesture(GestureDescription.Builder().apply { strokes.forEach(::addStroke) }.build(), null, null)
        }.getOrDefault(false)
    }

    private fun dispatch(path: Path, duration: Long): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        return runCatching {
            dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0L, duration.coerceAtLeast(1L)))
                    .build(),
                null,
                null,
            )
        }.getOrDefault(false)
    }

    companion object {
        @Volatile private var instance: TouchlessAccessibilityService? = null
        fun get(): TouchlessAccessibilityService? = instance
    }
}
