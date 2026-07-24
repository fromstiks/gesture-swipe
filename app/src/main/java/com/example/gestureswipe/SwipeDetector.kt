package com.example.gestureswipe

import android.os.SystemClock
import kotlin.math.abs

/**
 * Turns a stream of "palm Y position" samples into discrete swipe-up / swipe-down events.
 *
 * Y is the normalized vertical coordinate of the palm from MediaPipe: 0 = top of frame,
 * 1 = bottom. A hand moving physically UP makes Y decrease.
 *
 * Detection rule: within a short time window the palm must travel more than [minDelta]
 * (a fraction of the frame height). After a swipe fires we ignore input for [cooldownMs]
 * so one physical wave can't trigger several page turns.
 *
 * All thresholds are deliberately easy to tweak — real cameras/lighting need calibration.
 */
class SwipeDetector(
    private val windowMs: Long = 450L,
    private val minDelta: Float = 0.15f,
    private val cooldownMs: Long = 600L,
    private val onSwipe: (Direction) -> Unit
) {
    enum class Direction { UP, DOWN }

    private data class Sample(val t: Long, val y: Float)

    private val samples = ArrayDeque<Sample>()
    private var lastSwipeAt = 0L

    /**
     * @param y palm Y in [0,1], or null when no hand is visible in this frame.
     */
    fun onFrame(y: Float?, now: Long = SystemClock.uptimeMillis()) {
        if (y == null) {
            samples.clear()
            return
        }

        samples.addLast(Sample(now, y))
        while (samples.isNotEmpty() && now - samples.first().t > windowMs) {
            samples.removeFirst()
        }

        if (samples.size < 2) return
        if (now - lastSwipeAt < cooldownMs) return

        val dy = samples.last().y - samples.first().y
        if (abs(dy) >= minDelta) {
            lastSwipeAt = now
            samples.clear()
            onSwipe(if (dy < 0f) Direction.UP else Direction.DOWN)
        }
    }

    fun reset() {
        samples.clear()
    }
}
