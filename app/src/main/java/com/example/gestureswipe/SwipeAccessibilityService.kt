package com.example.gestureswipe

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/**
 * Performs the actual on-screen swipe on whatever app is in the foreground (e.g. TikTok).
 *
 * The camera service can't inject touch events itself — only an AccessibilityService with
 * canPerformGestures="true" may call [dispatchGesture]. We expose a static [instance] so the
 * camera service can command us once the user has enabled this service in system settings.
 */
class SwipeAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: SwipeAccessibilityService? = null
            private set

        private const val GESTURE_DURATION_MS = 180L
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* not needed */ }

    override fun onInterrupt() { /* not needed */ }

    /** Swipe content up → next video (finger moves from lower to upper part of the screen). */
    fun swipeUp() = swipeVertical(fromFraction = 0.72f, toFraction = 0.28f)

    /** Swipe content down → previous video. */
    fun swipeDown() = swipeVertical(fromFraction = 0.28f, toFraction = 0.72f)

    private fun swipeVertical(fromFraction: Float, toFraction: Float) {
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val yFrom = metrics.heightPixels * fromFraction
        val yTo = metrics.heightPixels * toFraction

        val path = Path().apply {
            moveTo(x, yFrom)
            lineTo(x, yTo)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0L, GESTURE_DURATION_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }
}
