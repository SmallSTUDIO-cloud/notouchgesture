package com.samin.notouchgesture.capture

import android.content.Context
import java.io.File

class CaptureStore(context: Context) {
    private val prefs = context.getSharedPreferences("capture_store", Context.MODE_PRIVATE)
    fun latestFile(): File? = prefs.getString(KEY_LATEST, null)?.let(::File)?.takeIf { it.exists() }

    fun saveLatest(file: File) {
        prefs.edit().putString(KEY_LATEST, file.absolutePath).apply()
    }

    fun clearLatest() {
        prefs.edit().remove(KEY_LATEST).apply()
    }

    companion object {
        private const val KEY_LATEST = "latest_capture"
    }
}
