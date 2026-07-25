package com.example.gestureswipe

import android.content.Context

/** Tiny wrapper over SharedPreferences for the detection-mode choice. */
object AppPrefs {
    private const val FILE = "gesture_prefs"
    private const val KEY_MODE = "mode"

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
}
