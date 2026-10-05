package com.samin.notouchgesture.ui

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

enum class ThemeMode(val label: String) {
    AUTO("Auto"),
    LIGHT("Light"),
    DARK("Dark"),
}

private val LightColors = lightColorScheme()
private val DarkColors = darkColorScheme()

@Composable
fun NoTouchTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = when (themeMode) {
        ThemeMode.AUTO -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 && !dark -> dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}

class ThemeStore(context: Context) {
    private val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)

    fun get(): ThemeMode = runCatching {
        ThemeMode.valueOf(prefs.getString(KEY, ThemeMode.AUTO.name) ?: ThemeMode.AUTO.name)
    }.getOrDefault(ThemeMode.AUTO)

    fun set(mode: ThemeMode) {
        prefs.edit().putString(KEY, mode.name).apply()
    }

    companion object {
        private const val KEY = "theme_mode"
    }
}
