package com.samin.notouchgesture.gesture

/**
 * Debounces MediaPipe labels into deliberate gesture sequences.
 *
 * CAPTURE:   open palm -> closed fist
 * SEND:      closed fist -> open palm
 * RECEIVE:   open palm
 * BACKGROUND: both capture/send sequences, with RECEIVE taking priority when a transfer is pending.
 */
class GestureStateMachine(
    private val mode: GestureMode,
    private val minConfidence: Float = 0.50f,
    private val stableMs: Long = 220L,
    private val neutralRearmMs: Long = 180L,
    private val cooldownMs: Long = 600L,
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
    private var waitingForNeutral = false
    private var neutralSince = Long.MIN_VALUE
    private var lastActionAt = Long.MIN_VALUE

    fun reset() {
        candidate = Label.NONE
        candidateSince = 0L
        currentStable = Label.NONE
        armed = false
        armedSequence = null
        waitingForNeutral = false
        neutralSince = Long.MIN_VALUE
        lastActionAt = Long.MIN_VALUE
    }

    fun update(label: Label, confidence: Float, nowMs: Long, receivePending: Boolean = false): Action {
        val effective = if (confidence >= minConfidence) label else Label.NONE

        if (effective != candidate) {
            candidate = effective
            candidateSince = nowMs
        }

        if (nowMs - candidateSince < stableMs) return Action.NONE

        val backgroundReceiveReady = mode == GestureMode.BACKGROUND &&
            receivePending &&
            effective == Label.OPEN_PALM &&
            !waitingForNeutral

        // A background receive offer may arrive after the user's open palm has already
        // become stable. Keep that path eligible even when a send/capture sequence had
        // previously been armed, because a pending receive has explicit priority.
        if (!backgroundReceiveReady && currentStable == effective && !waitingForNeutral) {
            return Action.NONE
        }

        currentStable = effective

        if (waitingForNeutral) {
            if (effective == Label.NONE) {
                if (neutralSince == Long.MIN_VALUE) neutralSince = candidateSince
                if (nowMs - neutralSince >= neutralRearmMs) {
                    waitingForNeutral = false
                    armed = false
                    armedSequence = null
                    neutralSince = Long.MIN_VALUE
                    currentStable = Label.NONE
                }
            } else {
                neutralSince = Long.MIN_VALUE
            }
            return Action.NONE
        }

        if (lastActionAt != Long.MIN_VALUE && nowMs - lastActionAt < cooldownMs) return Action.NONE

        if (backgroundReceiveReady) {
            return trigger(Action.RECEIVE_ACCEPT, nowMs)
        }

        return when (mode) {
            GestureMode.CAPTURE -> handleCapture(effective, nowMs)
            GestureMode.SEND -> handleSend(effective, nowMs)
            GestureMode.RECEIVE -> handleReceive(effective, nowMs)
            GestureMode.BACKGROUND -> handleBackground(effective, nowMs, receivePending)
        }
    }

    private fun handleCapture(label: Label, nowMs: Long): Action = when {
        !armed && label == Label.OPEN_PALM -> {
            armed = true
            Action.ARMED
        }
        armed && label == Label.CLOSED_FIST -> trigger(Action.CAPTURE, nowMs)
        else -> Action.NONE
    }

    private fun handleSend(label: Label, nowMs: Long): Action = when {
        !armed && label == Label.CLOSED_FIST -> {
            armed = true
            Action.ARMED
        }
        armed && label == Label.OPEN_PALM -> trigger(Action.SEND_OFFER, nowMs)
        else -> Action.NONE
    }

    private fun handleReceive(label: Label, nowMs: Long): Action =
        if (label == Label.OPEN_PALM) trigger(Action.RECEIVE_ACCEPT, nowMs) else Action.NONE

    private fun handleBackground(label: Label, nowMs: Long, receivePending: Boolean): Action = when {
        receivePending && armedSequence == null && label == Label.OPEN_PALM -> trigger(Action.RECEIVE_ACCEPT, nowMs)
        armedSequence == null && label == Label.OPEN_PALM -> {
            armed = true
            armedSequence = Label.OPEN_PALM
            Action.ARMED
        }
        armedSequence == null && label == Label.CLOSED_FIST -> {
            armed = true
            armedSequence = Label.CLOSED_FIST
            Action.ARMED
        }
        armedSequence == Label.OPEN_PALM && label == Label.CLOSED_FIST -> trigger(Action.CAPTURE, nowMs)
        armedSequence == Label.CLOSED_FIST && label == Label.OPEN_PALM -> trigger(Action.SEND_OFFER, nowMs)
        else -> Action.NONE
    }

    private fun trigger(action: Action, nowMs: Long): Action {
        lastActionAt = nowMs
        waitingForNeutral = true
        neutralSince = Long.MIN_VALUE
        return action
    }
}
