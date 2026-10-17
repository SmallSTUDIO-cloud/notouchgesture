package com.samin.notouchgesture.capture

import android.content.Context

/**
 * Persistent feedback/control preferences. The original PalmLink 0.4.0 shutter-sound key is
 * intentionally preserved so upgrading does not reset the user's existing sound choice.
 */
class FeedbackSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var shutterSoundEnabled: Boolean
        get() = prefs.getBoolean(KEY_SHUTTER_SOUND, true)
        set(value) { prefs.edit().putBoolean(KEY_SHUTTER_SOUND, value).apply() }

    var visualFeedbackEnabled: Boolean
        get() = prefs.getBoolean(KEY_VISUAL_FEEDBACK, true)
        set(value) { prefs.edit().putBoolean(KEY_VISUAL_FEEDBACK, value).apply() }

    var extendedGesturesEnabled: Boolean
        get() = prefs.getBoolean(KEY_EXTENDED_GESTURES, false)
        set(value) { prefs.edit().putBoolean(KEY_EXTENDED_GESTURES, value).apply() }

    var flashlightGestureEnabled: Boolean
        get() = prefs.getBoolean(KEY_FLASHLIGHT_GESTURE, false)
        set(value) { prefs.edit().putBoolean(KEY_FLASHLIGHT_GESTURE, value).apply() }

    var volumeGestureEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOLUME_GESTURE, false)
        set(value) { prefs.edit().putBoolean(KEY_VOLUME_GESTURE, value).apply() }

    var brightnessGestureEnabled: Boolean
        get() = prefs.getBoolean(KEY_BRIGHTNESS_GESTURE, false)
        set(value) { prefs.edit().putBoolean(KEY_BRIGHTNESS_GESTURE, value).apply() }

    var mediaGestureEnabled: Boolean
        get() = prefs.getBoolean(KEY_MEDIA_GESTURE, false)
        set(value) { prefs.edit().putBoolean(KEY_MEDIA_GESTURE, value).apply() }

    var navigationGestureEnabled: Boolean
        get() = prefs.getBoolean(KEY_NAVIGATION_GESTURE, false)
        set(value) { prefs.edit().putBoolean(KEY_NAVIGATION_GESTURE, value).apply() }

    var scrollGestureEnabled: Boolean
        get() = prefs.getBoolean(KEY_SCROLL_GESTURE, false)
        set(value) { prefs.edit().putBoolean(KEY_SCROLL_GESTURE, value).apply() }

    var cursorGestureEnabled: Boolean
        get() = prefs.getBoolean(KEY_CURSOR_GESTURE, false)
        set(value) { prefs.edit().putBoolean(KEY_CURSOR_GESTURE, value).apply() }

    var airMicEnabled: Boolean
        get() = prefs.getBoolean(KEY_AIR_MIC, false)
        set(value) { prefs.edit().putBoolean(KEY_AIR_MIC, value).apply() }

    var gestureSensitivity: Float
        get() = prefs.getFloat(KEY_GESTURE_SENSITIVITY, 1.0f)
        set(value) { prefs.edit().putFloat(KEY_GESTURE_SENSITIVITY, value.coerceIn(0.75f, 1.5f)).apply() }

    companion object {
        // Preserve the 0.4.0 preference namespace and key.
        private const val PREFS = "palmlink_feedback"
        private const val KEY_SHUTTER_SOUND = "shutter_sound_enabled"
        private const val KEY_VISUAL_FEEDBACK = "visual_feedback_enabled"
        private const val KEY_EXTENDED_GESTURES = "extended_gestures_enabled"
        private const val KEY_FLASHLIGHT_GESTURE = "flashlight_gesture_enabled"
        private const val KEY_VOLUME_GESTURE = "volume_gesture_enabled"
        private const val KEY_BRIGHTNESS_GESTURE = "brightness_gesture_enabled"
        private const val KEY_MEDIA_GESTURE = "media_gesture_enabled"
        private const val KEY_NAVIGATION_GESTURE = "navigation_gesture_enabled"
        private const val KEY_SCROLL_GESTURE = "scroll_gesture_enabled"
        private const val KEY_CURSOR_GESTURE = "cursor_gesture_enabled"
        private const val KEY_AIR_MIC = "air_mic_enabled"
        private const val KEY_GESTURE_SENSITIVITY = "gesture_sensitivity"
    }
}
