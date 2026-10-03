package com.samin.notouchgesture

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.samin.notouchgesture.capture.CaptureStore
import com.samin.notouchgesture.capture.MediaStoreSaver
import com.samin.notouchgesture.capture.ScreenCaptureService
import com.samin.notouchgesture.gesture.GestureRecognizerController
import com.samin.notouchgesture.gesture.GestureStateMachine
import com.samin.notouchgesture.nearby.NearbyRuntime
import com.samin.notouchgesture.nearby.NearbyTransferManager
import com.samin.notouchgesture.ui.NoTouchTheme
import com.samin.notouchgesture.ui.SectionCard
import com.samin.notouchgesture.ui.StatusChip
import com.samin.notouchgesture.ui.ThemeMenu
import com.samin.notouchgesture.ui.ThemeMode
import com.samin.notouchgesture.ui.ThemeStore
import com.samin.notouchgesture.ui.TransferProgress
import androidx.camera.view.PreviewView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var captureStore: CaptureStore
    private lateinit var nearby: NearbyTransferManager

    private var latestCaptureState = mutableStateOf<File?>(null)
    private var nearbyState = mutableStateOf(NearbyTransferManager.State())
    private var themeState = mutableStateOf(ThemeMode.AUTO)
    private var backgroundState = mutableStateOf("PalmLink is not running in the background")
    private var backgroundRunningState = mutableStateOf(ScreenCaptureService.running)
    private var captureReadyState = mutableStateOf(ScreenCaptureService.captureReady)
    private var lastHandledReceived: File? = null
    private var backgroundGestureState = mutableStateOf(GestureRecognizerController.GestureUiState())

    private val captureProjectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startCaptureService(result.resultCode, result.data!!)
        } else {
            ScreenCaptureService.finishStarting()
            backgroundRunningState.value = false
            backgroundState.value = "Screen-capture permission was canceled."
        }
    }

    private val permissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // Compose refreshes the visible permission state from the lifecycle callback below.
    }

    private val captureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            intent.getStringExtra(ScreenCaptureService.EXTRA_PATH)?.let { latestCaptureState.value = File(it) }
        }
    }

    private val backgroundStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            intent.getStringExtra(ScreenCaptureService.EXTRA_MESSAGE)?.let { backgroundState.value = it }
            backgroundRunningState.value = intent.getBooleanExtra(ScreenCaptureService.EXTRA_RUNNING, backgroundRunningState.value)
            captureReadyState.value = intent.getBooleanExtra(ScreenCaptureService.EXTRA_CAPTURE_READY, captureReadyState.value)
        }
    }

    private val nearbyStateListener: (NearbyTransferManager.State) -> Unit = { state ->
        runOnUiThread {
            nearbyState.value = state
            state.lastReceivedFile?.let { received ->
                if (received != lastHandledReceived) {
                    lastHandledReceived = received
                    captureStore.saveLatest(received)
                    latestCaptureState.value = received
                }
            }
        }
    }

    private val backgroundGestureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val label = runCatching {
                GestureStateMachine.Label.valueOf(intent.getStringExtra(ScreenCaptureService.EXTRA_LABEL) ?: "NONE")
            }.getOrDefault(GestureStateMachine.Label.NONE)
            backgroundGestureState.value = GestureRecognizerController.GestureUiState(
                label = label,
                confidence = if (intent.hasExtra(ScreenCaptureService.EXTRA_CONFIDENCE)) {
                    intent.getFloatExtra(ScreenCaptureService.EXTRA_CONFIDENCE, 0f)
                } else {
                    null
                },
                handQuality = intent.getFloatExtra(ScreenCaptureService.EXTRA_HAND_QUALITY, 0f),
                message = intent.getStringExtra(ScreenCaptureService.EXTRA_MESSAGE) ?: "Searching for one hand…",
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captureStore = CaptureStore(this)
        nearby = NearbyRuntime.get(this)
        // Activity recreation: start from the service's real state, not stale Activity state.
        lastHandledReceived = nearby.currentState().lastReceivedFile
        backgroundRunningState.value = ScreenCaptureService.running
        captureReadyState.value = ScreenCaptureService.captureReady
        if (ScreenCaptureService.running) backgroundState.value = "PalmLink is running in the background."
        nearby.addStateListener(nearbyStateListener)
        latestCaptureState.value = captureStore.latestFile()
        themeState.value = ThemeStore(this).get()
        ContextCompat.registerReceiver(
            this,
            captureReceiver,
            IntentFilter(ScreenCaptureService.ACTION_CAPTURE_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            this,
            backgroundStateReceiver,
            IntentFilter(ScreenCaptureService.ACTION_SERVICE_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            this,
            backgroundGestureReceiver,
            IntentFilter(ScreenCaptureService.ACTION_GESTURE_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        setContent {
            val themeStore = remember { ThemeStore(this) }
            NoTouchTheme(themeState.value) {
                NoTouchApp(
                    themeMode = themeState.value,
                    onThemeChange = {
                        themeState.value = it
                        themeStore.set(it)
                    },
                    latestCapture = latestCaptureState.value,
                    nearbyState = nearbyState.value,
                    onRequestCamera = ::requestCameraPermissions,
                    onRequestNearby = ::requestNearbyPermissions,
                    onRequestCapture = ::requestScreenCapture,
                    backgroundRunning = backgroundRunningState.value,
                    captureReady = captureReadyState.value,
                    backgroundState = backgroundState.value,
                    backgroundGestureState = backgroundGestureState.value,
                    onStartAdvertising = { sendNearbyCommand(ScreenCaptureService.ACTION_NEARBY_ADVERTISE) },
                    onStartDiscovery = { sendNearbyCommand(ScreenCaptureService.ACTION_NEARBY_DISCOVER) },
                    onStopNearby = { sendNearbyCommand(ScreenCaptureService.ACTION_NEARBY_STOP) },
                    onForgetPairing = { nearby.forgetTrustedPeers() },
                    onConfirmConnection = { nearby.confirmPendingConnection() },
                    onRejectConnection = { nearby.rejectPendingConnection() },
                    onSendOffer = { file -> nearby.sendScreenshot(file) },
                    onSaveGallery = { file -> MediaStoreSaver.saveToPictures(this, file) },
                    onDeleteCapture = { file ->
                        val deleted = file.delete()
                        if (deleted) {
                            captureStore.clearLatest()
                            latestCaptureState.value = null
                        }
                        deleted
                    },
                    onShare = ::shareFile,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Re-sync with the service every time the UI becomes visible.
        backgroundRunningState.value = ScreenCaptureService.running
        captureReadyState.value = ScreenCaptureService.captureReady
    }

    override fun onDestroy() {
        // The Nearby session belongs to ScreenCaptureService. The Activity only observes it, so
        // finishing, recreating or swiping away the UI must NOT touch the connection.
        nearby.removeStateListener(nearbyStateListener)
        runCatching { unregisterReceiver(captureReceiver) }
        runCatching { unregisterReceiver(backgroundStateReceiver) }
        runCatching { unregisterReceiver(backgroundGestureReceiver) }
        super.onDestroy()
    }

    /**
     * Starts/stops Nearby inside the foreground service. Going through the service (never calling
     * the manager directly) guarantees the session lives in a process Android keeps alive.
     */
    private fun sendNearbyCommand(action: String) {
        if (action != ScreenCaptureService.ACTION_NEARBY_STOP && !nearbyPermissionsGranted(this)) {
            requestNearbyPermissions()
            return
        }
        val intent = Intent(this, ScreenCaptureService::class.java).setAction(action)
        runCatching {
            if (action == ScreenCaptureService.ACTION_NEARBY_STOP) startService(intent)
            else ContextCompat.startForegroundService(this, intent)
        }.onFailure { error ->
            backgroundState.value = "Could not start PalmLink Nearby: ${error.message ?: "Android rejected the request"}"
        }
    }

    fun requestScreenCapture() {
        if (ScreenCaptureService.captureReady) {
            val intent = Intent(this, ScreenCaptureService::class.java)
                .setAction(ScreenCaptureService.ACTION_CAPTURE_NOW)
            // The service is already a foreground service here, so a plain startService() is
            // enough. Using startForegroundService() would oblige the service to call
            // startForeground() again and, if it could not, Android would kill the app.
            runCatching { startService(intent) }
                .onFailure { error ->
                    backgroundState.value = "Could not capture the screen: ${error.message ?: "Android rejected the request"}"
                }
            return
        }
        if (!ScreenCaptureService.tryBeginStarting()) {
            backgroundState.value = "PalmLink background mode is already starting."
            return
        }

        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            ScreenCaptureService.finishStarting()
            backgroundState.value = "Screen capture is unavailable on this device."
            return
        }
        runCatching { captureProjectionLauncher.launch(manager.createScreenCaptureIntent()) }
            .onFailure { error ->
                ScreenCaptureService.finishStarting()
                backgroundState.value = "Could not request screen capture: ${error.message ?: "unknown error"}"
            }
    }

    private fun startCaptureService(resultCode: Int, data: Intent) {
        if (ScreenCaptureService.captureReady) {
            ScreenCaptureService.finishStarting()
            backgroundRunningState.value = ScreenCaptureService.running
            backgroundState.value = "PalmLink is already running in the background."
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ScreenCaptureService.finishStarting()
            requestCameraPermissions()
            return
        }
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
        }
        backgroundState.value = "Starting PalmLink background mode…"
        if (ScreenCaptureService.running) {
            // Only the projection ended; the service's camera engine is still up and is re-used.
            // Stopping the "active" controller here would stop that engine.
            launchCaptureService(intent)
        } else {
            backgroundRunningState.value = false
            // Release the foreground CameraX/MediaPipe pipeline first. The controller invokes the
            // callback only after the current inference has finished and the native recognizer has
            // been closed, preventing an Activity→service camera race on OEM devices.
            GestureRecognizerController.stopActive { launchCaptureService(intent) }
        }
    }

    private fun launchCaptureService(intent: Intent) {
        window.decorView.postDelayed({
            if (isFinishing || (Build.VERSION.SDK_INT >= 17 && isDestroyed)) {
                ScreenCaptureService.finishStarting()
                return@postDelayed
            }
            runCatching {
                ContextCompat.startForegroundService(this@MainActivity, intent)
            }.onFailure { error ->
                ScreenCaptureService.finishStarting()
                backgroundRunningState.value = ScreenCaptureService.running
                backgroundState.value = "PalmLink could not start: ${error.message ?: "Android rejected the service start"}"
            }
        }, 150L)
    }

    private fun requestCameraPermissions() {
        val permissions = buildList {
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionsLauncher.launch(permissions.toTypedArray())
    }

    private fun requestNearbyPermissions() {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
            } else if (Build.VERSION.SDK_INT >= 29) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            } else {
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
                add(Manifest.permission.POST_NOTIFICATIONS) // shows the PalmLink status notification
            }
        }
        permissionsLauncher.launch(permissions.toTypedArray())
    }

    private fun shareFile(file: File) {
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(this, getString(R.string.file_provider_authority), file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share screenshot"))
    }
}

private enum class AppTab(val label: String) {
    HOME("Home"),
    GESTURE("Gesture"),
    NEARBY("Nearby"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoTouchApp(
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    latestCapture: File?,
    nearbyState: NearbyTransferManager.State,
    onRequestCamera: () -> Unit,
    onRequestNearby: () -> Unit,
    onRequestCapture: () -> Unit,
    backgroundRunning: Boolean,
    captureReady: Boolean,
    backgroundState: String,
    backgroundGestureState: GestureRecognizerController.GestureUiState,
    onStartAdvertising: () -> Unit,
    onStartDiscovery: () -> Unit,
    onStopNearby: () -> Unit,
    onForgetPairing: () -> Unit,
    onConfirmConnection: () -> Unit,
    onRejectConnection: () -> Unit,
    onSendOffer: (File) -> Unit,
    onSaveGallery: (File) -> Boolean,
    onDeleteCapture: (File) -> Boolean,
    onShare: (File) -> Unit,
) {
    var tab by remember { mutableStateOf(AppTab.HOME) }
    var showInfo by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("PalmLink", fontWeight = FontWeight.Bold)
                        Text("Touchless control, built responsibly", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    IconButton(onClick = { showInfo = true }) { Icon(Icons.Filled.Info, contentDescription = "Information") }
                    ThemeMenu(themeMode, onThemeChange)
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            NavigationBar(modifier = Modifier.navigationBarsPadding()) {
                AppTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = {
                            Icon(
                                when (item) {
                                    AppTab.HOME -> Icons.Filled.Home
                                    AppTab.GESTURE -> Icons.Filled.CameraAlt
                                    AppTab.NEARBY -> Icons.Filled.Devices
                                },
                                contentDescription = item.label,
                            )
                        },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { padding ->
        when (tab) {
            AppTab.HOME -> HomeScreen(
                modifier = Modifier.padding(padding),
                latestCapture = latestCapture,
                onGesture = { tab = AppTab.GESTURE },
                onNearby = { tab = AppTab.NEARBY },
                onCapture = onRequestCapture,
                onSaveGallery = onSaveGallery,
                onDeleteCapture = onDeleteCapture,
                onShare = onShare,
            )
            AppTab.GESTURE -> GestureScreen(
                modifier = Modifier.padding(padding),
                onRequestCamera = onRequestCamera,
                onRequestCapture = onRequestCapture,
                backgroundRunning = backgroundRunning,
                captureReady = captureReady,
                backgroundState = backgroundState,
                backgroundGestureState = backgroundGestureState,
            )
            AppTab.NEARBY -> NearbyScreen(
                modifier = Modifier.padding(padding),
                state = nearbyState,
                latestCapture = latestCapture,
                onRequestNearby = onRequestNearby,
                onRequestCamera = onRequestCamera,
                onStartAdvertising = onStartAdvertising,
                onStartDiscovery = onStartDiscovery,
                onStopNearby = onStopNearby,
                onForgetPairing = onForgetPairing,
                onConfirmConnection = onConfirmConnection,
                onRejectConnection = onRejectConnection,
                onSendOffer = onSendOffer,
                backgroundRunning = backgroundRunning,
            )
        }
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text("How this prototype works") },
            text = {
                Text(
                    "Gesture detection runs locally with the front camera. PalmLink uses a user-visible Android foreground service for background gestures and MediaProjection for screen capture. The system requires user consent when background capture is enabled. Nearby transfer stays device-to-device and uses the platform connection authentication flow."
                )
            },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("Done") } },
        )
    }
}

@Composable
private fun HomeScreen(
    modifier: Modifier,
    latestCapture: File?,
    onGesture: () -> Unit,
    onNearby: () -> Unit,
    onCapture: () -> Unit,
    onSaveGallery: (File) -> Boolean,
    onDeleteCapture: (File) -> Boolean,
    onShare: (File) -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Spacer(Modifier.height(4.dp)) }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = RoundedCornerShape(28.dp),
            ) {
                Column(Modifier.padding(24.dp)) {
                    StatusChip("PalmLink • Android")
                    Spacer(Modifier.height(14.dp))
                    Text("Control your screen without touching it.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Open palm → fist captures your screen and sends it to your paired phone. Fist → open palm receives an incoming screenshot.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = onGesture) { Icon(Icons.Filled.CameraAlt, null); Spacer(Modifier.width(8.dp)); Text("Test gestures") }
                        TextButton(onClick = onNearby) { Icon(Icons.Filled.Send, null); Spacer(Modifier.width(6.dp)); Text("Nearby") }
                    }
                }
            }
        }
        item {
            SectionCard(
                title = "Capture",
                body = "Enable PalmLink once, approve Android screen capture, and the foreground service keeps the gesture listener alive while you use other apps.",
                actionLabel = "Start screen capture",
                onAction = onCapture,
            )
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(20.dp)) {
                    Text("Gesture recipe", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        GestureBadge("01", "Open palm", Icons.Filled.CameraAlt)
                        GestureBadge("02", "Closed fist", Icons.Filled.Lock)
                        GestureBadge("03", "Action", Icons.Filled.ScreenShare)
                    }
                }
            }
        }
        item {
            SectionCard(
                title = "Privacy by default",
                body = "Gesture inference is local. Screenshots remain in app storage until you export or share them. Nearby transfer is device-to-device, without a cloud upload path.",
            )
        }
        if (latestCapture != null) {
            item {
                LatestCaptureCard(latestCapture, onSaveGallery, onDeleteCapture, onShare)
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun GestureBadge(number: String, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(90.dp)) {
        Surface(shape = RoundedCornerShape(18.dp), tonalElevation = 2.dp) {
            Box(Modifier.size(60.dp), contentAlignment = Alignment.Center) { Icon(icon, null) }
        }
        Spacer(Modifier.height(7.dp))
        Text(number, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun LatestCaptureCard(file: File, onSaveGallery: (File) -> Boolean, onDeleteCapture: (File) -> Boolean, onShare: (File) -> Unit) {
    var saveState by remember { mutableStateOf("") }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text("Latest capture", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            val bitmap = remember(file.absolutePath) { BitmapFactory.decodeFile(file.absolutePath) }
            DisposableEffect(bitmap) {
                onDispose { runCatching { bitmap?.recycle() } }
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Latest screenshot",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(20.dp)),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(file.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { saveState = if (onSaveGallery(file)) "Saved to Pictures" else "Could not save" }) { Text("Save to gallery") }
                TextButton(onClick = { onShare(file) }) { Text("Share") }
                TextButton(onClick = { saveState = if (onDeleteCapture(file)) "Deleted from app storage" else "Could not delete" }) { Text("Delete") }
            }
            if (saveState.isNotBlank()) Text(saveState, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun GestureScreen(
    modifier: Modifier,
    onRequestCamera: () -> Unit,
    onRequestCapture: () -> Unit,
    backgroundRunning: Boolean,
    captureReady: Boolean,
    backgroundState: String,
    backgroundGestureState: GestureRecognizerController.GestureUiState,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasCameraPermission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var uiState by remember { mutableStateOf(GestureRecognizerController.GestureUiState()) }
    var actionText by remember { mutableStateOf("") }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasCameraPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Gesture Lab", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("The recognizer uses the front camera locally. PalmLink can keep the gesture listener alive in a visible foreground service while you use another app.", color = MaterialTheme.colorScheme.onSurfaceVariant)

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(18.dp)) {
                Text("Background mode", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(backgroundState)
                Spacer(Modifier.height(10.dp))
                if (!backgroundRunning) {
                    Button(onClick = onRequestCapture, enabled = hasCameraPermission) {
                        Icon(Icons.Filled.ScreenShare, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Enable PalmLink")
                    }
                } else {
                    StatusChip("RUNNING IN BACKGROUND")
                    Spacer(Modifier.height(8.dp))
                    Text("Open palm → fist captures the current screen and sends it to the connected device. Fist → open palm receives an incoming screenshot. A persistent notification provides the stop control.")
                    Spacer(Modifier.height(8.dp))
                    Text("${backgroundGestureState.message}")
                    if (!captureReady) {
                        Spacer(Modifier.height(8.dp))
                        Text("Screen capture has ended (for example the screen was locked). Receiving still works; re-enable screen capture to take screenshots.")
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onRequestCapture) {
                            Icon(Icons.Filled.ScreenShare, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Re-enable screen capture")
                        }
                    }
                }
            }
        }

        if (!hasCameraPermission) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(20.dp)) {
                    Icon(Icons.Filled.CameraAlt, null)
                    Spacer(Modifier.height(10.dp))
                    Text("Camera access is required", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text("Camera frames are processed locally for gesture recognition.")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRequestCamera) { Text("Allow camera") }
                }
            }
        } else if (!backgroundRunning) {
            Surface(
                shape = RoundedCornerShape(26.dp),
                modifier = Modifier.fillMaxWidth().height(390.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(26.dp)),
            ) {
                Box {
                    AndroidView(
                        factory = { ctx -> PreviewView(ctx).also {
                            it.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            it.scaleType = PreviewView.ScaleType.FILL_CENTER
                            previewView = it
                        } },
                        modifier = Modifier.fillMaxSize(),
                    )
                    Column(Modifier.align(Alignment.TopStart).padding(16.dp)) {
                        StatusChip(uiState.message)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            uiState.confidence?.let { "Confidence ${(it.coerceIn(0f, 1f) * 100).toInt()}%" }
                                ?: "Confidence unavailable",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    Text(
                        when (uiState.label) {
                            GestureStateMachine.Label.OPEN_PALM -> "OPEN PALM"
                            GestureStateMachine.Label.CLOSED_FIST -> "CLOSED FIST"
                            GestureStateMachine.Label.NONE -> "READY"
                        },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(18.dp),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Text("Capture test: open your palm, hold, then make a fist. Your fist must remain stable before the action fires.")
            Button(onClick = onRequestCapture, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.ScreenShare, null)
                Spacer(Modifier.width(8.dp))
                Text("Re-authorize screen capture")
            }
            if (actionText.isNotBlank()) Text(actionText, color = MaterialTheme.colorScheme.primary)
        }
    }

    DisposableEffect(previewView, hasCameraPermission, backgroundRunning) {
        val pv = previewView
        if (!hasCameraPermission || pv == null || backgroundRunning) {
            onDispose { }
        } else {
            val controller = GestureRecognizerController(
                context = context,
                lifecycleOwner = lifecycleOwner,
                previewView = pv,
                mode = GestureStateMachine.GestureMode.CAPTURE,
                onState = { uiState = it },
                onAction = { action ->
                    when (action) {
                        GestureStateMachine.Action.CAPTURE -> {
                            actionText = "Gesture recognized. Starting Android screen-capture consent…"
                            onRequestCapture()
                        }
                        GestureStateMachine.Action.ARMED -> actionText = "Sequence armed. Complete the second gesture."
                        else -> Unit
                    }
                },
            )
            controller.start()
            onDispose { controller.stop() }
        }
    }
}

@Composable
private fun NearbyScreen(
    modifier: Modifier,
    state: NearbyTransferManager.State,
    latestCapture: File?,
    onRequestNearby: () -> Unit,
    onRequestCamera: () -> Unit,
    onStartAdvertising: () -> Unit,
    onStartDiscovery: () -> Unit,
    onStopNearby: () -> Unit,
    onForgetPairing: () -> Unit,
    onConfirmConnection: () -> Unit,
    onRejectConnection: () -> Unit,
    onSendOffer: (File) -> Unit,
    backgroundRunning: Boolean,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasNearby by remember { mutableStateOf(nearbyPermissionsGranted(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasNearby = nearbyPermissionsGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Nearby", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Pair once, then use gestures only: open palm → fist captures and sends; fist → open palm receives. No cloud account required.", color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (!hasNearby) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(20.dp)) {
                    Icon(Icons.Filled.Security, null)
                    Spacer(Modifier.height(8.dp))
                    Text("Nearby permissions", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("Android uses nearby-device permissions to allow discovery and local connections.")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRequestNearby) { Text("Allow nearby access") }
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(20.dp)) {
                Text("Connection", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                StatusChip(if (state.connected) "Connected" else "Not connected")
                Spacer(Modifier.height(6.dp))
                Text(state.message)
                if (state.endpointName != null) Text("Device: ${state.endpointName}", style = MaterialTheme.typography.labelLarge)
                if (state.authDigits != null) {
                    Text("Auth digits: ${state.authDigits}", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                }
                if (state.pendingConnectionEndpointId != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onConfirmConnection) { Text("Confirm") }
                        TextButton(onClick = onRejectConnection) { Text("Reject") }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onStartAdvertising, enabled = hasNearby) { Text("Make visible") }
                    TextButton(onClick = onStartDiscovery, enabled = hasNearby) { Text("Find devices") }
                }
                if (state.connected || state.advertising || state.discovering) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onStopNearby) { Text("Stop Nearby") }
                        TextButton(onClick = onForgetPairing) { Text("Forget pairing") }
                    }
                }
            }
        }

        if (latestCapture != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(20.dp)) {
                    Text("Send", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text("Open palm → fist captures the screen and sends it automatically. If a send was missed, you can offer the latest capture again here.")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { onSendOffer(latestCapture) }, enabled = state.connected) {
                        Icon(Icons.Filled.Send, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Send latest capture again")
                    }
                }
            }
        }

        val offer = state.pendingOffer
        if (offer != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(20.dp)) {
                    Text("Screenshot waiting", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("${offer.fileName} • ${offer.byteCount / 1024} KB")
                    Spacer(Modifier.height(8.dp))
                    Text("Make a fist, then open your palm to receive this screenshot. No tap needed.")
                }
            }
        }

        NearbyGesturePanel(
            state = state,
            backgroundRunning = backgroundRunning,
            onRequestCamera = onRequestCamera,
        )

        TransferProgress(state.progress)

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(20.dp)) {
                Text("Security note", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(5.dp))
                Text("The app displays the Nearby authentication digits. Compare them on both devices before continuing with sensitive files.")
            }
        }
    }
}


@Composable
private fun NearbyGesturePanel(
    state: NearbyTransferManager.State,
    backgroundRunning: Boolean,
    onRequestCamera: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val hasCameraPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    // While the background service runs, ITS camera engine handles every gesture. A second
    // foreground controller would take over the camera (only one controller may be active) and
    // double-handle gestures, so the panel is hidden in that case.
    val mode = if (state.connected && !backgroundRunning) GestureStateMachine.GestureMode.RECEIVE else null
    var previewView by remember(mode) { mutableStateOf<PreviewView?>(null) }
    var gestureState by remember(mode) { mutableStateOf(GestureRecognizerController.GestureUiState()) }
    var lastGestureMessage by remember(mode) { mutableStateOf("") }

    if (mode != null) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    "Gesture receive",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                Text("Make a fist, then open your palm to receive the incoming screenshot. Enable PalmLink on the Gesture tab to do this from other apps.")
                Spacer(Modifier.height(12.dp))

                if (!hasCameraPermission) {
                    Button(onClick = onRequestCamera) { Text("Allow camera for gesture transfer") }
                } else {
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        modifier = Modifier.fillMaxWidth().height(250.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(22.dp)),
                    ) {
                        Box {
                            AndroidView(
                                factory = { ctx -> PreviewView(ctx).also {
                            it.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            it.scaleType = PreviewView.ScaleType.FILL_CENTER
                            previewView = it
                        } },
                                modifier = Modifier.fillMaxSize(),
                            )
                            Column(Modifier.align(Alignment.TopStart).padding(12.dp)) {
                                StatusChip(gestureState.message)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    gestureState.confidence?.let { "Confidence ${(it * 100).toInt()}%" }
                                        ?: "Confidence unavailable",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
                if (lastGestureMessage.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(lastGestureMessage, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }

    DisposableEffect(previewView, hasCameraPermission, mode) {
        val pv = previewView
        if (!hasCameraPermission || pv == null || mode == null) {
            onDispose { }
        } else {
            val controller = GestureRecognizerController(
                context = context,
                lifecycleOwner = lifecycleOwner,
                previewView = pv,
                mode = mode,
                onState = { gestureState = it },
                onAction = { action ->
                    when (action) {
                        GestureStateMachine.Action.RECEIVE_ACCEPT -> {
                            lastGestureMessage = "Gesture recognized. Receiving screenshot…"
                            NearbyRuntime.get(context).authorizeReceive()
                        }
                        GestureStateMachine.Action.ARMED -> lastGestureMessage = "Sequence armed. Complete the gesture."
                        else -> Unit
                    }
                },
            )
            controller.start()
            onDispose { controller.stop() }
        }
    }
}

private fun nearbyPermissionsGranted(context: Context): Boolean {
    val permissions = buildList {
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else if (Build.VERSION.SDK_INT >= 29) {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }
    return permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
}
