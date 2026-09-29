package com.samin.notouchgesture

import com.samin.notouchgesture.gesture.GestureStateMachine
import com.samin.notouchgesture.nearby.TransferMessage
import com.samin.notouchgesture.nearby.TransferProtocol

fun main() {
    captureSequenceWorks()
    lowConfidenceDoesNotTrigger()
    triggerNeedsNeutralRelease()
    sendSequenceWorks()
    receiveGestureWorks()
    wrongOrderDoesNotTrigger()
    protocolRoundTripWorks()
    println("LOGIC_SMOKE_TEST: PASS")
}

private fun captureSequenceWorks() {
    val sm = GestureStateMachine(GestureStateMachine.GestureMode.CAPTURE, stableMs = 100)
    check(sm.update(GestureStateMachine.Label.OPEN_PALM, 0.9f, 0L) == GestureStateMachine.Action.NONE)
    check(sm.update(GestureStateMachine.Label.OPEN_PALM, 0.9f, 100L) == GestureStateMachine.Action.ARMED)
    check(sm.update(GestureStateMachine.Label.CLOSED_FIST, 0.9f, 200L) == GestureStateMachine.Action.NONE)
    check(sm.update(GestureStateMachine.Label.CLOSED_FIST, 0.9f, 300L) == GestureStateMachine.Action.CAPTURE)
}

private fun lowConfidenceDoesNotTrigger() {
    val sm = GestureStateMachine(GestureStateMachine.GestureMode.CAPTURE, stableMs = 100)
    sm.update(GestureStateMachine.Label.OPEN_PALM, 0.6f, 0L)
    check(sm.update(GestureStateMachine.Label.CLOSED_FIST, 0.6f, 500L) == GestureStateMachine.Action.NONE)
}

private fun triggerNeedsNeutralRelease() {
    val sm = GestureStateMachine(GestureStateMachine.GestureMode.CAPTURE, stableMs = 50, neutralRearmMs = 50)
    sm.update(GestureStateMachine.Label.OPEN_PALM, 1f, 0L)
    sm.update(GestureStateMachine.Label.OPEN_PALM, 1f, 50L)
    sm.update(GestureStateMachine.Label.CLOSED_FIST, 1f, 100L)
    check(sm.update(GestureStateMachine.Label.CLOSED_FIST, 1f, 150L) == GestureStateMachine.Action.CAPTURE)
    check(sm.update(GestureStateMachine.Label.CLOSED_FIST, 1f, 400L) == GestureStateMachine.Action.NONE)
    sm.update(GestureStateMachine.Label.NONE, 0f, 450L)
    sm.update(GestureStateMachine.Label.NONE, 0f, 500L)
    check(sm.update(GestureStateMachine.Label.OPEN_PALM, 1f, 1150L) == GestureStateMachine.Action.NONE)
    check(sm.update(GestureStateMachine.Label.OPEN_PALM, 1f, 1250L) == GestureStateMachine.Action.ARMED)
}


private fun sendSequenceWorks() {
    val sm = GestureStateMachine(GestureStateMachine.GestureMode.SEND, stableMs = 100, cooldownMs = 200)
    check(sm.update(GestureStateMachine.Label.CLOSED_FIST, 0.9f, 0L) == GestureStateMachine.Action.NONE)
    check(sm.update(GestureStateMachine.Label.CLOSED_FIST, 0.9f, 100L) == GestureStateMachine.Action.ARMED)
    check(sm.update(GestureStateMachine.Label.OPEN_PALM, 0.9f, 200L) == GestureStateMachine.Action.NONE)
    check(sm.update(GestureStateMachine.Label.OPEN_PALM, 0.9f, 300L) == GestureStateMachine.Action.SEND_OFFER)
}

private fun receiveGestureWorks() {
    val sm = GestureStateMachine(GestureStateMachine.GestureMode.RECEIVE, stableMs = 100)
    check(sm.update(GestureStateMachine.Label.OPEN_PALM, 0.9f, 0L) == GestureStateMachine.Action.NONE)
    check(sm.update(GestureStateMachine.Label.OPEN_PALM, 0.9f, 100L) == GestureStateMachine.Action.RECEIVE_ACCEPT)
}

private fun wrongOrderDoesNotTrigger() {
    val sm = GestureStateMachine(GestureStateMachine.GestureMode.CAPTURE, stableMs = 100)
    check(sm.update(GestureStateMachine.Label.CLOSED_FIST, 0.9f, 0L) == GestureStateMachine.Action.NONE)
    check(sm.update(GestureStateMachine.Label.CLOSED_FIST, 0.9f, 100L) == GestureStateMachine.Action.NONE)
}

private fun protocolRoundTripWorks() {
    val offer = TransferMessage.Offer("capture.png", 1234L)
    val decoded = TransferProtocol.decode(TransferProtocol.encode(offer))
    check(decoded == offer)
    check(TransferProtocol.decode("NOPE|x".toByteArray()) == null)
}
