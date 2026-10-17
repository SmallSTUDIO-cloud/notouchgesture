package com.samin.notouchgesture.control

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.provider.Settings
import android.view.KeyEvent
import com.samin.notouchgesture.accessibility.TouchlessAccessibilityService
import kotlin.math.abs

/**
 * Converts high-level touchless actions into documented Android APIs. Actions that affect other
 * apps use the optional user-enabled AccessibilityService; nothing attempts to bypass Android's
 * permission model.
 */
class TouchlessActionDispatcher(private val context: Context) {
    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(AudioManager::class.java)
    private val camera = appContext.getSystemService(CameraManager::class.java)

    fun toggleFlashlight(): Boolean {
        val manager = camera ?: return false
        val id = runCatching {
            manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull() ?: return false
        val current = flashlightState
        val next = !current
        return runCatching {
            manager.setTorchMode(id, next)
            flashlightState = next
            true
        }.getOrDefault(false)
    }

    fun volumeByDelta(deltaY: Float): Boolean {
        val manager = audio ?: return false
        val direction = if (deltaY < 0f) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        return runCatching {
            val steps = (abs(deltaY) * 5f).toInt().coerceIn(1, 2)
            repeat(steps) {
                manager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
            }
            true
        }.getOrDefault(false)
    }

    fun mediaNext(): Boolean = dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
    fun mediaPrevious(): Boolean = dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    fun mediaPlayPause(): Boolean = dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)

    private fun dispatchMediaKey(keyCode: Int): Boolean {
        val manager = audio ?: return false
        return runCatching {
            manager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            manager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            true
        }.getOrDefault(false)
    }

    fun setBrightness(delta: Float): Boolean {
        if (!Settings.System.canWrite(appContext)) return false
        val current = runCatching { Settings.System.getInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(128)
        val step = (abs(delta) * 120f).toInt().coerceIn(12, 35)
        val next = (current + if (delta > 0f) step else -step).coerceIn(10, 255)
        return runCatching { Settings.System.putInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS, next) }.getOrDefault(false)
    }

    fun scroll(up: Boolean): Boolean = TouchlessAccessibilityService.get()?.scroll(up) == true
    fun swipe(left: Boolean): Boolean = TouchlessAccessibilityService.get()?.swipe(left) == true
    fun back(): Boolean = TouchlessAccessibilityService.get()?.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK) == true
    fun home(): Boolean = TouchlessAccessibilityService.get()?.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME) == true
    fun recents(): Boolean = TouchlessAccessibilityService.get()?.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS) == true
    fun notifications(): Boolean = TouchlessAccessibilityService.get()?.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS) == true
    fun tapAt(x: Float, y: Float): Boolean = TouchlessAccessibilityService.get()?.tap(x, y) == true
    fun pinchAt(x: Float, y: Float, zoomIn: Boolean): Boolean = TouchlessAccessibilityService.get()?.pinch(x, y, zoomIn) == true

    fun accessibilityEnabled(): Boolean = TouchlessAccessibilityService.get() != null
    fun canWriteBrightness(): Boolean = Settings.System.canWrite(appContext)

    fun openAccessibilitySettings() {
        runCatching { appContext.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun openWriteSettings() {
        runCatching { appContext.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
            data = android.net.Uri.parse("package:${appContext.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }) }
    }

    fun stopFlashlight() {
        val manager = camera ?: return
        if (!flashlightState) return
        runCatching {
            val id = manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            if (id != null) manager.setTorchMode(id, false)
        }
        flashlightState = false
    }

    companion object {
        @Volatile private var flashlightState = false
    }
}
