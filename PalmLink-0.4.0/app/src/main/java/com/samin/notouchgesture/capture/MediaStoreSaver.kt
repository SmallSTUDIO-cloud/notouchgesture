package com.samin.notouchgesture.capture

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.io.File

object MediaStoreSaver {
    private const val FOLDER = "PalmLink"
    private const val MIME = "image/png"
    private const val PREFS = "palmlink_gallery"

    /** Saves a PNG into the user's Pictures/PalmLink library without creating duplicates. */
    fun saveToPictures(context: Context, source: File): Boolean = runCatching {
        require(source.isFile && source.length() > 0L)
        if (Build.VERSION.SDK_INT >= 29) saveModern(context, source) else saveLegacy(context, source)
    }.getOrDefault(false)

    private fun alreadySaved(context: Context, source: File): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(keyFor(source), null) ?: return false
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return false
        val exists = runCatching { context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media._ID), null, null, null)?.use { it.moveToFirst() } == true }.getOrDefault(false)
        if (!exists) prefs.edit().remove(keyFor(source)).apply()
        return exists
    }

    private fun rememberSaved(context: Context, source: File, uri: Uri) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(keyFor(source), uri.toString())
            .apply()
    }

    private fun keyFor(source: File): String = source.absolutePath + "|" + source.length()

    private fun saveModern(context: Context, source: File): Boolean {
        if (alreadySaved(context, source)) return true
        val resolver = context.contentResolver
        val relativePath = Environment.DIRECTORY_PICTURES + "/" + FOLDER
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, source.name)
            put(MediaStore.Images.Media.MIME_TYPE, MIME)
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        val copied = runCatching {
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
                true
            } ?: false
        }.getOrDefault(false)
        if (!copied) {
            resolver.delete(uri, null, null)
            return false
        }
        val published = runCatching {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) >= 0
        }.getOrDefault(false)
        if (!published) {
            resolver.delete(uri, null, null)
            return false
        }
        rememberSaved(context, source, uri)
        return true
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(context: Context, source: File): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val remembered = prefs.getString(keyFor(source), null)
        if (!remembered.isNullOrBlank() && File(remembered).isFile) return true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), FOLDER)
        if (!dir.exists() && !dir.mkdirs()) return false
        val destination = File(dir, source.name)
        if (destination.isFile && destination.length() == source.length()) {
            prefs.edit().putString(keyFor(source), destination.absolutePath).apply()
            return true
        }
        return runCatching {
            source.inputStream().use { input -> destination.outputStream().use { output -> input.copyTo(output) } }
            if (!destination.isFile) false else {
                prefs.edit().putString(keyFor(source), destination.absolutePath).apply()
                true
            }
        }.getOrDefault(false)
    }
}
