package com.samin.notouchgesture.capture

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

object MediaStoreSaver {
    fun saveToPictures(context: Context, source: File): Boolean = runCatching {
        require(source.exists())
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, source.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PalmLink")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        val copied = runCatching {
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { it.copyTo(output) }
                true
            } ?: false
        }.getOrDefault(false)
        if (!copied) {
            resolver.delete(uri, null, null)
            return false
        }
        if (Build.VERSION.SDK_INT >= 29) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        }
        true
    }.getOrDefault(false)
}
