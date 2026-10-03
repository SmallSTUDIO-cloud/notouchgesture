package com.samin.notouchgesture.nearby

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
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
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The single, process-wide Nearby Connections runtime.
 *
 * OWNERSHIP: the foreground [com.samin.notouchgesture.capture.ScreenCaptureService] owns the
 * lifecycle of this object. The Activity only observes it. Nothing in the UI layer may call
 * [stopAll]; it is called when the service is destroyed.
 *
 * ROLES: a device has one deliberate role at a time (advertise or discover). The role is
 * remembered in [desiredRole]; after a disconnect the runtime re-enters it by itself with a
 * bounded backoff, so the pair reconnects without any UI. All start/stop operations are
 * idempotent (no STATUS_ALREADY_ADVERTISING / STATUS_ALREADY_DISCOVERING).
 *
 * TRANSFER PROTOCOL (per screenshot, identified by a random transferId):
 *   sender   OFFER(id, size, sha256, ...)   -> receiver stores it as the pending offer
 *   receiver user's fist -> open palm       -> ACCEPT(id)
 *   sender   FILE payload (streamed)        -> receiver
 *   receiver verifies size + SHA-256 + PNG signature, then atomically publishes the file
 *   receiver RESULT(id, ok)                 -> sender reports "delivered"
 */
class NearbyTransferManager(
    context: Context,
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
        val sending: Boolean = false,
        val receiving: Boolean = false,
    )

    private enum class Role { NONE, ADVERTISE, DISCOVER }
    private enum class OutPhase { HASHING, OFFERED, SENDING, AWAITING_RESULT }

    private class Outgoing(val id: String, val file: File) {
        @Volatile var phase: OutPhase = OutPhase.HASHING
        @Volatile var payloadId: Long = -1L
        var timeout: Runnable? = null
    }

    private class Incoming(val offer: TransferMessage.Offer) {
        @Volatile var payloadId: Long = -1L
        @Volatile var uri: Uri? = null
        var timeout: Runnable? = null
    }

    private class StoreResult(val file: File?, val error: String?)

    private val appContext: Context = context.applicationContext
    private val connectionsClient = Nearby.getConnectionsClient(appContext)
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PalmLink-Nearby-IO")
    }

    private val activeEndpoints = ConcurrentHashMap.newKeySet<String>()
    private val discoveredEndpoints = ConcurrentHashMap.newKeySet<String>()
    private val pendingConnections = ConcurrentHashMap.newKeySet<String>()
    private val pendingNames = ConcurrentHashMap<String, String>()
    private val pendingUserApprovals = ConcurrentHashMap.newKeySet<String>()
    private val authenticatedEndpoints = ConcurrentHashMap.newKeySet<String>()
    private val pairingTokensSent = ConcurrentHashMap<String, String>()
    private val pairingPeerTokens = ConcurrentHashMap<String, String>()
    private val pairingPeerIds = ConcurrentHashMap<String, String>()
    private val authLocalChallenges = ConcurrentHashMap<String, String>()

    private val acceptingConnection = AtomicBoolean(false)
    private val stateLock = Any()
    private val transferLock = Any()

    @Volatile
    private var state = State()
    private val stateListeners = CopyOnWriteArrayList<(State) -> Unit>()
    private val operationHandler = Handler(Looper.getMainLooper())

    private var advertisingStartInFlight = false
    private var discoveryStartInFlight = false
    private var advertisingGeneration = 0L
    private var discoveryGeneration = 0L

    @Volatile
    private var desiredRole = Role.NONE
    private var resumeAttempt = 0
    private val resumeRunnable = Runnable { resumeRole() }
    private var resumeForce = false

    private var outgoing: Outgoing? = null
    private var incoming: Incoming? = null
    private var queuedFile: File? = null
    private var queuedAt = 0L
    private var offerExpiry: Runnable? = null

    @Volatile
    private var authorizedUntil = 0L

    init {
        desiredRole = when (prefs.getString(KEY_DESIRED_ROLE, null)) {
            ROLE_ADVERTISE -> Role.ADVERTISE
            ROLE_DISCOVER -> Role.DISCOVER
            else -> Role.NONE
        }
        val storedQueue = prefs.getString(KEY_QUEUED_FILE, null)
        val storedWallAt = prefs.getLong(KEY_QUEUED_AT, 0L)
        if (!storedQueue.isNullOrBlank() && storedWallAt > 0L) {
            val file = File(storedQueue)
            val age = System.currentTimeMillis() - storedWallAt
            if (file.isFile && age in 0L..QUEUE_TTL_MS) {
                queuedFile = file
                queuedAt = storedWallAt
            } else {
                clearQueuedPersistence()
            }
        }
        ioExecutor.execute { cleanupStalePartFiles() }
    }

    // ---------------------------------------------------------------------------------------
    // Observation
    // ---------------------------------------------------------------------------------------

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

    fun currentState(): State = state

    /** True while this runtime has a reason to keep the owning service alive. */
    fun isActive(): Boolean =
        desiredRole != Role.NONE ||
            activeEndpoints.isNotEmpty() ||
            synchronized(stateLock) { advertisingStartInFlight || discoveryStartInFlight }

    // ---------------------------------------------------------------------------------------
    // Connection management
    // ---------------------------------------------------------------------------------------

    fun confirmPendingConnection(): Boolean {
        val endpointId = state.pendingConnectionEndpointId ?: return false
        pendingUserApprovals += endpointId
        return acceptConnection(endpointId)
    }

    private fun acceptConnection(endpointId: String): Boolean {
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
        pendingNames.remove(endpointId)
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

    /** Devices the user has already confirmed once reconnect without a new confirmation. */
    fun forgetTrustedPeers() {
        prefs.edit().remove(KEY_TRUSTED_SECRETS).remove(KEY_TRUSTED_LEGACY).apply()
        authenticatedEndpoints.clear()
        updateState { it.copy(message = "Paired devices forgotten. The next connection needs confirmation.") }
    }

    /** Explicit, full shutdown. Called by the owning service when it is destroyed or stopped. */
    fun stopAll() {
        desiredRole = Role.NONE
        prefs.edit().remove(KEY_DESIRED_ROLE).apply()
        resumeAttempt = 0
        authorizedUntil = 0L
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

        val (out, inc) = synchronized(transferLock) {
            val pair = Pair(outgoing, incoming)
            outgoing = null
            incoming = null
            queuedFile = null
            clearQueuedPersistence()
            pair
        }
        out?.payloadId?.takeIf { it != -1L }?.let { runCatching { connectionsClient.cancelPayload(it) } }
        inc?.payloadId?.takeIf { it != -1L }?.let { runCatching { connectionsClient.cancelPayload(it) } }
        offerExpiry = null

        activeEndpoints.clear()
        authenticatedEndpoints.clear()
        discoveredEndpoints.clear()
        pendingConnections.clear()
        pendingNames.clear()
        pendingUserApprovals.clear()
        pairingTokensSent.clear()
        pairingPeerTokens.clear()
        pairingPeerIds.clear()
        authLocalChallenges.clear()
        acceptingConnection.set(false)
        updateState { State(message = "Not connected") }
    }

    /** Restores the persisted advertise/discover role after the process/service was recreated. */
    fun resumePersistedRole() {
        when (desiredRole) {
            Role.ADVERTISE -> startAdvertising()
            Role.DISCOVER -> startDiscovery()
            Role.NONE -> Unit
        }
    }

    fun startAdvertising(): Boolean {
        if (!hasNearbyPermissions()) {
            updateState { it.copy(message = "Nearby permissions are required to become visible.") }
            return false
        }
        desiredRole = Role.ADVERTISE
        prefs.edit().putString(KEY_DESIRED_ROLE, ROLE_ADVERTISE).apply()
        if (activeEndpoints.isNotEmpty()) {
            updateState { it.copy(message = "Already connected.") }
            return true
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
                Log.w(TAG, "startAdvertising failed", error)
                updateState { it.copy(advertising = false, message = "Could not become discoverable: ${friendlyError(error)}") }
                scheduleRoleResume(force = false)
            }
        }
    }

    fun startDiscovery(): Boolean {
        if (!hasNearbyPermissions()) {
            updateState { it.copy(message = "Nearby permissions are required to find devices.") }
            return false
        }
        desiredRole = Role.DISCOVER
        prefs.edit().putString(KEY_DESIRED_ROLE, ROLE_DISCOVER).apply()
        if (activeEndpoints.isNotEmpty()) {
            updateState { it.copy(message = "Already connected.") }
            return true
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
                Log.w(TAG, "startDiscovery failed", error)
                updateState { it.copy(discovering = false, message = "Could not start discovery: ${friendlyError(error)}") }
                scheduleRoleResume(force = false)
            }
        }
    }

    /** Stops advertising/discovery radios without touching the connection or the desired role. */
    private fun stopRadios() {
        synchronized(stateLock) {
            advertisingStartInFlight = false
            discoveryStartInFlight = false
            advertisingGeneration++
            discoveryGeneration++
        }
        runCatching { connectionsClient.stopAdvertising() }
        runCatching { connectionsClient.stopDiscovery() }
    }

    private fun scheduleRoleResume(force: Boolean) {
        if (desiredRole == Role.NONE || activeEndpoints.isNotEmpty()) return
        resumeForce = resumeForce || force
        operationHandler.removeCallbacks(resumeRunnable)
        val delay = RESUME_DELAY_MS * (resumeAttempt + 1).coerceAtMost(MAX_RESUME_MULTIPLIER)
        resumeAttempt++
        operationHandler.postDelayed(resumeRunnable, delay)
    }

    private fun resumeRole() {
        if (activeEndpoints.isNotEmpty() || pendingConnections.isNotEmpty()) return
        val force = resumeForce
        resumeForce = false
        if (force) {
            stopRadios()
            updateState { it.copy(discovering = false, advertising = false) }
        }
        when (desiredRole) {
            Role.ADVERTISE -> startAdvertising()
            Role.DISCOVER -> startDiscovery()
            Role.NONE -> Unit
        }
    }

    // ---------------------------------------------------------------------------------------
    // Sending a screenshot (phone A)
    // ---------------------------------------------------------------------------------------

    /**
     * Offers [file] to the connected peer. Safe to call repeatedly: a still-unanswered older
     * offer is replaced, a transfer that is already streaming makes the new file wait in a
     * single queue slot, and with no peer connected the file is queued for a reconnect.
     */
    fun sendScreenshot(file: File): Boolean {
        if (!file.isFile || file.length() <= 0L) {
            updateState { it.copy(message = "The screenshot file is unavailable.") }
            return false
        }
        if (file.length() > MAX_TRANSFER_BYTES) {
            updateState { it.copy(message = "The screenshot is too large to send.") }
            return false
        }
        if (authenticatedEndpoints.isEmpty()) {
            synchronized(transferLock) {
                queuedFile = file
                queuedAt = System.currentTimeMillis()
                persistQueuedFile(file)
            }
            updateState {
                it.copy(message = if (activeEndpoints.isEmpty())
                    "No PalmLink device connected. The screenshot is saved and will be offered when a device connects."
                else
                    "Nearby is securing the connection. The screenshot is queued for automatic delivery.")
            }
            return false
        }

        var replaced: Outgoing? = null
        val started: Outgoing? = synchronized(transferLock) {
            val current = outgoing
            if (current != null && (current.phase == OutPhase.SENDING || current.phase == OutPhase.AWAITING_RESULT)) {
                queuedFile = file
                queuedAt = System.currentTimeMillis()
                persistQueuedFile(file)
                null
            } else {
                if (current != null) {
                    outgoing = null
                    replaced = current
                }
                Outgoing(UUID.randomUUID().toString(), file).also { outgoing = it }
            }
        }
        if (started == null) {
            updateState { it.copy(message = "A transfer is still running. The new screenshot is queued.") }
            return true
        }
        replaced?.let { old ->
            old.timeout?.let { operationHandler.removeCallbacks(it) }
            activeEndpoints.firstOrNull()?.let { endpoint ->
                sendMessage(endpoint, TransferMessage.Cancel(old.id))
            }
        }

        updateState { it.copy(sending = true, progress = 0, message = "Preparing screenshot…") }
        ioExecutor.execute {
            val sha = try {
                sha256Of(file)
            } catch (error: Exception) {
                Log.w(TAG, "Could not hash ${file.name}", error)
                null
            }
            operationHandler.post { onHashed(started, sha) }
        }
        return true
    }

    private fun onHashed(out: Outgoing, sha: String?) {
        val stillCurrent = synchronized(transferLock) { outgoing === out && out.phase == OutPhase.HASHING }
        if (!stillCurrent) return

        val endpoint = authenticatedEndpoints.firstOrNull()
        if (endpoint == null) {
            failOutgoing(out, "The device is not authenticated yet. The screenshot remains queued.", requeue = true, notifyPeer = false)
            return
        }
        if (sha == null || !out.file.isFile || out.file.length() <= 0L) {
            failOutgoing(out, "Could not read the screenshot.", requeue = false, notifyPeer = false)
            return
        }

        val offer = TransferMessage.Offer(
            transferId = out.id,
            fileName = safeFileName(out.file.name),
            byteCount = out.file.length(),
            sha256 = sha,
            mimeType = "image/png",
            createdAtMs = System.currentTimeMillis(),
            senderName = displayName(localEndpointName()),
        )
        val expiry = Runnable {
            val waiting = synchronized(transferLock) { outgoing === out && out.phase == OutPhase.OFFERED }
            if (waiting) {
                failOutgoing(
                    out,
                    "The receiver did not accept in time. The screenshot is saved on this phone.",
                    requeue = false,
                    notifyPeer = true,
                )
            }
        }
        synchronized(transferLock) {
            out.phase = OutPhase.OFFERED
            out.timeout = expiry
        }
        operationHandler.postDelayed(expiry, OFFER_TTL_MS)

        sendMessage(
            endpoint,
            offer,
            onSuccess = {
                updateState {
                    it.copy(message = "Screenshot offered. Waiting for the receiver's fist → open palm…", progress = 0)
                }
            },
            onFailure = { error ->
                failOutgoing(out, "Offer failed: ${friendlyError(error)}", requeue = true, notifyPeer = false)
            },
        )
    }

    private fun onAcceptReceived(message: TransferMessage.Accept) {
        val out = synchronized(transferLock) {
            outgoing?.takeIf { it.id == message.transferId && it.phase == OutPhase.OFFERED }?.also {
                it.phase = OutPhase.SENDING
            }
        } ?: return
        out.timeout?.let { operationHandler.removeCallbacks(it) }
        updateState { it.copy(message = "Receiver accepted. Sending screenshot…", progress = 0) }
        sendFilePayload(out)
    }

    private fun sendFilePayload(out: Outgoing) {
        val endpoint = authenticatedEndpoints.firstOrNull()
        if (endpoint == null) {
            failOutgoing(out, "The device is no longer authenticated.", requeue = true, notifyPeer = false)
            return
        }
        if (!out.file.isFile || out.file.length() <= 0L) {
            failOutgoing(out, "The screenshot is no longer available.", requeue = false, notifyPeer = true)
            return
        }
        val payload = try {
            Payload.fromFile(out.file)
        } catch (error: Exception) {
            failOutgoing(out, "Could not open the screenshot: ${friendlyError(error)}", requeue = false, notifyPeer = true)
            return
        }
        out.payloadId = payload.id
        connectionsClient.sendPayload(endpoint, payload)
            .addOnFailureListener { error ->
                failOutgoing(out, "Transfer failed: ${friendlyError(error)}", requeue = true, notifyPeer = false)
            }
    }

    private fun onOutgoingPayloadSent(out: Outgoing) {
        val ok = synchronized(transferLock) {
            if (outgoing !== out) {
                false
            } else {
                out.phase = OutPhase.AWAITING_RESULT
                true
            }
        }
        if (!ok) return
        updateState { it.copy(progress = 100, message = "Sent. Waiting for the receiver to verify…") }
        val expiry = Runnable {
            completeOutgoing(out, "Screenshot sent, but the receiver did not confirm it.")
        }
        synchronized(transferLock) { out.timeout = expiry }
        operationHandler.postDelayed(expiry, RESULT_TIMEOUT_MS)
    }

    private fun onResultReceived(message: TransferMessage.Result) {
        val out = synchronized(transferLock) {
            outgoing?.takeIf {
                it.id == message.transferId &&
                    (it.phase == OutPhase.SENDING || it.phase == OutPhase.AWAITING_RESULT)
            }
        } ?: return
        if (message.ok) {
            completeOutgoing(out, "Screenshot delivered.")
        } else {
            completeOutgoing(out, "The receiver rejected the screenshot: ${message.detail.ifBlank { "verification failed" }}")
        }
    }

    private fun onRejectReceived(message: TransferMessage.Reject) {
        val out = synchronized(transferLock) { outgoing?.takeIf { it.id == message.transferId } } ?: return
        failOutgoing(out, "The receiver declined: ${message.reason}", requeue = false, notifyPeer = false)
    }

    private fun completeOutgoing(out: Outgoing, message: String) {
        val owned = synchronized(transferLock) {
            if (outgoing !== out) {
                false
            } else {
                outgoing = null
                true
            }
        }
        if (!owned) return
        out.timeout?.let { operationHandler.removeCallbacks(it) }
        updateState { it.copy(sending = false, progress = 0, message = message) }
        flushQueued()
    }

    private fun failOutgoing(out: Outgoing, message: String, requeue: Boolean, notifyPeer: Boolean) {
        val owned = synchronized(transferLock) {
            if (outgoing !== out) {
                false
            } else {
                outgoing = null
                if (requeue && queuedFile == null) {
                    queuedFile = out.file
                    queuedAt = System.currentTimeMillis()
                    persistQueuedFile(out.file)
                }
                true
            }
        }
        if (!owned) return
        out.timeout?.let { operationHandler.removeCallbacks(it) }
        if (out.payloadId != -1L) runCatching { connectionsClient.cancelPayload(out.payloadId) }
        if (notifyPeer) {
            activeEndpoints.firstOrNull()?.let { sendMessage(it, TransferMessage.Cancel(out.id)) }
        }
        Log.w(TAG, "Outgoing transfer ${out.id} failed: $message")
        updateState { it.copy(sending = false, progress = 0, message = message) }
    }

    /** Offers a screenshot that was captured while no peer was connected (if still fresh). */
    private fun flushQueued() {
        if (authenticatedEndpoints.isEmpty()) return
        val (file, at) = synchronized(transferLock) {
            val pair = Pair(queuedFile, queuedAt)
            queuedFile = null
            pair
        }
        clearQueuedPersistence()
        if (file == null) return
        if (System.currentTimeMillis() - at > QUEUE_TTL_MS) {
            updateState { it.copy(message = "A saved screenshot was too old to send automatically.") }
            return
        }
        sendScreenshot(file)
    }

    // ---------------------------------------------------------------------------------------
    // Receiving a screenshot (phone B)
    // ---------------------------------------------------------------------------------------

    /**
     * Called when the user performs the receive gesture (closed fist → open palm).
     * With an offer waiting it accepts immediately. Without one, the authorization is
     * remembered for a short window so a gesture that slightly precedes the offer is not lost.
     */
    fun authorizeReceive(): Boolean {
        if (state.pendingOffer != null) return acceptPendingOffer()
        if (authenticatedEndpoints.isEmpty()) return false
        authorizedUntil = SystemClock.elapsedRealtime() + AUTHORIZATION_WINDOW_MS
        updateState { it.copy(message = "Receive gesture noted. Waiting for a screenshot…") }
        return false
    }

    fun acceptPendingOffer(): Boolean {
        val endpoint = authenticatedEndpoints.firstOrNull()
        if (endpoint == null) {
            updateState { it.copy(message = "Secure the Nearby connection first.") }
            return false
        }
        val offer = state.pendingOffer ?: return false
        val inc = synchronized(transferLock) {
            if (incoming != null) null else Incoming(offer).also { incoming = it }
        } ?: return true

        offerExpiry?.let { operationHandler.removeCallbacks(it) }
        offerExpiry = null
        authorizedUntil = 0L
        updateState {
            it.copy(pendingOffer = null, receiving = true, progress = 0, message = "Accepted. Receiving the screenshot…")
        }
        touchIncoming(inc)
        sendMessage(
            endpoint,
            TransferMessage.Accept(offer.transferId),
            onFailure = { error ->
                failIncoming(inc, "Could not accept: ${friendlyError(error)}", notifySender = false)
            },
        )
        return true
    }

    private fun onOfferReceived(endpointId: String, offer: TransferMessage.Offer) {
        if (!authenticatedEndpoints.contains(endpointId)) return
        if (offer.byteCount !in 1L..MAX_TRANSFER_BYTES) {
            sendMessage(endpointId, TransferMessage.Reject(offer.transferId, "Too large or invalid"))
            updateState { it.copy(message = "The incoming screenshot is too large or invalid.") }
            return
        }
        if (receivedFileFor(offer.transferId).exists()) {
            // Duplicate transfer id: never create a second copy.
            sendMessage(endpointId, TransferMessage.Result(offer.transferId, true, "Already received"))
            return
        }
        if (synchronized(transferLock) { incoming != null }) {
            sendMessage(endpointId, TransferMessage.Reject(offer.transferId, "Receiver is busy"))
            return
        }
        if (state.pendingOffer?.transferId == offer.transferId) return

        offerExpiry?.let { operationHandler.removeCallbacks(it) }
        val expiry = Runnable {
            if (state.pendingOffer?.transferId == offer.transferId) {
                updateState { it.copy(pendingOffer = null, message = "The screenshot offer expired.") }
                activeEndpoints.firstOrNull()?.let {
                    sendMessage(it, TransferMessage.Reject(offer.transferId, "Offer expired"))
                }
            }
        }
        offerExpiry = expiry
        operationHandler.postDelayed(expiry, OFFER_TTL_MS)

        updateState {
            it.copy(
                pendingOffer = offer,
                progress = 0,
                message = "Screenshot incoming. Make a fist, then open your palm to receive it.",
            )
        }
        if (SystemClock.elapsedRealtime() < authorizedUntil) {
            // The receive gesture happened a moment before the offer arrived.
            authorizedUntil = 0L
            acceptPendingOffer()
        }
    }

    private fun onCancelReceived(message: TransferMessage.Cancel) {
        if (state.pendingOffer?.transferId == message.transferId) {
            offerExpiry?.let { operationHandler.removeCallbacks(it) }
            offerExpiry = null
            updateState { it.copy(pendingOffer = null, message = "The sender cancelled the screenshot.") }
        }
        val inc = synchronized(transferLock) { incoming?.takeIf { it.offer.transferId == message.transferId } }
        if (inc != null) failIncoming(inc, "The sender cancelled the transfer.", notifySender = false)
    }

    private fun onIncomingPayload(endpointId: String, payload: Payload) {
        if (!authenticatedEndpoints.contains(endpointId)) {
            runCatching { connectionsClient.cancelPayload(payload.id) }
            return
        }
        val inc = synchronized(transferLock) { incoming }
        if (inc == null || inc.payloadId != -1L) {
            // Nobody authorized this payload (no accepted offer, or a second payload): drop it.
            runCatching { connectionsClient.cancelPayload(payload.id) }
            return
        }
        val uri = payload.asFile()?.asUri()
        if (uri == null) {
            failIncoming(inc, "Incoming screenshot payload is unavailable.", notifySender = true)
            return
        }
        inc.payloadId = payload.id
        inc.uri = uri
        touchIncoming(inc)
        updateState { it.copy(message = "Screenshot is arriving…", progress = 0) }
    }

    private fun touchIncoming(inc: Incoming) {
        inc.timeout?.let { operationHandler.removeCallbacks(it) }
        val timeout = Runnable {
            failIncoming(inc, "The screenshot did not arrive in time.", notifySender = true)
        }
        inc.timeout = timeout
        operationHandler.postDelayed(timeout, INCOMING_IDLE_TIMEOUT_MS)
    }

    private fun failIncoming(inc: Incoming, message: String, notifySender: Boolean) {
        val owned = synchronized(transferLock) {
            if (incoming !== inc) {
                false
            } else {
                incoming = null
                true
            }
        }
        if (!owned) return
        inc.timeout?.let { operationHandler.removeCallbacks(it) }
        if (inc.payloadId != -1L) runCatching { connectionsClient.cancelPayload(inc.payloadId) }
        discardPayloadUri(inc.uri)
        if (notifySender) {
            activeEndpoints.firstOrNull()?.let {
                sendMessage(it, TransferMessage.Result(inc.offer.transferId, false, message))
            }
        }
        Log.w(TAG, "Incoming transfer ${inc.offer.transferId} failed: $message")
        updateState { it.copy(receiving = false, progress = 0, message = message) }
    }

    private fun finishIncoming(inc: Incoming) {
        inc.timeout?.let { operationHandler.removeCallbacks(it) }
        val offer = inc.offer
        val uri = inc.uri
        updateState { it.copy(progress = 100, message = "Verifying screenshot…") }
        ioExecutor.execute {
            val outcome = verifyAndStore(offer, uri)
            discardPayloadUri(uri)
            operationHandler.post {
                val owned = synchronized(transferLock) {
                    if (incoming !== inc) {
                        false
                    } else {
                        incoming = null
                        true
                    }
                }
                if (!owned) {
                    // The transfer was cancelled while it was being verified.
                    outcome.file?.takeIf { it.exists() && state.lastReceivedFile != it }?.delete()
                    return@post
                }
                val saved = outcome.file
                if (saved != null) {
                    updateState {
                        it.copy(
                            receiving = false,
                            progress = 100,
                            message = "Screenshot received and saved.",
                            lastReceivedFile = saved,
                        )
                    }
                    activeEndpoints.firstOrNull()?.let {
                        sendMessage(it, TransferMessage.Result(offer.transferId, true))
                    }
                } else {
                    val reason = outcome.error ?: "verification failed"
                    Log.w(TAG, "Rejected incoming ${offer.transferId}: $reason")
                    updateState {
                        it.copy(receiving = false, progress = 0, message = "The received screenshot was rejected: $reason")
                    }
                    activeEndpoints.firstOrNull()?.let {
                        sendMessage(it, TransferMessage.Result(offer.transferId, false, reason))
                    }
                }
            }
        }
    }

    /** Streams the payload to a temp file while hashing it, then publishes it atomically. */
    private fun verifyAndStore(offer: TransferMessage.Offer, uri: Uri?): StoreResult {
        if (uri == null) return StoreResult(null, "payload unavailable")
        val dir = File(appContext.filesDir, CAPTURE_DIR).apply { mkdirs() }
        val temp = File(dir, "incoming-${offer.transferId}.part")
        val finalFile = receivedFileFor(offer.transferId)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            val input = appContext.contentResolver.openInputStream(uri) ?: return StoreResult(null, "cannot open payload")
            input.use { stream ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > offer.byteCount || total > MAX_TRANSFER_BYTES) {
                            return StoreResult(null, "larger than offered")
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (total != offer.byteCount) return StoreResult(null, "size mismatch ($total of ${offer.byteCount} bytes)")
            if (!digest.digest().toHex().equals(offer.sha256, ignoreCase = true)) {
                return StoreResult(null, "checksum mismatch")
            }
            if (!looksLikePng(temp)) return StoreResult(null, "not a PNG image")
            if (finalFile.exists()) return StoreResult(finalFile, null)
            if (!temp.renameTo(finalFile)) return StoreResult(null, "could not save the file")
            return StoreResult(finalFile, null)
        } catch (error: Exception) {
            Log.w(TAG, "verifyAndStore failed", error)
            return StoreResult(null, error.message ?: error.javaClass.simpleName)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun discardPayloadUri(uri: Uri?) {
        if (uri == null) return
        // Remove the copy Nearby placed in shared storage; ours is the only one that matters.
        runCatching { appContext.contentResolver.delete(uri, null, null) }
    }

    private fun looksLikePng(file: File): Boolean = try {
        FileInputStream(file).use { input ->
            val header = ByteArray(8)
            var read = 0
            while (read < header.size) {
                val n = input.read(header, read, header.size - read)
                if (n < 0) break
                read += n
            }
            read == header.size && header.contentEquals(PNG_SIGNATURE)
        }
    } catch (error: Exception) {
        false
    }

    private fun cleanupStalePartFiles() {
        runCatching {
            File(appContext.filesDir, CAPTURE_DIR).listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
        }
    }

    private fun receivedFileFor(transferId: String): File =
        File(File(appContext.filesDir, CAPTURE_DIR), "received-$transferId.png")

    // ---------------------------------------------------------------------------------------
    // Callbacks
    // ---------------------------------------------------------------------------------------

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (!hasNearbyPermissions()) return
            if (!state.discovering) return
            if (activeEndpoints.isNotEmpty()) return
            if (activeEndpoints.contains(endpointId) || !discoveredEndpoints.add(endpointId) || !pendingConnections.add(endpointId)) return

            updateState {
                it.copy(message = "Found ${displayName(info.endpointName)}. Connecting…", endpointName = displayName(info.endpointName))
            }
            connectionsClient.requestConnection(localEndpointName(), endpointId, connectionLifecycleCallback)
                .addOnFailureListener { error ->
                    pendingConnections.remove(endpointId)
                    discoveredEndpoints.remove(endpointId)
                    updateState { it.copy(message = "Connection request failed: ${friendlyError(error)}") }
                    scheduleRoleResume(force = true)
                }
        }

        override fun onEndpointLost(endpointId: String) {
            activeEndpoints.remove(endpointId)
            authenticatedEndpoints.remove(endpointId)
            pairingTokensSent.remove(endpointId)
            pairingPeerTokens.remove(endpointId)
            pairingPeerIds.remove(endpointId)
            authLocalChallenges.remove(endpointId)
            discoveredEndpoints.remove(endpointId)
            pendingConnections.remove(endpointId)
            pendingNames.remove(endpointId)
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
            if (activeEndpoints.isNotEmpty() && !activeEndpoints.contains(endpointId)) {
                // One authoritative peer connection: refuse additional ones.
                runCatching { connectionsClient.rejectConnection(endpointId) }
                return
            }
            pendingConnections.add(endpointId)
            pendingNames[endpointId] = connectionInfo.endpointName
            val display = displayName(connectionInfo.endpointName)
            if (looksLikeTrustedEndpointName(connectionInfo.endpointName)) {
                // The endpoint name is only a hint. Cryptographic authentication still happens
                // after the Nearby transport is connected.
                updateState {
                    it.copy(
                        endpointName = display,
                        authDigits = null,
                        pendingConnectionEndpointId = null,
                        message = "Reconnecting to $display…",
                    )
                }
                acceptConnection(endpointId)
            } else {
                updateState {
                    it.copy(
                        endpointName = display,
                        authDigits = connectionInfo.authenticationDigits,
                        pendingConnectionEndpointId = endpointId,
                        message = "Compare the digits on both devices, then confirm the connection.",
                    )
                }
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            pendingConnections.remove(endpointId)
            pendingNames.remove(endpointId)
            if (result.status.isSuccess) {
                activeEndpoints += endpointId
                authenticatedEndpoints.remove(endpointId)
                discoveredEndpoints.remove(endpointId)
                resumeAttempt = 0
                operationHandler.removeCallbacks(resumeRunnable)
                // Point-to-point: once connected, the radios used for finding each other can stop.
                stopRadios()
                updateState {
                    it.copy(
                        connected = true,
                        discovering = false,
                        advertising = false,
                        pendingConnectionEndpointId = null,
                        authDigits = null,
                        message = "Nearby connected. Securing the paired device…",
                    )
                }
                val manuallyApproved = pendingUserApprovals.remove(endpointId)
                if (manuallyApproved) beginFirstPairing(endpointId) else beginAuthentication(endpointId)
            } else {
                activeEndpoints.remove(endpointId)
                discoveredEndpoints.remove(endpointId)
                updateState {
                    it.copy(
                        connected = activeEndpoints.isNotEmpty(),
                        pendingConnectionEndpointId = null,
                        authDigits = null,
                        message = "Nearby connection rejected or failed.",
                    )
                }
                if (activeEndpoints.isEmpty()) scheduleRoleResume(force = true)
            }
        }

        override fun onDisconnected(endpointId: String) {
            activeEndpoints.remove(endpointId)
            authenticatedEndpoints.remove(endpointId)
            pairingTokensSent.remove(endpointId)
            pairingPeerTokens.remove(endpointId)
            pairingPeerIds.remove(endpointId)
            authLocalChallenges.remove(endpointId)
            pendingConnections.remove(endpointId)
            discoveredEndpoints.remove(endpointId)
            pendingNames.remove(endpointId)
            clearTransferStateIfNoConnections()
            val reconnecting = desiredRole != Role.NONE && activeEndpoints.isEmpty()
            updateState {
                val isSamePending = it.pendingConnectionEndpointId == endpointId
                it.copy(
                    connected = activeEndpoints.isNotEmpty(),
                    endpointName = if (activeEndpoints.isEmpty()) null else it.endpointName,
                    pendingConnectionEndpointId = if (isSamePending) null else it.pendingConnectionEndpointId,
                    authDigits = if (isSamePending) null else it.authDigits,
                    message = when {
                        activeEndpoints.isNotEmpty() -> it.message
                        reconnecting -> "Disconnected. Reconnecting…"
                        else -> "Disconnected."
                    },
                )
            }
            if (reconnecting) scheduleRoleResume(force = true)
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (!activeEndpoints.contains(endpointId)) return
            try {
                when (payload.type) {
                    Payload.Type.BYTES -> {
                        val message = payload.asBytes()?.let(TransferProtocol::decode)
                        if (message != null) handleMessage(endpointId, message)
                    }
                    Payload.Type.FILE -> onIncomingPayload(endpointId, payload)
                    else -> runCatching { connectionsClient.cancelPayload(payload.id) }
                }
            } catch (error: Exception) {
                Log.e(TAG, "Payload handling failed", error)
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (!activeEndpoints.contains(endpointId)) return
            try {
                val out = synchronized(transferLock) { outgoing?.takeIf { it.payloadId == update.payloadId } }
                val inc = synchronized(transferLock) { incoming?.takeIf { it.payloadId == update.payloadId } }
                if (out == null && inc == null) return

                when (update.status) {
                    PayloadTransferUpdate.Status.IN_PROGRESS -> {
                        val percent = if (update.totalBytes > 0L) {
                            ((update.bytesTransferred * 100L) / update.totalBytes).toInt().coerceIn(0, 100)
                        } else 0
                        updateState {
                            it.copy(progress = percent, message = if (out != null) "Sending… $percent%" else "Receiving… $percent%")
                        }
                        if (inc != null) touchIncoming(inc)
                    }
                    PayloadTransferUpdate.Status.SUCCESS -> {
                        if (out != null) onOutgoingPayloadSent(out)
                        if (inc != null) finishIncoming(inc)
                    }
                    PayloadTransferUpdate.Status.FAILURE,
                    PayloadTransferUpdate.Status.CANCELED -> {
                        if (out != null) failOutgoing(out, "Transfer failed.", requeue = false, notifyPeer = false)
                        if (inc != null) failIncoming(inc, "Transfer failed. No partial file was accepted.", notifySender = true)
                    }
                    else -> Unit
                }
            } catch (error: Exception) {
                Log.e(TAG, "Transfer update handling failed", error)
            }
        }
    }

    private fun handleMessage(endpointId: String, message: TransferMessage) {
        when (message) {
            is TransferMessage.PairSetup -> onPairSetup(endpointId, message)
            is TransferMessage.AuthHello -> onAuthHello(endpointId, message)
            is TransferMessage.AuthResponse -> onAuthResponse(endpointId, message)
            is TransferMessage.Ping -> Unit
            else -> {
                if (!authenticatedEndpoints.contains(endpointId)) return
                when (message) {
                    is TransferMessage.Offer -> onOfferReceived(endpointId, message)
                    is TransferMessage.Accept -> onAcceptReceived(message)
                    is TransferMessage.Reject -> onRejectReceived(message)
                    is TransferMessage.Cancel -> onCancelReceived(message)
                    is TransferMessage.Result -> onResultReceived(message)
                    else -> Unit
                }
            }
        }
    }

    private fun sendMessage(
        endpointId: String,
        message: TransferMessage,
        onSuccess: (() -> Unit)? = null,
        onFailure: ((Exception) -> Unit)? = null,
    ) {
        val handshakeMessage = message is TransferMessage.PairSetup ||
            message is TransferMessage.AuthHello || message is TransferMessage.AuthResponse || message is TransferMessage.Ping
        if (!handshakeMessage && !authenticatedEndpoints.contains(endpointId)) {
            onFailure?.invoke(IllegalStateException("Nearby peer is not authenticated"))
            return
        }
        connectionsClient.sendPayload(endpointId, Payload.fromBytes(TransferProtocol.encode(message)))
            .addOnSuccessListener { onSuccess?.invoke() }
            .addOnFailureListener { error ->
                Log.w(TAG, "Could not send ${message.javaClass.simpleName}", error)
                onFailure?.invoke(error)
            }
    }

    private fun clearTransferStateIfNoConnections() {
        if (activeEndpoints.isNotEmpty()) return
        var out: Outgoing? = null
        var inc: Incoming? = null
        synchronized(transferLock) {
            out = outgoing
            inc = incoming
            outgoing = null
            incoming = null
            val o = out
            // An unfinished upload is kept so it can be offered again after a reconnect.
            if (o != null && queuedFile == null && o.phase != OutPhase.AWAITING_RESULT) {
                queuedFile = o.file
                queuedAt = System.currentTimeMillis()
                persistQueuedFile(o.file)
            }
        }
        val finishedOut = out
        val finishedInc = inc
        finishedOut?.timeout?.let { operationHandler.removeCallbacks(it) }
        finishedInc?.timeout?.let { operationHandler.removeCallbacks(it) }
        offerExpiry?.let { operationHandler.removeCallbacks(it) }
        offerExpiry = null
        authorizedUntil = 0L
        discardPayloadUri(finishedInc?.uri)
        updateState { it.copy(pendingOffer = null, progress = 0, sending = false, receiving = false) }
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private fun hasNearbyPermissions(): Boolean {
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
        return permissions.all { ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED }
    }

    /** Human-readable endpoint name with a short persistent identity hint. The hint is not a credential. */
    private fun localEndpointName(): String {
        val model = Build.MODEL.orEmpty().replace("#", "").take(18).ifBlank { "PalmLink" }
        // Include only a short persistent identity hint in the human-readable endpoint name.
        // It is NOT used as authentication; the HMAC handshake below is the credential.
        return "$model#${localDeviceId().take(16)}"
    }

    private fun localDeviceId(): String = prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().replace("-", "")
        .also { prefs.edit().putString(KEY_DEVICE_ID, it).apply() }

    private fun displayName(raw: String): String = raw.substringBefore('#').ifBlank { "PalmLink device" }

    private fun trustedSecrets(): Map<String, String> {
        val entries = prefs.getStringSet(KEY_TRUSTED_SECRETS, emptySet()).orEmpty()
        return entries.mapNotNull { entry ->
            val separator = entry.indexOf(':')
            if (separator <= 0) return@mapNotNull null
            val id = entry.substring(0, separator)
            val secret = entry.substring(separator + 1)
            if (TransferProtocol.isValidTransferId(id) || (id.length in 16..80 && id.matches(Regex("^[A-Za-z0-9-]+$")))) id to secret else null
        }.toMap()
    }

    private fun secretFor(deviceId: String): String? = trustedSecrets()[deviceId]

    private fun looksLikeTrustedEndpointName(rawName: String): Boolean {
        // This is only a routing hint. Cryptographic authentication below remains mandatory.
        val suffix = rawName.substringAfter('#', missingDelimiterValue = "")
        return suffix.length >= 12 && trustedSecrets().keys.any { it.startsWith(suffix, ignoreCase = true) }
    }

    private fun trustPeer(deviceId: String, sharedSecret: String) {
        val entries = HashSet(prefs.getStringSet(KEY_TRUSTED_SECRETS, emptySet()).orEmpty())
        entries.removeAll { it.substringBefore(':') == deviceId }
        entries.add("$deviceId:$sharedSecret")
        prefs.edit().putStringSet(KEY_TRUSTED_SECRETS, entries).remove(KEY_TRUSTED_LEGACY).apply()
    }

    private fun beginFirstPairing(endpointId: String) {
        val token = pairingTokensSent[endpointId] ?: PairingCrypto.randomToken().also {
            pairingTokensSent[endpointId] = it
        }
        sendMessage(endpointId, TransferMessage.PairSetup(localDeviceId(), token))
        updateState { it.copy(message = "Pairing the verified device…") }
    }

    private fun beginAuthentication(endpointId: String) {
        val nonce = PairingCrypto.randomNonce()
        authLocalChallenges[endpointId] = nonce
        sendMessage(endpointId, TransferMessage.AuthHello(localDeviceId(), nonce))
    }

    private fun onPairSetup(endpointId: String, message: TransferMessage.PairSetup) {
        val existing = secretFor(message.deviceId)
        if (existing != null) {
            // A trusted peer reconnecting should use the auth handshake instead. A fresh PAIR is
            // ignored rather than overwriting an established secret.
            updateState { it.copy(message = "Ignoring an unexpected re-pair request.") }
            beginAuthentication(endpointId)
            return
        }
        pairingPeerIds[endpointId] = message.deviceId
        pairingPeerTokens[endpointId] = message.token

        val localToken = pairingTokensSent[endpointId] ?: return
        val shared = PairingCrypto.deriveSharedSecret(
            localDeviceId(), localToken, message.deviceId, message.token,
        )
        trustPeer(message.deviceId, shared)
        authenticatedEndpoints += endpointId
        pairingTokensSent.remove(endpointId)
        pairingPeerTokens.remove(endpointId)
        pairingPeerIds.remove(endpointId)
        updateState { it.copy(message = "Nearby connection ready.") }
        flushQueued()
    }

    private fun onAuthHello(endpointId: String, message: TransferMessage.AuthHello) {
        val secret = secretFor(message.deviceId)
        if (secret == null) {
            // Unknown device. Do not authenticate or accept transfer data. The first pairing must
            // go through Nearby's user-visible authentication digits instead.
            updateState { it.copy(message = "Unknown device. Confirm the pairing digits first.") }
            runCatching { connectionsClient.disconnectFromEndpoint(endpointId) }
            return
        }
        pairingPeerIds[endpointId] = message.deviceId
        val proof = PairingCrypto.proof(secret, "hello", message.deviceId, message.nonce, localDeviceId())
        sendMessage(
            endpointId,
            TransferMessage.AuthResponse(localDeviceId(), message.nonce, proof),
        )
    }

    private fun onAuthResponse(endpointId: String, message: TransferMessage.AuthResponse) {
        val secret = secretFor(message.deviceId) ?: return
        val challenge = authLocalChallenges[endpointId] ?: return
        if (challenge != message.challenge) return
        val expected = PairingCrypto.proof(secret, "hello", localDeviceId(), challenge, message.deviceId)
        if (!PairingCrypto.constantTimeEquals(expected, message.proof)) {
            Log.w(TAG, "Nearby authentication failed for endpoint $endpointId")
            runCatching { connectionsClient.disconnectFromEndpoint(endpointId) }
            return
        }
        authenticatedEndpoints += endpointId
        authLocalChallenges.remove(endpointId)
        updateState { it.copy(message = "Nearby connection ready.") }
        flushQueued()
    }

    private fun persistQueuedFile(file: File) {
        // Use wall clock for persisted age because elapsedRealtime resets after a reboot.
        prefs.edit().putString(KEY_QUEUED_FILE, file.absolutePath).putLong(KEY_QUEUED_AT, System.currentTimeMillis()).apply()
    }

    private fun clearQueuedPersistence() {
        prefs.edit().remove(KEY_QUEUED_FILE).remove(KEY_QUEUED_AT).apply()
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String {
        val builder = StringBuilder(size * 2)
        for (byte in this) {
            val value = byte.toInt() and 0xff
            builder.append(HEX[value ushr 4]).append(HEX[value and 0x0f])
        }
        return builder.toString()
    }

    private fun safeFileName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "screenshot.png" }

    private fun updateState(transform: (State) -> State) {
        val snapshot = synchronized(stateLock) {
            state = transform(state)
            state
        }
        stateListeners.forEach { listener ->
            try {
                listener(snapshot)
            } catch (error: Exception) {
                Log.w(TAG, "State listener failed", error)
            }
        }
    }

    private fun friendlyError(error: Exception): String =
        error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

    private fun statusCode(error: Exception): Int? = (error as? ApiException)?.statusCode

    private fun isAlreadyAdvertising(error: Exception): Boolean =
        statusCode(error) == STATUS_ALREADY_ADVERTISING || error.message?.contains("ALREADY_ADVERTISING", true) == true

    private fun isAlreadyDiscovering(error: Exception): Boolean =
        statusCode(error) == STATUS_ALREADY_DISCOVERING || error.message?.contains("ALREADY_DISCOVERING", true) == true

    companion object {
        private const val TAG = "PalmLink"
        private const val PREFS = "palmlink_nearby"
        private const val KEY_TRUSTED_SECRETS = "trusted_peer_secrets_v2"
        private const val KEY_TRUSTED_LEGACY = "trusted_peers"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DESIRED_ROLE = "desired_role"
        private const val KEY_QUEUED_FILE = "queued_file"
        private const val KEY_QUEUED_AT = "queued_at"
        private const val ROLE_ADVERTISE = "advertise"
        private const val ROLE_DISCOVER = "discover"
        private const val CAPTURE_DIR = "captures"

        private const val STATUS_ALREADY_ADVERTISING = 8001
        private const val STATUS_ALREADY_DISCOVERING = 8002
        private const val MAX_TRANSFER_BYTES = 50L * 1024L * 1024L

        /** How long a screenshot offer stays valid on both phones. */
        private const val OFFER_TTL_MS = 90_000L
        /** A receive gesture performed this shortly before an offer arrives still authorizes it. */
        private const val AUTHORIZATION_WINDOW_MS = 8_000L
        private const val INCOMING_IDLE_TIMEOUT_MS = 45_000L
        private const val RESULT_TIMEOUT_MS = 20_000L
        /** A screenshot captured with no peer is still offered on reconnect for this long. */
        private const val QUEUE_TTL_MS = 120_000L
        private const val RESUME_DELAY_MS = 3_000L
        private const val MAX_RESUME_MULTIPLIER = 10

        private val HEX = "0123456789abcdef".toCharArray()
        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
    }
}
