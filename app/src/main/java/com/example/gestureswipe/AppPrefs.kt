package com.example.gestureswipe

import android.content.Context

/** Tiny wrapper over SharedPreferences for the detection-mode choice. */
object AppPrefs {
    private const val FILE = "gesture_prefs"
    private const val KEY_MODE = "mode"
    private const val KEY_SENS = "sensitivity"

    /** 0..100; 50 == the current default tuning. */
    const val DEFAULT_SENSITIVITY = 50

    enum class Mode { HAND, MOTION }

    fun getMode(context: Context): Mode {
        val name = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_MODE, Mode.HAND.name)
        return runCatching { Mode.valueOf(name!!) }.getOrDefault(Mode.HAND)
    }

    fun setMode(context: Context, mode: Mode) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODE, mode.name).apply()
    }

    fun getSensitivity(context: Context): Int =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getInt(KEY_SENS, DEFAULT_SENSITIVITY)

    fun setSensitivity(context: Context, value: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putInt(KEY_SENS, value.coerceIn(0, 100)).apply()
    }

    // --- Map sensitivity 0..100 to detector thresholds. At 50 → current defaults. ---

    /** SwipeDetector travel threshold: higher sensitivity → smaller required travel. */
    fun minDelta(sensitivity: Int): Float =
        (0.35f - sensitivity * 0.0025f).coerceIn(0.08f, 0.40f) // s=50 → 0.225

    /** MotionDetector required moving-pixel count: higher sensitivity → fewer needed. */
    fun minMovingPixels(sensitivity: Int): Int =
        (90 - sensitivity * 0.8f).toInt().coerceIn(8, 100) // s=50 → 50
}
