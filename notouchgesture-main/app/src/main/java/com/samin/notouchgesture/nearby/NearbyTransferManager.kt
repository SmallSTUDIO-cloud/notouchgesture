package com.samin.notouchgesture.nearby

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
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
import java.util.concurrent.CopyOnWriteArrayList

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
    private val incomingUris = ConcurrentHashMap<Long, android.net.Uri>()

    private var state = State()
    private val stateListeners = CopyOnWriteArrayList<(State) -> Unit>()
    private var pendingOutgoing: File? = null

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
        if (!hasNearbyPermissions()) return false
        connectionsClient.acceptConnection(endpointId, payloadCallback)
        state = state.copy(pendingConnectionEndpointId = null, message = "Connecting…")
        emit()
        return true
    }

    fun rejectPendingConnection(): Boolean {
        val endpointId = state.pendingConnectionEndpointId ?: return false
        if (!hasNearbyPermissions()) return false
        connectionsClient.rejectConnection(endpointId)
        state = state.copy(
            pendingConnectionEndpointId = null,
            authDigits = null,
            message = "Connection rejected.",
        )
        emit()
        return true
    }

    fun stopAll() {
        if (hasNearbyPermissions()) {
            connectionsClient.stopAdvertising()
            connectionsClient.stopDiscovery()
            connectionsClient.stopAllEndpoints()
        }
        activeEndpoints.clear()
        pendingOutgoing = null
        incomingUris.clear()
        state = State(message = "Not connected")
        emit()
    }

    fun startAdvertising(): Boolean {
        if (!hasNearbyPermissions()) return false
        connectionsClient.startAdvertising(
            localEndpointName(),
            serviceId,
            connectionLifecycleCallback,
            AdvertisingOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build(),
        ).addOnSuccessListener {
            state = state.copy(advertising = true, message = "Visible to nearby devices")
            emit()
        }.addOnFailureListener { error ->
            state = state.copy(advertising = false, message = "Could not become discoverable: ${error.message ?: "unknown error"}")
            emit()
        }
        return true
    }

    fun startDiscovery(): Boolean {
        if (!hasNearbyPermissions()) return false
        connectionsClient.startDiscovery(
            serviceId,
            endpointDiscoveryCallback,
            DiscoveryOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build(),
        ).addOnSuccessListener {
            state = state.copy(discovering = true, message = "Looking for nearby devices…")
            emit()
        }.addOnFailureListener { error ->
            state = state.copy(discovering = false, message = "Could not start discovery: ${error.message ?: "unknown error"}")
            emit()
        }
        return true
    }

    fun sendOffer(file: File): Boolean {
        val endpoint = activeEndpoints.firstOrNull() ?: return false
        if (!file.exists()) return false
        pendingOutgoing = file
        connectionsClient.sendPayload(
            endpoint,
            Payload.fromBytes(TransferProtocol.encode(TransferMessage.Offer(file.name, file.length())))
        ).addOnSuccessListener {
            state = state.copy(message = "Offer sent. Waiting for the receiver's open palm…", progress = 0)
            emit()
        }.addOnFailureListener { error ->
            pendingOutgoing = null
            state = state.copy(message = "Offer failed: ${error.message ?: "unknown error"}")
            emit()
        }
        return true
    }

    fun acceptPendingOffer(): Boolean {
        val endpoint = activeEndpoints.firstOrNull() ?: return false
        val offer = state.pendingOffer ?: return false
        connectionsClient.sendPayload(
            endpoint,
            Payload.fromBytes(TransferProtocol.encode(TransferMessage.Accept(offer.fileName)))
        ).addOnSuccessListener {
            state = state.copy(message = "Accepted. Receiving the screenshot…", pendingOffer = null)
            emit()
        }.addOnFailureListener { error ->
            state = state.copy(message = "Could not accept: ${error.message ?: "unknown error"}")
            emit()
        }
        return true
    }

    private fun sendPendingFile() {
        val file = pendingOutgoing ?: return
        val endpoint = activeEndpoints.firstOrNull() ?: return
        if (!file.exists()) {
            state = state.copy(message = "The screenshot is no longer available.")
            emit()
            return
        }
        connectionsClient.sendPayload(endpoint, Payload.fromFile(file))
            .addOnSuccessListener {
                state = state.copy(message = "Sending ${file.name}…", progress = 0)
                emit()
            }
            .addOnFailureListener { error ->
                state = state.copy(message = "Transfer failed: ${error.message ?: "unknown error"}")
                emit()
            }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (!hasNearbyPermissions()) return
            state = state.copy(message = "Found ${info.endpointName}. Connecting…", endpointName = info.endpointName)
            emit()
            connectionsClient.requestConnection(localEndpointName(), endpointId, connectionLifecycleCallback)
                .addOnFailureListener { error ->
                    state = state.copy(message = "Connection request failed: ${error.message ?: "unknown error"}")
                    emit()
                }
        }

        override fun onEndpointLost(endpointId: String) {
            activeEndpoints.remove(endpointId)
            state = state.copy(
                connected = activeEndpoints.isNotEmpty(),
                message = if (activeEndpoints.isEmpty()) "Nearby device left." else state.message,
            )
            emit()
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            // Discovery/advertising are user-armed in this app. Authentication digits remain visible for comparison.
            state = state.copy(
                endpointName = connectionInfo.endpointName,
                authDigits = connectionInfo.authenticationDigits,
                pendingConnectionEndpointId = endpointId,
                message = "Compare the digits on both devices, then confirm the connection.",
            )
            emit()
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                activeEndpoints += endpointId
                state = state.copy(connected = true, pendingConnectionEndpointId = null, message = "Nearby connection ready.")
            } else {
                activeEndpoints.remove(endpointId)
                state = state.copy(connected = activeEndpoints.isNotEmpty(), pendingConnectionEndpointId = null, message = "Nearby connection rejected or failed.")
            }
            emit()
        }

        override fun onDisconnected(endpointId: String) {
            activeEndpoints.remove(endpointId)
            state = state.copy(
                connected = activeEndpoints.isNotEmpty(),
                endpointName = if (activeEndpoints.isEmpty()) null else state.endpointName,
                message = if (activeEndpoints.isEmpty()) "Disconnected." else state.message,
            )
            emit()
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                when (val message = payload.asBytes()?.let(TransferProtocol::decode)) {
                    is TransferMessage.Offer -> {
                        state = state.copy(
                            pendingOffer = message,
                            message = "Screenshot offer ready. Open your palm to accept.",
                            progress = 0,
                        )
                        emit()
                    }
                    is TransferMessage.Accept -> sendPendingFile()
                    else -> Unit
                }
            } else if (payload.type == Payload.Type.FILE) {
                payload.asFile()?.asUri()?.let { incomingUris[payload.id] = it }
                state = state.copy(message = "Screenshot is arriving…", progress = 0)
                emit()
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            when (update.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS -> {
                    val percent = if (update.totalBytes > 0) {
                        ((update.bytesTransferred * 100L) / update.totalBytes).toInt().coerceIn(0, 100)
                    } else 0
                    state = state.copy(progress = percent, message = "Transferring… $percent%")
                    emit()
                }
                PayloadTransferUpdate.Status.SUCCESS -> {
                    val uri = incomingUris.remove(update.payloadId)
                    val saved = uri?.let(::copyIncomingToAppStorage)
                    state = state.copy(
                        progress = 100,
                        message = if (saved != null) "Transfer complete. Screenshot saved." else "Transfer complete.",
                        lastReceivedFile = saved ?: state.lastReceivedFile,
                    )
                    emit()
                }
                PayloadTransferUpdate.Status.FAILURE -> {
                    incomingUris.remove(update.payloadId)
                    state = state.copy(message = "Transfer failed. No partial file was accepted.")
                    emit()
                }
                else -> Unit
            }
        }
    }

    private fun copyIncomingToAppStorage(uri: android.net.Uri): File? {
        return runCatching {
            val folder = File(context.filesDir, "captures").apply { mkdirs() }
            val file = File(folder, "received-${System.currentTimeMillis()}.png")
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            file
        }.getOrNull()
    }

    private fun hasNearbyPermissions(): Boolean {
        val permissions = buildList {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        return permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun localEndpointName(): String = android.os.Build.MODEL.take(40)

    private fun emit() { stateListeners.forEach { listener -> listener(state) } }
}
