package com.example.gestureswipe

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

/**
 * Draws MediaPipe hand landmarks over the camera preview so we can see, live, what is
 * being tracked. Normalized coords are mirrored on X to match the selfie-view preview.
 */
class HandOverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var landmarks: List<NormalizedLandmark>? = null

    private val pointPaint = Paint().apply {
        color = Color.parseColor("#00E676")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val palmPaint = Paint().apply {
        color = Color.parseColor("#FF1744")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val linePaint = Paint().apply {
        color = Color.parseColor("#B0FFFFFF")
        strokeWidth = 4f
        isAntiAlias = true
    }

    // Bones connecting the 21 hand landmarks, for a simple skeleton.
    private val connections = listOf(
        0 to 1, 1 to 2, 2 to 3, 3 to 4,          // thumb
        0 to 5, 5 to 6, 6 to 7, 7 to 8,          // index
        5 to 9, 9 to 10, 10 to 11, 11 to 12,     // middle
        9 to 13, 13 to 14, 14 to 15, 15 to 16,   // ring
        13 to 17, 17 to 18, 18 to 19, 19 to 20,  // pinky
        0 to 17                                   // palm base
    )

    fun setLandmarks(list: List<NormalizedLandmark>?) {
        landmarks = list
        invalidate()
    }

    // Mirror X for the front (selfie) camera so the overlay lines up with the preview.
    private fun px(lm: NormalizedLandmark) = (1f - lm.x()) * width
    private fun py(lm: NormalizedLandmark) = lm.y() * height

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val lms = landmarks ?: return

        for ((a, b) in connections) {
            if (a < lms.size && b < lms.size) {
                canvas.drawLine(px(lms[a]), py(lms[a]), px(lms[b]), py(lms[b]), linePaint)
            }
        }
        for ((i, lm) in lms.withIndex()) {
            val paint = if (i == HandLandmarkerHelper.PALM_LANDMARK) palmPaint else pointPaint
            val radius = if (i == HandLandmarkerHelper.PALM_LANDMARK) 18f else 10f
            canvas.drawCircle(px(lm), py(lm), radius, paint)
        }
    }
}
