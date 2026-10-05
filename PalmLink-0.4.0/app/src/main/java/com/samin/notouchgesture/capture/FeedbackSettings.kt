package com.samin.notouchgesture.capture

import android.content.Context

class FeedbackSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var shutterSoundEnabled: Boolean
        get() = prefs.getBoolean(KEY_SHUTTER_SOUND, true)
        set(value) { prefs.edit().putBoolean(KEY_SHUTTER_SOUND, value).apply() }

    companion object {
        private const val PREFS = "palmlink_feedback"
        private const val KEY_SHUTTER_SOUND = "shutter_sound_enabled"
    }
}
