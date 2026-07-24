package com.example.gestureswipe

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.max

/**
 * Draws MediaPipe hand landmarks over the camera preview.
 *
 * The landmarks are normalized to the analysis image (imageWidth × imageHeight), while the
 * PreviewView shows that image scaled with FILL_CENTER (center-crop). We replicate that exact
 * transform so the dots land on the real hand, and mirror X to match the selfie preview.
 */
class HandOverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var landmarks: List<NormalizedLandmark>? = null
    private var imageWidth = 0
    private var imageHeight = 0

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

    private val connections = listOf(
        0 to 1, 1 to 2, 2 to 3, 3 to 4,          // thumb
        0 to 5, 5 to 6, 6 to 7, 7 to 8,          // index
        5 to 9, 9 to 10, 10 to 11, 11 to 12,     // middle
        9 to 13, 13 to 14, 14 to 15, 15 to 16,   // ring
        13 to 17, 17 to 18, 18 to 19, 19 to 20,  // pinky
        0 to 17                                   // palm base
    )

    fun setResults(list: List<NormalizedLandmark>?, imgW: Int, imgH: Int) {
        landmarks = list
        imageWidth = imgW
        imageHeight = imgH
        invalidate()
    }

    // FILL_CENTER: scale so the image covers the view, centered; then mirror X for front camera.
    private fun px(lm: NormalizedLandmark): Float {
        if (imageWidth == 0) return lm.x() * width
        val scale = max(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        val offsetX = (width - imageWidth * scale) / 2f
        val x = offsetX + lm.x() * imageWidth * scale
        return width - x // mirror
    }

    private fun py(lm: NormalizedLandmark): Float {
        if (imageHeight == 0) return lm.y() * height
        val scale = max(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        val offsetY = (height - imageHeight * scale) / 2f
        return offsetY + lm.y() * imageHeight * scale
    }

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
