package com.samin.notouchgesture

import com.samin.notouchgesture.gesture.GestureRecognizerController
import com.samin.notouchgesture.gesture.GestureStateMachine
import com.samin.notouchgesture.nearby.PairingCrypto
import com.samin.notouchgesture.nearby.TransferMessage
import com.samin.notouchgesture.nearby.TransferProtocol
import org.junit.Test

class LogicSmokeTest {
    @Test fun captureSequenceWorksTest() = captureSequenceWorks()
    @Test fun lowConfidenceDoesNotTriggerTest() = lowConfidenceDoesNotTrigger()
    @Test fun triggerNeedsNeutralReleaseTest() = triggerNeedsNeutralRelease()
    @Test fun sendSequenceWorksTest() = sendSequenceWorks()
    @Test fun receiveGestureIsFistThenPalmTest() = receiveGestureIsFistThenPalm()
    @Test fun backgroundCaptureWorksTest() = backgroundCaptureWorks()
    @Test fun backgroundFistThenPalmAuthorizesReceiveTest() = backgroundFistThenPalmAuthorizesReceive()
    @Test fun backgroundOpenFirstWhilePendingResetsTest() = backgroundOpenFirstWhilePendingResets()
    @Test fun staleArmingExpiresTest() = staleArmingExpires()
    @Test fun heldGestureTriggersOnceTest() = heldGestureTriggersOnce()
    @Test fun defaultFastSequenceWorksTest() = defaultFastSequenceWorks()
    @Test fun briefConfidenceDipDoesNotRestartGestureTest() = briefConfidenceDipDoesNotRestartGesture()
    @Test fun longConfidenceLossResetsGestureTest() = longConfidenceLossResetsGesture()
    @Test fun wrongOrderDoesNotTriggerTest() = wrongOrderDoesNotTrigger()
    @Test fun protocolRoundTripWorksTest() = protocolRoundTripWorks()
    @Test fun protocolControlMessagesRoundTripTest() = protocolControlMessagesRoundTrip()
    @Test fun protocolPreviewRoundTripTest() = protocolPreviewRoundTrip()
    @Test fun protocolPairingMessagesRoundTripTest() = protocolPairingMessagesRoundTrip()
    @Test fun pairingCryptoRoundTripTest() = pairingCryptoRoundTrip()
    @Test fun invalidMessagesAreRejectedTest() = invalidMessagesAreRejected()
    @Test fun missingGestureResultHasNoConfidenceTest() {
        check(GestureRecognizerController.GestureUiState().confidence == null)
    }
}

private typealias Sm = GestureStateMachine
private typealias L = GestureStateMachine.Label
private typealias A = GestureStateMachine.Action

private fun captureSequenceWorks() {
    val sm = Sm(GestureStateMachine.GestureMode.CAPTURE, stableMs = 100)
    check(sm.update(L.OPEN_PALM, 0.9f, 0L) == A.NONE)
    check(sm.update(L.OPEN_PALM, 0.9f, 100L) == A.ARMED)
    check(sm.update(L.CLOSED_FIST, 0.9f, 200L) == A.NONE)
    check(sm.update(L.CLOSED_FIST, 0.9f, 300L) == A.CAPTURE)
}

private fun lowConfidenceDoesNotTrigger() {
    val sm = Sm(GestureStateMachine.GestureMode.CAPTURE, stableMs = 100)
    sm.update(L.OPEN_PALM, 0.4f, 0L)
    check(sm.update(L.CLOSED_FIST, 0.4f, 500L) == A.NONE)
}

private fun triggerNeedsNeutralRelease() {
    val sm = Sm(GestureStateMachine.GestureMode.CAPTURE, stableMs = 50, neutralRearmMs = 50)
    sm.update(L.OPEN_PALM, 1f, 0L)
    sm.update(L.OPEN_PALM, 1f, 50L)
    sm.update(L.CLOSED_FIST, 1f, 100L)
    check(sm.update(L.CLOSED_FIST, 1f, 150L) == A.CAPTURE)
    check(sm.update(L.CLOSED_FIST, 1f, 400L) == A.NONE)
    sm.update(L.NONE, 0f, 450L)
    sm.update(L.NONE, 0f, 500L)
    check(sm.update(L.OPEN_PALM, 1f, 1150L) == A.NONE)
    check(sm.update(L.OPEN_PALM, 1f, 1250L) == A.ARMED)
}

private fun sendSequenceWorks() {
    val sm = Sm(GestureStateMachine.GestureMode.SEND, stableMs = 100, cooldownMs = 200)
    check(sm.update(L.CLOSED_FIST, 0.9f, 0L) == A.NONE)
    check(sm.update(L.CLOSED_FIST, 0.9f, 100L) == A.ARMED)
    check(sm.update(L.OPEN_PALM, 0.9f, 200L) == A.NONE)
    check(sm.update(L.OPEN_PALM, 0.9f, 300L) == A.SEND_OFFER)
}

private fun receiveGestureIsFistThenPalm() {
    val alone = Sm(GestureStateMachine.GestureMode.RECEIVE, stableMs = 100)
    check(alone.update(L.OPEN_PALM, 0.9f, 0L) == A.NONE)
    check(alone.update(L.OPEN_PALM, 0.9f, 100L) == A.NONE)
    check(alone.update(L.OPEN_PALM, 0.9f, 400L) == A.NONE)

    val sm = Sm(GestureStateMachine.GestureMode.RECEIVE, stableMs = 100)
    check(sm.update(L.CLOSED_FIST, 0.9f, 0L) == A.NONE)
    check(sm.update(L.CLOSED_FIST, 0.9f, 100L) == A.ARMED)
    check(sm.update(L.OPEN_PALM, 0.9f, 200L) == A.NONE)
    check(sm.update(L.OPEN_PALM, 0.9f, 300L) == A.RECEIVE_ACCEPT)
}

private fun backgroundCaptureWorks() {
    val capture = Sm(GestureStateMachine.GestureMode.BACKGROUND, stableMs = 100)
    check(capture.update(L.OPEN_PALM, 0.9f, 0L) == A.NONE)
    check(capture.update(L.OPEN_PALM, 0.9f, 100L) == A.ARMED)
    check(capture.update(L.CLOSED_FIST, 0.9f, 200L) == A.NONE)
    check(capture.update(L.CLOSED_FIST, 0.9f, 300L) == A.CAPTURE)
}

private fun backgroundFistThenPalmAuthorizesReceive() {
    val sm = Sm(GestureStateMachine.GestureMode.BACKGROUND, stableMs = 100)
    check(sm.update(L.CLOSED_FIST, 0.9f, 0L, receivePending = true) == A.NONE)
    check(sm.update(L.CLOSED_FIST, 0.9f, 100L, receivePending = true) == A.ARMED)
    check(sm.update(L.OPEN_PALM, 0.9f, 200L, receivePending = true) == A.NONE)
    check(sm.update(L.OPEN_PALM, 0.9f, 300L, receivePending = true) == A.RECEIVE_ACCEPT)
    check(sm.update(L.OPEN_PALM, 0.9f, 2000L, receivePending = true) == A.NONE)
}

private fun backgroundOpenFirstWhilePendingResets() {
    val sm = Sm(GestureStateMachine.GestureMode.BACKGROUND, stableMs = 100)
    check(sm.update(L.OPEN_PALM, 0.9f, 0L, receivePending = true) == A.NONE)
    check(sm.update(L.OPEN_PALM, 0.9f, 100L, receivePending = true) == A.ARMED)
    check(sm.update(L.CLOSED_FIST, 0.9f, 200L, receivePending = true) == A.NONE)
    check(sm.update(L.CLOSED_FIST, 0.9f, 300L, receivePending = true) == A.NONE)
    check(sm.update(L.OPEN_PALM, 0.9f, 500L, receivePending = true) == A.NONE)
    check(sm.update(L.CLOSED_FIST, 0.9f, 700L, receivePending = true) == A.NONE)
    check(sm.update(L.CLOSED_FIST, 0.9f, 800L, receivePending = true) == A.ARMED)
    check(sm.update(L.OPEN_PALM, 0.9f, 900L, receivePending = true) == A.NONE)
    check(sm.update(L.OPEN_PALM, 0.9f, 1000L, receivePending = true) == A.RECEIVE_ACCEPT)
}

private fun staleArmingExpires() {
    val sm = Sm(GestureStateMachine.GestureMode.BACKGROUND, stableMs = 100)
    check(sm.update(L.OPEN_PALM, 0.9f, 0L) == A.NONE)
    check(sm.update(L.OPEN_PALM, 0.9f, 100L) == A.ARMED)
    // The production arm timeout is 2.5s, comfortably above the short two-step
    // gesture window. Advance beyond that timeout
    // before starting the next gesture sequence.
    check(sm.update(L.NONE, 0f, 7000L) == A.NONE)
    check(sm.update(L.CLOSED_FIST, 0.9f, 7100L) == A.NONE)
    check(sm.update(L.CLOSED_FIST, 0.9f, 7200L) == A.ARMED)
}

private fun heldGestureTriggersOnce() {
    val sm = Sm(GestureStateMachine.GestureMode.BACKGROUND, stableMs = 100)
    var captures = 0
    var t = 0L
    while (t < 600L) {
        if (sm.update(L.OPEN_PALM, 0.9f, t) == A.CAPTURE) captures++
        t += 50L
    }
    while (t < 5000L) {
        if (sm.update(L.CLOSED_FIST, 0.9f, t) == A.CAPTURE) captures++
        t += 50L
    }
    check(captures == 1)
}

private fun defaultFastSequenceWorks() {
    val sm = Sm(GestureStateMachine.GestureMode.BACKGROUND)

    // Production defaults are intentionally quick: roughly 180 ms per gesture step.
    check(sm.update(L.OPEN_PALM, 0.9f, 0L) == A.NONE)
    check(sm.update(L.OPEN_PALM, 0.9f, 179L) == A.NONE)
    check(sm.update(L.OPEN_PALM, 0.9f, 180L) == A.ARMED)

    check(sm.update(L.CLOSED_FIST, 0.9f, 181L) == A.NONE)
    check(sm.update(L.CLOSED_FIST, 0.9f, 359L) == A.NONE)
    check(sm.update(L.CLOSED_FIST, 0.9f, 361L) == A.CAPTURE)
}

private fun briefConfidenceDipDoesNotRestartGesture() {
    val sm = Sm(GestureStateMachine.GestureMode.CAPTURE, stableMs = 180, confidenceGraceMs = 140)
    check(sm.update(L.OPEN_PALM, 0.9f, 0L) == A.NONE)
    check(sm.update(L.OPEN_PALM, 0.9f, 100L) == A.NONE)
    // A brief weak frame should not restart the stable-window timer.
    check(sm.update(L.OPEN_PALM, 0.40f, 150L) == A.NONE)
    check(sm.update(L.OPEN_PALM, 0.9f, 180L) == A.ARMED)
}

private fun longConfidenceLossResetsGesture() {
    val sm = Sm(GestureStateMachine.GestureMode.CAPTURE, stableMs = 180, confidenceGraceMs = 140)
    check(sm.update(L.OPEN_PALM, 0.9f, 0L) == A.NONE)
    check(sm.update(L.OPEN_PALM, 0.9f, 100L) == A.NONE)

    // After the grace window expires, the previous stable time cannot be reused.
    check(sm.update(L.OPEN_PALM, 0.40f, 250L) == A.NONE)
    check(sm.update(L.OPEN_PALM, 0.9f, 260L) == A.NONE)
    check(sm.update(L.OPEN_PALM, 0.9f, 439L) == A.NONE)
    check(sm.update(L.OPEN_PALM, 0.9f, 440L) == A.ARMED)
}

private fun wrongOrderDoesNotTrigger() {
    val sm = Sm(GestureStateMachine.GestureMode.CAPTURE, stableMs = 100)
    check(sm.update(L.CLOSED_FIST, 0.9f, 0L) == A.NONE)
    check(sm.update(L.CLOSED_FIST, 0.9f, 100L) == A.NONE)
}

private const val SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
private const val TID = "7f3c2b1a-5d4e-4f60-9a8b-1c2d3e4f5a6b"

private fun protocolRoundTripWorks() {
    val offer = TransferMessage.Offer(TID, "capture-1.png", 1234L, SHA, "image/png", 1700000000000L, "Pixel 8")
    check(TransferProtocol.decode(TransferProtocol.encode(offer)) == offer)
    val noName = offer.copy(senderName = "")
    check(TransferProtocol.decode(TransferProtocol.encode(noName)) == noName)
}

private fun protocolPreviewRoundTrip() {
    val preview = TransferMessage.Preview(TID, "A".repeat(128))
    check(TransferProtocol.decode(TransferProtocol.encode(preview)) == preview)
    check(TransferProtocol.decode("PREVIEW|$TID|bad!".toByteArray()) == null)
}

private fun protocolControlMessagesRoundTrip() {
    for (message in listOf(
        TransferMessage.Accept(TID),
        TransferMessage.Cancel(TID),
        TransferMessage.Reject(TID, "Receiver is busy"),
        TransferMessage.Result(TID, true, ""),
        TransferMessage.Result(TID, false, "checksum mismatch"),
    )) {
        check(TransferProtocol.decode(TransferProtocol.encode(message)) == message)
    }
}

private fun protocolPairingMessagesRoundTrip() {
    val pair = TransferMessage.PairSetup("aaaaaaaaaaaaaaaa", "A".repeat(64))
    check(TransferProtocol.decode(TransferProtocol.encode(pair)) == pair)
    val hello = TransferMessage.AuthHello("aaaaaaaaaaaaaaaa", "B".repeat(64))
    check(TransferProtocol.decode(TransferProtocol.encode(hello)) == hello)
    val response = TransferMessage.AuthResponse("bbbbbbbbbbbbbbbb", hello.nonce, "C".repeat(64))
    check(TransferProtocol.decode(TransferProtocol.encode(response)) == response)
}

private fun pairingCryptoRoundTrip() {
    val aId = "a".repeat(16)
    val bId = "b".repeat(16)
    val aToken = PairingCrypto.randomToken()
    val bToken = PairingCrypto.randomToken()
    val secretA = PairingCrypto.deriveSharedSecret(aId, aToken, bId, bToken)
    val secretB = PairingCrypto.deriveSharedSecret(bId, bToken, aId, aToken)
    check(secretA == secretB)
    val nonce = PairingCrypto.randomNonce()
    val proof = PairingCrypto.proof(secretA, "hello", aId, nonce, bId)
    check(PairingCrypto.constantTimeEquals(proof, PairingCrypto.proof(secretB, "hello", aId, nonce, bId)))
    check(!PairingCrypto.constantTimeEquals(proof, PairingCrypto.proof(secretB, "hello", aId, PairingCrypto.randomNonce(), bId)))
}

private fun invalidMessagesAreRejected() {
    fun offer(id: String = TID, size: String = "10", sha: String = SHA, mime: String = "image/png") =
        "OFFER|$id|capture.png|$size|$sha|$mime|1700000000000|Phone".toByteArray()
    check(TransferProtocol.decode(offer()) != null)
    check(TransferProtocol.decode(offer(size = "0")) == null)
    check(TransferProtocol.decode(offer(size = "-12")) == null)
    check(TransferProtocol.decode(offer(sha = "abc")) == null)
    check(TransferProtocol.decode(offer(id = "x")) == null)
    check(TransferProtocol.decode(offer(id = "../../etc/passwd")) == null)
    check(TransferProtocol.decode(offer(mime = "not a mime")) == null)
    check(TransferProtocol.decode("OFFER|capture.png|12".toByteArray()) == null)
    check(TransferProtocol.decode("ACCEPT|$TID|extra".toByteArray()) == null)
    check(TransferProtocol.decode("RESULT|$TID|1|ok|extra".toByteArray()) == null)
    check(TransferProtocol.decode("AUTHR|aaaaaaaaaaaaaaaa|${"B".repeat(64)}|${"C".repeat(64)}|extra".toByteArray()) == null)
    check(TransferProtocol.decode(ByteArray(0)) == null)
}
