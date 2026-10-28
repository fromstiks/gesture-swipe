package com.example.gestureswipe

import android.os.SystemClock
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.hypot

/**
 * Pinch gesture detector using the 21 hand landmarks.
 *
 * It measures the distance between the thumb tip (landmark 4) and index tip (landmark 8),
 * normalized by hand size (wrist 0 → middle-finger MCP 9) so it is independent of how far the
 * hand is from the camera. A "pinch" is one close→(stay closed)→open cycle, detected with
 * hysteresis.
 *
 * Mapping (as requested):
 *  - one pinch            → swipe DOWN
 *  - two pinches in a row → swipe UP
 *
 * Like single vs double click: after a pinch we wait [doubleWindowMs]; a second pinch in that
 * window makes it a double (UP), otherwise the single fires (DOWN).
 */
class PinchDetector(
    private val onAction: (SwipeDetector.Direction) -> Unit
) {
    // Normalized thresholds (pinch distance / hand size) with hysteresis. Tunable.
    var closeThreshold = 0.45f
    var openThreshold = 0.70f

    private val doubleWindowMs = 450L
    private val cooldownMs = 450L

    @Volatile
    var lastDistance: Float = 1f
        private set

    @Volatile
    var isPinched: Boolean = false
        private set

    private var pendingSingleAt = 0L // 0 = no pending single pinch
    private var lastActionAt = 0L

    fun onFrame(landmarks: List<NormalizedLandmark>?, now: Long = SystemClock.uptimeMillis()) {
        if (landmarks == null || landmarks.size < 13) {
            resolvePending(now)
            return
        }

        val d = normalizedPinch(landmarks)
        lastDistance = d

        if (!isPinched && d < closeThreshold) {
            isPinched = true
            onPinchClosed(now)
        } else if (isPinched && d > openThreshold) {
            isPinched = false
        }

        resolvePending(now)
    }

    private fun onPinchClosed(now: Long) {
        if (now - lastActionAt < cooldownMs) return

        if (pendingSingleAt != 0L && now - pendingSingleAt <= doubleWindowMs) {
            // Second pinch within the window → double pinch → UP.
            pendingSingleAt = 0L
            lastActionAt = now
            onAction(SwipeDetector.Direction.UP)
        } else {
            // First pinch → wait to see if a second follows.
            pendingSingleAt = now
        }
    }

    private fun resolvePending(now: Long) {
        if (pendingSingleAt != 0L && now - pendingSingleAt > doubleWindowMs) {
            // No second pinch arrived → it was a single → DOWN.
            pendingSingleAt = 0L
            lastActionAt = now
            onAction(SwipeDetector.Direction.DOWN)
        }
    }

    private fun normalizedPinch(lm: List<NormalizedLandmark>): Float {
        val thumb = lm[4]
        val index = lm[8]
        val pinch = hypot((thumb.x() - index.x()).toDouble(), (thumb.y() - index.y()).toDouble())

        val wrist = lm[0]
        val mcp = lm[9]
        val scale = hypot((wrist.x() - mcp.x()).toDouble(), (wrist.y() - mcp.y()).toDouble())

        return if (scale > 1e-4) (pinch / scale).toFloat() else pinch.toFloat()
    }

    fun reset() {
        pendingSingleAt = 0L
        isPinched = false
    }
}
