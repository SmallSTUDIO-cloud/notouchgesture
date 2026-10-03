package com.samin.notouchgesture.gesture

/**
 * Debounces MediaPipe labels into deliberate gesture sequences.
 *
 * CAPTURE:    open palm -> closed fist
 * SEND:       closed fist -> open palm (legacy, send-offer semantics)
 * RECEIVE:    closed fist -> open palm (authorizes the incoming screenshot)
 * BACKGROUND: open palm -> closed fist captures (and the service auto-sends it);
 *             closed fist -> open palm authorizes the incoming screenshot.
 *             If an offer is already pending, an open-palm-first sequence is ignored and
 *             reset rather than being converted into a hidden third-step gesture.
 *
 * A half-completed sequence expires after [armTimeoutMs], so an open palm shown minutes
 * ago can never combine with a later fist.
 */
class GestureStateMachine(
    private val mode: GestureMode,
    private val minConfidence: Float = 0.50f,
    private val stableMs: Long = 220L,
    private val neutralRearmMs: Long = 180L,
    private val cooldownMs: Long = 600L,
    private val armTimeoutMs: Long = 4_000L,
) {
    enum class GestureMode { CAPTURE, SEND, RECEIVE, BACKGROUND }

    enum class Label {
        NONE,
        OPEN_PALM,
        CLOSED_FIST,
    }

    enum class Action {
        NONE,
        ARMED,
        CAPTURE,
        SEND_OFFER,
        RECEIVE_ACCEPT,
    }

    private var candidate = Label.NONE
    private var candidateSince = 0L
    private var currentStable = Label.NONE
    private var armed = false
    private var armedSequence: Label? = null
    private var armedAt = Long.MIN_VALUE
    private var waitingForNeutral = false
    private var neutralSince = Long.MIN_VALUE
    private var lastActionAt = Long.MIN_VALUE

    fun reset() {
        candidate = Label.NONE
        candidateSince = 0L
        currentStable = Label.NONE
        armed = false
        armedSequence = null
        armedAt = Long.MIN_VALUE
        waitingForNeutral = false
        neutralSince = Long.MIN_VALUE
        lastActionAt = Long.MIN_VALUE
    }

    fun update(label: Label, confidence: Float, nowMs: Long, receivePending: Boolean = false): Action {
        expireStaleArming(nowMs)

        val effective = if (confidence >= minConfidence) label else Label.NONE

        if (effective != candidate) {
            candidate = effective
            candidateSince = nowMs
        }

        if (nowMs - candidateSince < stableMs) return Action.NONE

        if (currentStable == effective && !waitingForNeutral) {
            return Action.NONE
        }

        currentStable = effective

        if (waitingForNeutral) {
            if (effective == Label.NONE) {
                if (neutralSince == Long.MIN_VALUE) neutralSince = candidateSince
                if (nowMs - neutralSince >= neutralRearmMs) {
                    waitingForNeutral = false
                    disarm()
                    neutralSince = Long.MIN_VALUE
                    currentStable = Label.NONE
                }
            } else {
                neutralSince = Long.MIN_VALUE
            }
            return Action.NONE
        }

        if (lastActionAt != Long.MIN_VALUE && nowMs - lastActionAt < cooldownMs) return Action.NONE

        return when (mode) {
            GestureMode.CAPTURE -> handleCapture(effective, nowMs)
            GestureMode.SEND -> handleSend(effective, nowMs)
            GestureMode.RECEIVE -> handleReceive(effective, nowMs)
            GestureMode.BACKGROUND -> handleBackground(effective, nowMs, receivePending)
        }
    }

    private fun expireStaleArming(nowMs: Long) {
        if (armed && !waitingForNeutral && armedAt != Long.MIN_VALUE && nowMs - armedAt > armTimeoutMs) {
            disarm()
            // Forget the stable label so a hand that is still held can arm again.
            currentStable = Label.NONE
        }
    }

    private fun disarm() {
        armed = false
        armedSequence = null
        armedAt = Long.MIN_VALUE
    }

    private fun arm(sequence: Label?, nowMs: Long): Action {
        armed = true
        armedSequence = sequence
        armedAt = nowMs
        return Action.ARMED
    }

    private fun handleCapture(label: Label, nowMs: Long): Action = when {
        !armed && label == Label.OPEN_PALM -> arm(null, nowMs)
        armed && label == Label.CLOSED_FIST -> trigger(Action.CAPTURE, nowMs)
        else -> Action.NONE
    }

    private fun handleSend(label: Label, nowMs: Long): Action = when {
        !armed && label == Label.CLOSED_FIST -> arm(null, nowMs)
        armed && label == Label.OPEN_PALM -> trigger(Action.SEND_OFFER, nowMs)
        else -> Action.NONE
    }

    private fun handleReceive(label: Label, nowMs: Long): Action = when {
        !armed && label == Label.CLOSED_FIST -> arm(null, nowMs)
        armed && label == Label.OPEN_PALM -> trigger(Action.RECEIVE_ACCEPT, nowMs)
        else -> Action.NONE
    }

    private fun handleBackground(label: Label, nowMs: Long, receivePending: Boolean): Action = when {
        armedSequence == null && label == Label.OPEN_PALM -> {
            arm(Label.OPEN_PALM, nowMs)
        }
        armedSequence == null && label == Label.CLOSED_FIST -> {
            arm(Label.CLOSED_FIST, nowMs)
        }
        armedSequence == Label.OPEN_PALM && label == Label.CLOSED_FIST -> {
            if (receivePending) {
                // Do not silently turn OPEN_PALM -> FIST into a three-step receive gesture.
                // The user must perform the documented FIST -> OPEN_PALM receive gesture.
                disarm()
                currentStable = Label.NONE
                Action.NONE
            } else {
                trigger(Action.CAPTURE, nowMs)
            }
        }
        armedSequence == Label.CLOSED_FIST && label == Label.OPEN_PALM -> {
            trigger(Action.RECEIVE_ACCEPT, nowMs)
        }
        else -> Action.NONE
    }

    private fun trigger(action: Action, nowMs: Long): Action {
        lastActionAt = nowMs
        waitingForNeutral = true
        neutralSince = Long.MIN_VALUE
        return action
    }
}
