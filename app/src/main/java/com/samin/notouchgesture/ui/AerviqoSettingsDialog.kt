package com.samin.notouchgesture.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.samin.notouchgesture.capture.FeedbackSettings
import com.samin.notouchgesture.control.TouchlessActionDispatcher

@Composable
fun AerviqoSettingsDialog(
    onDismiss: () -> Unit,
    onSettingsChanged: () -> Unit,
    onRequestMicrophone: () -> Unit,
) {
    val context = LocalContext.current
    val settings = remember { FeedbackSettings(context) }
    var shutterSound by remember { mutableStateOf(settings.shutterSoundEnabled) }
    var visualFeedback by remember { mutableStateOf(settings.visualFeedbackEnabled) }
    var extended by remember { mutableStateOf(settings.extendedGesturesEnabled) }
    var flashlight by remember { mutableStateOf(settings.flashlightGestureEnabled) }
    var volume by remember { mutableStateOf(settings.volumeGestureEnabled) }
    var brightness by remember { mutableStateOf(settings.brightnessGestureEnabled) }
    var media by remember { mutableStateOf(settings.mediaGestureEnabled) }
    var navigation by remember { mutableStateOf(settings.navigationGestureEnabled) }
    var scroll by remember { mutableStateOf(settings.scrollGestureEnabled) }
    var cursor by remember { mutableStateOf(settings.cursorGestureEnabled) }
    var airMic by remember { mutableStateOf(settings.airMicEnabled) }
    var sensitivity by remember { mutableStateOf(settings.gestureSensitivity) }
    var showManual by remember { mutableStateOf(false) }

    if (showManual) {
        GestureManualDialog(onDismiss = { showManual = false })
        return
    }

    fun changed() = onSettingsChanged()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Aerviqo Settings") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SettingSwitch("Shutter sound", "Play the camera-style shutter sound after a successful capture.", shutterSound) {
                    shutterSound = it; settings.shutterSoundEnabled = it; changed()
                }
                SettingSwitch("Visual feedback", "Show the existing capture and transfer overlays when Android allows them.", visualFeedback) {
                    visualFeedback = it; settings.visualFeedbackEnabled = it; changed()
                }
                SettingSwitch("Extended air gestures", "Enable the additional touchless controls below. Screenshot gestures remain unchanged.", extended) {
                    extended = it; settings.extendedGesturesEnabled = it; changed()
                }
                SettingSwitch("Shake → flashlight", "A deliberate phone shake toggles the rear flashlight.", flashlight) {
                    flashlight = it; settings.flashlightGestureEnabled = it; changed()
                }
                SettingSwitch("Pinch + vertical → volume", "Pinch, then move your hand up or down to adjust media volume.", volume) {
                    volume = it; settings.volumeGestureEnabled = it; changed()
                }
                SettingSwitch("Pinch + horizontal → brightness", "Pinch, then move your hand horizontally to adjust screen brightness. Android may require write-settings access.", brightness) {
                    brightness = it; settings.brightnessGestureEnabled = it; changed()
                }
                SettingSwitch("Swipe → media", "Open-palm horizontal swipe sends previous/next media-key commands.", media) {
                    media = it; settings.mediaGestureEnabled = it; changed()
                }
                SettingSwitch("Scroll", "Open-palm vertical movement sends a standard accessibility scroll gesture.", scroll) {
                    scroll = it; settings.scrollGestureEnabled = it; changed()
                }
                SettingSwitch("Pinch → select/click", "A pinch releases a tap at the hand cursor position.", cursor) {
                    cursor = it; settings.cursorGestureEnabled = it; changed()
                }
                SettingSwitch("Back/Home/notification controls", "Enables the optional user-approved AccessibilityService bridge for system navigation actions.", navigation) {
                    navigation = it; settings.navigationGestureEnabled = it; changed()
                }
                SettingSwitch("Air Mic (experimental)", "Mouth-open detection can launch Android voice input while Aerviqo is visible. It never silently takes control of another app's microphone.", airMic) {
                    airMic = it; settings.airMicEnabled = it; changed()
                }
                if (airMic) {
                    Button(onClick = onRequestMicrophone, modifier = Modifier.fillMaxWidth()) {
                        Text("Allow microphone")
                    }
                }

                Text("Gesture sensitivity", fontWeight = FontWeight.SemiBold)
                Text("Current ${(sensitivity * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.material3.Slider(
                    value = sensitivity,
                    onValueChange = { sensitivity = it; settings.gestureSensitivity = it },
                    valueRange = 0.75f..1.5f,
                    steps = 5,
                )

                TextButton(onClick = { showManual = true }) { Text("Open gesture manual") }
                Button(onClick = { TouchlessActionDispatcher(context).openAccessibilitySettings() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Open Accessibility settings")
                }
                if (brightness) {
                    Button(onClick = { TouchlessActionDispatcher(context).openWriteSettings() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Allow brightness control")
                    }
                }
                if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(context)) {
                    Button(
                        onClick = {
                            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                                if (Build.VERSION.SDK_INT < 30) data = Uri.parse("package:${context.packageName}")
                            }
                            runCatching { context.startActivity(intent) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Allow visual effects / cursor") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun SettingSwitch(title: String, body: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
