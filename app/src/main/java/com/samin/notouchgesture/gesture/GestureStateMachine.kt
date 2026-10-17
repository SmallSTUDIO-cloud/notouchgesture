package com.samin.notouchgesture.gesture

/**
 * Debounces MediaPipe predictions into deliberate gesture sequences.
 *
 * CAPTURE:    open palm -> closed fist
 * SEND:       closed fist -> open palm (legacy, send-offer semantics)
 * RECEIVE:    closed fist -> open palm (authorizes the incoming screenshot)
 * BACKGROUND: open palm -> closed fist captures (and the service auto-sends it);
 *             closed fist -> open palm authorizes the incoming screenshot.
 *
 * The recognizer is intended for natural, quick hand motions. Each step therefore uses a
 * short stable window, with a small confidence grace period to tolerate normal frame jitter.
 */
class GestureStateMachine(
    private val mode: GestureMode,
    private val minConfidence: Float = 0.50f,
    /** Continuous stable window required before a gesture step is accepted. */
    private val stableMs: Long = 180L,
    private val neutralRearmMs: Long = 120L,
    private val cooldownMs: Long = 450L,
    /** Maximum time allowed between the two deliberate steps of a sequence. */
    private val armTimeoutMs: Long = 2_500L,
    /** Brief low-confidence gaps are tolerated while the same gesture remains visible. */
    private val confidenceGraceMs: Long = 140L,
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
    private var lastStrongAt = Long.MIN_VALUE
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
        lastStrongAt = Long.MIN_VALUE
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

        val safeConfidence = confidence.coerceIn(0f, 1f)
        val strong = label != Label.NONE && safeConfidence >= minConfidence

        if (label != candidate) {
            candidate = label
            candidateSince = nowMs
            lastStrongAt = if (strong) nowMs else Long.MIN_VALUE
        } else if (strong) {
            lastStrongAt = nowMs
        } else if (label != Label.NONE && lastStrongAt != Long.MIN_VALUE && nowMs - lastStrongAt > confidenceGraceMs) {
            // The confidence gap is now long enough that the old stable window must not be
            // reused when confidence returns. Treat this as a fresh candidate next time.
            candidate = Label.NONE
            candidateSince = nowMs
            lastStrongAt = Long.MIN_VALUE
        }

        // A brief confidence dip should not make the user restart a gesture. Once the
        // grace window expires, the recognizer becomes neutral and must stabilize again.
        val effective = when {
            strong -> label
            label != Label.NONE && lastStrongAt != Long.MIN_VALUE && nowMs - lastStrongAt <= confidenceGraceMs -> label
            else -> Label.NONE
        }

        if (waitingForNeutral) {
            if (effective == Label.NONE) {
                if (neutralSince == Long.MIN_VALUE) neutralSince = nowMs
                if (nowMs - neutralSince >= neutralRearmMs) {
                    waitingForNeutral = false
                    neutralSince = Long.MIN_VALUE
                    disarm()
                    currentStable = Label.NONE
                    candidate = Label.NONE
                    candidateSince = nowMs
                    lastStrongAt = Long.MIN_VALUE
                }
            } else {
                neutralSince = Long.MIN_VALUE
            }
            return Action.NONE
        }

        if (effective == Label.NONE) {
            currentStable = Label.NONE
            return Action.NONE
        }

        if (nowMs - candidateSince < stableMs) return Action.NONE

        if (currentStable == effective) return Action.NONE
        currentStable = effective

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
            currentStable = Label.NONE
            candidate = Label.NONE
            candidateSince = nowMs
            lastStrongAt = Long.MIN_VALUE
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
                // Do not silently turn OPEN_PALM -> FIST into a receive gesture.
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
