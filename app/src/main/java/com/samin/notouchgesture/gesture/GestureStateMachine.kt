package com.samin.notouchgesture.gesture

/**
 * Debounces MediaPipe labels into a deliberate gesture sequence.
 * The machine requires a stable label, then a neutral release after a trigger.
 */
class GestureStateMachine(
    private val mode: GestureMode,
    private val minConfidence: Float = 0.72f,
    private val stableMs: Long = 350L,
    private val neutralRearmMs: Long = 300L,
    private val cooldownMs: Long = 1_000L,
) {
    enum class GestureMode { CAPTURE, SEND, RECEIVE }

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
    private var waitingForNeutral = false
    private var neutralSince = Long.MIN_VALUE
    private var lastActionAt = Long.MIN_VALUE

    fun reset() {
        candidate = Label.NONE
        candidateSince = 0L
        currentStable = Label.NONE
        armed = false
        waitingForNeutral = false
        neutralSince = Long.MIN_VALUE
        lastActionAt = Long.MIN_VALUE
    }

    fun update(label: Label, confidence: Float, nowMs: Long): Action {
        val effective = if (confidence >= minConfidence) label else Label.NONE

        if (effective != candidate) {
            candidate = effective
            candidateSince = nowMs
        }

        if (nowMs - candidateSince < stableMs) return Action.NONE
        if (currentStable == effective && !waitingForNeutral) return Action.NONE

        currentStable = effective

        if (waitingForNeutral) {
            if (effective == Label.NONE) {
                if (neutralSince == Long.MIN_VALUE) neutralSince = candidateSince
                if (nowMs - neutralSince >= neutralRearmMs) {
                    waitingForNeutral = false
                    armed = false
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
        }
    }

    private fun handleCapture(label: Label, nowMs: Long): Action {
        return when {
            !armed && label == Label.OPEN_PALM -> {
                armed = true
                Action.ARMED
            }
            armed && label == Label.CLOSED_FIST -> {
                trigger(Action.CAPTURE, nowMs)
            }
            else -> Action.NONE
        }
    }

    private fun handleSend(label: Label, nowMs: Long): Action {
        return when {
            !armed && label == Label.CLOSED_FIST -> {
                armed = true
                Action.ARMED
            }
            armed && label == Label.OPEN_PALM -> {
                trigger(Action.SEND_OFFER, nowMs)
            }
            else -> Action.NONE
        }
    }

    private fun handleReceive(label: Label, nowMs: Long): Action {
        return if (label == Label.OPEN_PALM) {
            trigger(Action.RECEIVE_ACCEPT, nowMs)
        } else {
            Action.NONE
        }
    }

    private fun trigger(action: Action, nowMs: Long): Action {
        lastActionAt = nowMs
        waitingForNeutral = true
        neutralSince = Long.MIN_VALUE
        return action
    }
}
