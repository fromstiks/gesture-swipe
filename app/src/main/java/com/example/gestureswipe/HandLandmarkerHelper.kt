package com.example.gestureswipe

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult

/**
 * Wraps MediaPipe HandLandmarker in LIVE_STREAM mode.
 *
 * Feed it CameraX frames via [detect]; it reports the palm's normalized Y position
 * (or null when no hand is seen) through [onPalmY], on MediaPipe's result thread.
 *
 * The model file `hand_landmarker.task` must be placed in app/src/main/assets/.
 * Download: https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task
 */
class HandLandmarkerHelper(
    context: Context,
    private val onPalmY: (Float?) -> Unit
) {
    companion object {
        private const val TAG = "HandLandmarkerHelper"
        private const val MODEL_ASSET = "hand_landmarker.task"
        // Landmark 9 = base of the middle finger ≈ centre of the palm; stable under rotation.
        private const val PALM_LANDMARK = 9
    }

    private var handLandmarker: HandLandmarker? = null

    init {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET)
                .setDelegate(Delegate.CPU) // switch to GPU if you validate it on your device
                .build()

            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(1)
                .setMinHandDetectionConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setResultListener { result, _ -> handleResult(result) }
                .setErrorListener { e -> Log.e(TAG, "MediaPipe error", e) }
                .build()

            handLandmarker = HandLandmarker.createFromOptions(context, options)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init HandLandmarker. Is hand_landmarker.task in assets?", e)
        }
    }

    private fun handleResult(result: HandLandmarkerResult) {
        val palmY = result.landmarks().firstOrNull()?.getOrNull(PALM_LANDMARK)?.y()
        onPalmY(palmY)
    }

    /**
     * Analyze one CameraX frame. Requires ImageAnalysis configured with
     * OUTPUT_IMAGE_FORMAT_RGBA_8888. The ImageProxy is always closed.
     */
    fun detect(imageProxy: ImageProxy) {
        val landmarker = handLandmarker
        if (landmarker == null) {
            imageProxy.close()
            return
        }

        try {
            val bitmap = Bitmap.createBitmap(
                imageProxy.width,
                imageProxy.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(imageProxy.planes[0].buffer)

            val mpImage = BitmapImageBuilder(bitmap).build()
            val processingOptions = ImageProcessingOptions.builder()
                .setRotationDegrees(imageProxy.imageInfo.rotationDegrees)
                .build()

            landmarker.detectAsync(mpImage, processingOptions, imageProxy.imageInfo.timestamp)
        } catch (e: Exception) {
            Log.e(TAG, "detect() failed", e)
        } finally {
            imageProxy.close()
        }
    }

    fun close() {
        handLandmarker?.close()
        handLandmarker = null
    }
}
