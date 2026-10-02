package com.samin.notouchgesture.nearby

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-local Nearby Connections owner shared by the Activity and foreground service.
 *
 * Nearby operations are deliberately idempotent. A device uses either the advertising role
 * or the discovery role at a time; switching roles stops the previous scan/advertisement first.
 * This prevents the common 8001/8002 duplicate-operation failures during UI/service lifecycle
 * transitions.
 */
class NearbyTransferManager(
    private val context: Context,
    private val serviceId: String = context.packageName,
) {
    data class State(
        val discovering: Boolean = false,
        val advertising: Boolean = false,
        val connected: Boolean = false,
        val endpointName: String? = null,
        val authDigits: String? = null,
        val pendingConnectionEndpointId: String? = null,
        val message: String = "Not connected",
        val progress: Int = 0,
        val pendingOffer: TransferMessage.Offer? = null,
        val lastReceivedFile: File? = null,
    )

    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val activeEndpoints = ConcurrentHashMap.newKeySet<String>()
    private val discoveredEndpoints = ConcurrentHashMap.newKeySet<String>()
    private val pendingConnections = ConcurrentHashMap.newKeySet<String>()
    private val incomingUris = ConcurrentHashMap<Long, android.net.Uri>()
    private val outgoingPayloads = ConcurrentHashMap.newKeySet<Long>()

    private val acceptingConnection = AtomicBoolean(false)
    private val acceptingOffer = AtomicBoolean(false)
    private val sendingOffer = AtomicBoolean(false)
    private val sendingFile = AtomicBoolean(false)
    private val stateLock = Any()

    @Volatile
    private var state = State()
    private val stateListeners = CopyOnWriteArrayList<(State) -> Unit>()
    private val operationHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var pendingOutgoing: File? = null

    private var advertisingStartInFlight = false
    private var discoveryStartInFlight = false
    private var advertisingGeneration = 0L
    private var discoveryGeneration = 0L

    fun setStateListener(listener: (State) -> Unit) {
        stateListeners.clear()
        addStateListener(listener)
    }

    fun addStateListener(listener: (State) -> Unit) {
        stateListeners += listener
        listener(state)
    }

    fun removeStateListener(listener: (State) -> Unit) {
        stateListeners.remove(listener)
    }

    fun confirmPendingConnection(): Boolean {
        val endpointId = state.pendingConnectionEndpointId ?: return false
        if (!hasNearbyPermissions()) {
            updateState { it.copy(message = "Nearby permissions are required.") }
            return false
        }
        if (!acceptingConnection.compareAndSet(false, true)) return true

        connectionsClient.acceptConnection(endpointId, payloadCallback)
            .addOnSuccessListener {
                acceptingConnection.set(false)
                pendingConnections.remove(endpointId)
                updateState {
                    it.copy(pendingConnectionEndpointId = null, authDigits = null, message = "Connecting…")
                }
            }
            .addOnFailureListener { error ->
                acceptingConnection.set(false)
                updateState {
                    it.copy(message = "Could not accept connection: ${friendlyError(error)}")
                }
            }
        return true
    }

    fun rejectPendingConnection(): Boolean {
        val endpointId = state.pendingConnectionEndpointId ?: return false
        if (!hasNearbyPermissions()) {
            updateState { it.copy(message = "Nearby permissions are required.") }
            return false
        }
        pendingConnections.remove(endpointId)
        val rejected = runCatching { connectionsClient.rejectConnection(endpointId) }.isSuccess
        if (!rejected) {
            updateState { it.copy(message = "Could not reject connection.") }
            return false
        }
        updateState {
            it.copy(
                pendingConnectionEndpointId = null,
                authDigits = null,
                message = "Connection rejected.",
            )
        }
        return true
    }

    fun stopAll() {
        operationHandler.removeCallbacksAndMessages(null)
        synchronized(stateLock) {
            advertisingStartInFlight = false
            discoveryStartInFlight = false
            advertisingGeneration++
            discoveryGeneration++
        }
        runCatching { connectionsClient.stopAdvertising() }
        runCatching { connectionsClient.stopDiscovery() }
        runCatching { connectionsClient.stopAllEndpoints() }

        activeEndpoints.clear()
        discoveredEndpoints.clear()
        pendingConnections.clear()
        pendingOutgoing = null
        incomingUris.clear()
        outgoingPayloads.clear()
        acceptingConnection.set(false)
        acceptingOffer.set(false)
        sendingOffer.set(false)
        sendingFile.set(false)
        updateState { State(message = "Not connected") }
    }

    fun startAdvertising(): Boolean {
        if (!hasNearbyPermissions()) {
            updateState { it.copy(message = "Nearby permissions are required to become visible.") }
            return false
        }

        // A device has one deliberate Nearby role at a time. Stop discovery before queuing
        // advertising; the generation token invalidates any older in-flight start callback.
        val stopDiscovery = synchronized(stateLock) {
            val needed = state.discovering || discoveryStartInFlight
            if (needed) {
                discoveryStartInFlight = false
                discoveryGeneration++
            }
            needed
        }
        if (stopDiscovery) runCatching { connectionsClient.stopDiscovery() }

        val token = synchronized(stateLock) {
            if (state.advertising || advertisingStartInFlight) return true
            advertisingStartInFlight = true
            ++advertisingGeneration
        }

        updateState {
            it.copy(
                discovering = false,
                advertising = false,
                message = if (stopDiscovery) "Preparing nearby visibility…" else "Making PalmLink visible…",
            )
        }
        operationHandler.post { beginAdvertising(token, 0) }
        return true
    }

    private fun beginAdvertising(token: Long, attempt: Int) {
        val valid = synchronized(stateLock) { token == advertisingGeneration && advertisingStartInFlight }
        if (!valid) return

        connectionsClient.startAdvertising(
            localEndpointName(),
            serviceId,
            connectionLifecycleCallback,
            AdvertisingOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build(),
        ).addOnSuccessListener {
            val current = synchronized(stateLock) {
                if (token != advertisingGeneration) return@addOnSuccessListener
                advertisingStartInFlight = false
                true
            }
            if (current) {
                updateState { it.copy(advertising = true, discovering = false, message = "Visible to nearby devices") }
            }
        }.addOnFailureListener { error ->
            if (token != synchronized(stateLock) { advertisingGeneration }) return@addOnFailureListener

            // A bounded event-queue retry handles a stop/start completion race without timers.
            if (isAlreadyDiscovering(error) && attempt < 2) {
                operationHandler.post { beginAdvertising(token, attempt + 1) }
                return@addOnFailureListener
            }

            val alreadyActive = isAlreadyAdvertising(error)
            synchronized(stateLock) {
                if (token != advertisingGeneration) return@addOnFailureListener
                advertisingStartInFlight = false
            }
            if (alreadyActive) {
                updateState { it.copy(advertising = true, discovering = false, message = "Already visible to nearby devices") }
            } else {
                updateState { it.copy(advertising = false, message = "Could not become discoverable: ${friendlyError(error)}") }
            }
        }
    }

    fun startDiscovery(): Boolean {
        if (!hasNearbyPermissions()) {
            updateState { it.copy(message = "Nearby permissions are required to find devices.") }
            return false
        }

        // Stop advertising before switching to discovery; the generation token invalidates
        // any older in-flight start callback.
        val stopAdvertising = synchronized(stateLock) {
            val needed = state.advertising || advertisingStartInFlight
            if (needed) {
                advertisingStartInFlight = false
                advertisingGeneration++
            }
            needed
        }
        if (stopAdvertising) runCatching { connectionsClient.stopAdvertising() }

        val token = synchronized(stateLock) {
            if (state.discovering || discoveryStartInFlight) return true
            discoveryStartInFlight = true
            ++discoveryGeneration
        }

        updateState {
            it.copy(
                advertising = false,
                discovering = false,
                message = if (stopAdvertising) "Preparing nearby search…" else "Starting nearby search…",
            )
        }
        operationHandler.post { beginDiscovery(token, 0) }
        return true
    }

    private fun beginDiscovery(token: Long, attempt: Int) {
        val valid = synchronized(stateLock) { token == discoveryGeneration && discoveryStartInFlight }
        if (!valid) return

        connectionsClient.startDiscovery(
            serviceId,
            endpointDiscoveryCallback,
            DiscoveryOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build(),
        ).addOnSuccessListener {
            val current = synchronized(stateLock) {
                if (token != discoveryGeneration) return@addOnSuccessListener
                discoveryStartInFlight = false
                true
            }
            if (current) {
                updateState { it.copy(discovering = true, advertising = false, message = "Looking for nearby devices…") }
            }
        }.addOnFailureListener { error ->
            if (token != synchronized(stateLock) { discoveryGeneration }) return@addOnFailureListener

            if (isAlreadyAdvertising(error) && attempt < 2) {
                operationHandler.post { beginDiscovery(token, attempt + 1) }
                return@addOnFailureListener
            }

            val alreadyActive = isAlreadyDiscovering(error)
            synchronized(stateLock) {
                if (token != discoveryGeneration) return@addOnFailureListener
                discoveryStartInFlight = false
            }
            if (alreadyActive) {
                updateState { it.copy(discovering = true, advertising = false, message = "Already searching for nearby devices…") }
            } else {
                updateState { it.copy(discovering = false, message = "Could not start discovery: ${friendlyError(error)}") }
            }
        }
    }

    fun sendOffer(file: File): Boolean {
        val endpoint = activeEndpoints.firstOrNull() ?: run {
            updateState { it.copy(message = "Connect to a nearby device first.") }
            return false
        }
        if (!file.exists() || !file.isFile || file.length() <= 0L) {
            updateState { it.copy(message = "The screenshot file is unavailable.") }
            return false
        }
        if (file.length() > MAX_TRANSFER_BYTES) {
            updateState { it.copy(message = "The screenshot is too large to send.") }
            return false
        }
        if (pendingOutgoing != null || sendingFile.get()) {
            updateState { it.copy(message = "A screenshot transfer is already in progress.") }
            return true
        }
        if (!sendingOffer.compareAndSet(false, true)) return true

        pendingOutgoing = file
        val offerPayload = Payload.fromBytes(
            TransferProtocol.encode(TransferMessage.Offer(file.name, file.length()))
        )
        connectionsClient.sendPayload(endpoint, offerPayload)
            .addOnSuccessListener {
                sendingOffer.set(false)
                updateState { it.copy(message = "Offer sent. Waiting for the receiver's open palm…", progress = 0) }
            }
            .addOnFailureListener { error ->
                sendingOffer.set(false)
                pendingOutgoing = null
                updateState { it.copy(message = "Offer failed: ${friendlyError(error)}") }
            }
        return true
    }

    fun acceptPendingOffer(): Boolean {
        val endpoint = activeEndpoints.firstOrNull() ?: run {
            updateState { it.copy(message = "Connect to the sender first.") }
            return false
        }
        val offer = state.pendingOffer ?: return false
        if (!acceptingOffer.compareAndSet(false, true)) return true

        connectionsClient.sendPayload(
            endpoint,
            Payload.fromBytes(TransferProtocol.encode(TransferMessage.Accept(offer.fileName))),
        ).addOnSuccessListener {
            acceptingOffer.set(false)
            updateState {
                it.copy(message = "Accepted. Receiving the screenshot…", pendingOffer = null, progress = 0)
            }
        }.addOnFailureListener { error ->
            acceptingOffer.set(false)
            updateState { it.copy(message = "Could not accept: ${friendlyError(error)}") }
        }
        return true
    }

    private fun sendPendingFile() {
        val file = pendingOutgoing ?: return
        val endpoint = activeEndpoints.firstOrNull() ?: return
        if (!file.exists() || file.length() <= 0L) {
            pendingOutgoing = null
            updateState { it.copy(message = "The screenshot is no longer available.") }
            return
        }
        if (file.length() > MAX_TRANSFER_BYTES) {
            pendingOutgoing = null
            updateState { it.copy(message = "The screenshot is too large to send.") }
            return
        }
        if (!sendingFile.compareAndSet(false, true)) return

        val payload = Payload.fromFile(file)
        outgoingPayloads += payload.id
        connectionsClient.sendPayload(endpoint, payload)
            .addOnSuccessListener {
                updateState { it.copy(message = "Sending ${file.name}…", progress = 0) }
            }
            .addOnFailureListener { error ->
                outgoingPayloads.remove(payload.id)
                sendingFile.set(false)
                pendingOutgoing = null
                updateState { it.copy(message = "Transfer failed: ${friendlyError(error)}") }
            }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (!hasNearbyPermissions()) return
            if (!state.discovering) return
            if (activeEndpoints.contains(endpointId) || !discoveredEndpoints.add(endpointId) || !pendingConnections.add(endpointId)) return

            updateState {
                it.copy(message = "Found ${info.endpointName}. Connecting…", endpointName = info.endpointName)
            }
            connectionsClient.requestConnection(localEndpointName(), endpointId, connectionLifecycleCallback)
                .addOnFailureListener { error ->
                    pendingConnections.remove(endpointId)
                    discoveredEndpoints.remove(endpointId)
                    updateState { it.copy(message = "Connection request failed: ${friendlyError(error)}") }
                }
        }

        override fun onEndpointLost(endpointId: String) {
            activeEndpoints.remove(endpointId)
            discoveredEndpoints.remove(endpointId)
            pendingConnections.remove(endpointId)
            clearTransferStateIfNoConnections()
            updateState {
                val isSamePending = it.pendingConnectionEndpointId == endpointId
                it.copy(
                    connected = activeEndpoints.isNotEmpty(),
                    pendingConnectionEndpointId = if (isSamePending) null else it.pendingConnectionEndpointId,
                    authDigits = if (isSamePending) null else it.authDigits,
                    message = if (activeEndpoints.isEmpty()) "Nearby device left." else it.message,
                )
            }
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            pendingConnections.add(endpointId)
            updateState {
                it.copy(
                    endpointName = connectionInfo.endpointName,
                    authDigits = connectionInfo.authenticationDigits,
                    pendingConnectionEndpointId = endpointId,
                    message = "Compare the digits on both devices, then confirm the connection.",
                )
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            pendingConnections.remove(endpointId)
            if (result.status.isSuccess) {
                activeEndpoints += endpointId
                discoveredEndpoints.remove(endpointId)
                updateState {
                    it.copy(
                        connected = true,
                        pendingConnectionEndpointId = null,
                        authDigits = null,
                        message = "Nearby connection ready.",
                    )
                }
            } else {
                activeEndpoints.remove(endpointId)
                updateState {
                    it.copy(
                        connected = activeEndpoints.isNotEmpty(),
                        pendingConnectionEndpointId = null,
                        authDigits = null,
                        message = "Nearby connection rejected or failed.",
                    )
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            activeEndpoints.remove(endpointId)
            pendingConnections.remove(endpointId)
            discoveredEndpoints.remove(endpointId)
            clearTransferStateIfNoConnections()
            updateState {
                val isSamePending = it.pendingConnectionEndpointId == endpointId
                it.copy(
                    connected = activeEndpoints.isNotEmpty(),
                    endpointName = if (activeEndpoints.isEmpty()) null else it.endpointName,
                    pendingConnectionEndpointId = if (isSamePending) null else it.pendingConnectionEndpointId,
                    authDigits = if (isSamePending) null else it.authDigits,
                    message = if (activeEndpoints.isEmpty()) "Disconnected." else it.message,
                )
            }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (!activeEndpoints.contains(endpointId)) return

            when (payload.type) {
                Payload.Type.BYTES -> {
                    when (val message = payload.asBytes()?.let(TransferProtocol::decode)) {
                        is TransferMessage.Offer -> {
                            if (message.byteCount !in 1L..MAX_TRANSFER_BYTES) {
                                updateState { it.copy(message = "The incoming screenshot is too large or invalid.") }
                                return
                            }
                            if (state.pendingOffer != null) return
                            updateState {
                                it.copy(
                                    pendingOffer = message,
                                    message = "Screenshot offer ready. Open your palm to accept.",
                                    progress = 0,
                                )
                            }
                        }
                        is TransferMessage.Accept -> {
                            if (message.fileName == pendingOutgoing?.name) sendPendingFile()
                        }
                        is TransferMessage.Reject -> {
                            sendingOffer.set(false)
                            pendingOutgoing = null
                            updateState { it.copy(message = "Transfer rejected: ${message.reason}") }
                        }
                        is TransferMessage.Ping,
                        null -> Unit
                    }
                }
                Payload.Type.FILE -> {
                    val uri = payload.asFile()?.asUri()
                    if (uri == null) {
                        updateState { it.copy(message = "Incoming screenshot payload is unavailable.") }
                        return
                    }
                    incomingUris[payload.id] = uri
                    updateState { it.copy(message = "Screenshot is arriving…", progress = 0) }
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (!activeEndpoints.contains(endpointId)) return

            val trackedFile = outgoingPayloads.contains(update.payloadId) || incomingUris.containsKey(update.payloadId)
            if (!trackedFile) return

            when (update.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS -> {
                    val percent = if (update.totalBytes > 0L) {
                        ((update.bytesTransferred * 100L) / update.totalBytes).toInt().coerceIn(0, 100)
                    } else 0
                    updateState { it.copy(progress = percent, message = "Transferring… $percent%") }
                }
                PayloadTransferUpdate.Status.SUCCESS -> {
                    val outgoing = outgoingPayloads.remove(update.payloadId)
                    if (outgoing) {
                        sendingFile.set(false)
                        pendingOutgoing = null
                    }
                    val uri = incomingUris.remove(update.payloadId)
                    val saved = uri?.let(::copyIncomingToAppStorage)
                    updateState {
                        it.copy(
                            progress = 100,
                            message = if (saved != null) "Transfer complete. Screenshot saved." else "Transfer complete.",
                            lastReceivedFile = saved ?: it.lastReceivedFile,
                        )
                    }
                }
                PayloadTransferUpdate.Status.FAILURE -> {
                    val outgoing = outgoingPayloads.remove(update.payloadId)
                    if (outgoing) {
                        sendingFile.set(false)
                        pendingOutgoing = null
                    }
                    incomingUris.remove(update.payloadId)
                    updateState { it.copy(message = "Transfer failed. No partial file was accepted.", progress = 0) }
                }
                else -> Unit
            }
        }
    }

    private fun clearTransferStateIfNoConnections() {
        if (activeEndpoints.isNotEmpty()) return
        pendingOutgoing = null
        incomingUris.clear()
        outgoingPayloads.clear()
        acceptingOffer.set(false)
        sendingOffer.set(false)
        sendingFile.set(false)
        updateState { it.copy(pendingOffer = null, progress = 0) }
    }

    private fun copyIncomingToAppStorage(uri: android.net.Uri): File? {
        return runCatching {
            val folder = File(context.filesDir, "captures").apply { mkdirs() }
            val file = File(folder, "received-${System.currentTimeMillis()}.png")
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_TRANSFER_BYTES) {
                            file.delete()
                            return null
                        }
                        output.write(buffer, 0, read)
                    }
                }
            } ?: return null
            file.takeIf { it.exists() && it.length() in 1L..MAX_TRANSFER_BYTES } ?: run {
                file.delete()
                null
            }
        }.getOrNull()
    }

    private fun hasNearbyPermissions(): Boolean {
        val permissions = buildList {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
            } else if (android.os.Build.VERSION.SDK_INT >= 29) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            } else {
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        return permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun localEndpointName(): String = android.os.Build.MODEL.take(40).ifBlank { "PalmLink" }

    private fun updateState(transform: (State) -> State) {
        val snapshot = synchronized(stateLock) {
            state = transform(state)
            state
        }
        stateListeners.forEach { listener -> runCatching { listener(snapshot) } }
    }

    private fun friendlyError(error: Exception): String =
        error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

    private fun statusCode(error: Exception): Int? = (error as? ApiException)?.statusCode

    private fun isAlreadyAdvertising(error: Exception): Boolean =
        statusCode(error) == STATUS_ALREADY_ADVERTISING || error.message?.contains("ALREADY_ADVERTISING", true) == true

    private fun isAlreadyDiscovering(error: Exception): Boolean =
        statusCode(error) == STATUS_ALREADY_DISCOVERING || error.message?.contains("ALREADY_DISCOVERING", true) == true

    companion object {
        private const val STATUS_ALREADY_ADVERTISING = 8001
        private const val STATUS_ALREADY_DISCOVERING = 8002
        private const val MAX_TRANSFER_BYTES = 50L * 1024L * 1024L
    }
}
