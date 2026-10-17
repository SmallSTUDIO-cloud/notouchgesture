package com.samin.notouchgesture.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.samin.notouchgesture.R

@Composable
fun GestureManualDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Aerviqo Gesture Manual") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Core screenshot controls", fontWeight = FontWeight.Bold)
                GestureExample(R.drawable.gesture_open_palm, "Open palm → closed fist", "Capture the current screen. In background mode the finished screenshot is sent to the connected device.")
                GestureExample(R.drawable.gesture_closed_fist, "Closed fist → open palm", "Authorize and receive a pending screenshot on the receiving phone.")
                Text("Extended controls", fontWeight = FontWeight.Bold)
                GestureExample(R.drawable.gesture_swipe, "Open-palm movement", "Vertical movement scrolls up/down. Horizontal swipe controls previous/next media; two left swipes can go Back and two right swipes can go Home when navigation controls are enabled.")
                GestureExample(R.drawable.gesture_pinch, "Pinch", "Pinch-to-select/click at the hand cursor. Pinch movement can also control volume or brightness when enabled.")
                Text("Shake phone → flashlight", fontWeight = FontWeight.SemiBold)
                Text("A deliberate shake toggles the flashlight when the feature is enabled. Use Settings to control it.")
                Text("Air Mic", fontWeight = FontWeight.SemiBold)
                Text("The Air Mic setting is experimental and follows Android microphone and background-activity restrictions. It does not secretly activate another app's microphone.")
                Text("Important", fontWeight = FontWeight.Bold)
                Text("Keep one hand clearly visible for hand gestures. Perform the two-step screenshot gestures deliberately. New controls are optional and do not replace the core capture/receive gestures.")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun GestureExample(imageRes: Int, title: String, description: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(imageRes),
            contentDescription = title,
            modifier = Modifier.size(76.dp).background(MaterialTheme.colorScheme.surfaceContainer),
        )
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
