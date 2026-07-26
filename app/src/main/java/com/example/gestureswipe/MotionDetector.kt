package com.example.gestureswipe

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageProxy
import kotlin.math.abs

/**
 * Distance-agnostic motion detector: instead of recognising a hand, it compares consecutive
 * frames and reports the vertical position (0..1) of whatever is moving. Works with a finger
 * right next to the camera, where whole-hand landmark detection fails.
 *
 * Reports the motion centroid's Y through [onMotionY], or null when there isn't enough motion.
 * That Y is fed into the same [SwipeDetector] used by the hand engine.
 */
class MotionDetector(private val onMotionY: (Float?) -> Unit) {

    companion object {
        private const val TAG = "MotionDetector"
        private const val GW = 24   // downscaled grid width
        private const val GH = 32   // downscaled grid height
        private const val PIXEL_DELTA = 30          // per-pixel brightness change to count as motion
    }

    /** Required moving-pixel count; retunable via the sensitivity slider. */
    @Volatile
    var minMovingPixels: Int = 50

    private var prevGray: IntArray? = null
    private val pixels = IntArray(GW * GH)

    @Volatile
    var framesProcessed: Long = 0L
        private set

    /** Amount of motion in the last frame (moving-pixel count), for the debug UI. */
    @Volatile
    var lastMotionAmount: Int = 0
        private set

    fun detect(imageProxy: ImageProxy) {
        try {
            val full = imageProxy.toBitmap()
            val rotation = imageProxy.imageInfo.rotationDegrees
            val upright = if (rotation != 0) {
                val m = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(full, 0, 0, full.width, full.height, m, true)
            } else full

            val small = Bitmap.createScaledBitmap(upright, GW, GH, true)
            small.getPixels(pixels, 0, GW, 0, 0, GW, GH)
            framesProcessed++

            val gray = IntArray(GW * GH)
            for (i in pixels.indices) {
                val c = pixels[i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                gray[i] = (r * 299 + g * 587 + b * 114) / 1000
            }

            val prev = prevGray
            prevGray = gray
            if (prev == null) {
                onMotionY(null)
                return
            }

            var sumY = 0.0
            var weight = 0.0
            var movingPixels = 0
            for (y in 0 until GH) {
                for (x in 0 until GW) {
                    val idx = y * GW + x
                    val d = abs(gray[idx] - prev[idx])
                    if (d > PIXEL_DELTA) {
                        movingPixels++
                        sumY += y * d
                        weight += d
                    }
                }
            }
            lastMotionAmount = movingPixels

            if (movingPixels < minMovingPixels || weight <= 0.0) {
                onMotionY(null)
                return
            }
            val centroidY = (sumY / weight) / (GH - 1)  // normalize 0..1
            onMotionY(centroidY.toFloat())
        } catch (e: Exception) {
            Log.e(TAG, "motion detect failed", e)
            onMotionY(null)
        } finally {
            imageProxy.close()
        }
    }

    fun reset() {
        prevGray = null
    }
}
