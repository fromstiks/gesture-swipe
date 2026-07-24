package com.example.gestureswipe

import android.content.Context
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult

/**
 * One detection result: the 21 landmarks of the first hand (or null), plus the size of the
 * upright image the landmarks are normalized against (needed to align an overlay).
 */
data class HandResult(
    val landmarks: List<NormalizedLandmark>?,
    val framesProcessed: Long,
    val imageWidth: Int,
    val imageHeight: Int
)

/**
 * Wraps MediaPipe HandLandmarker in LIVE_STREAM mode.
 *
 * Feed it CameraX frames via [detect]; it reports each result through [onResult] on
 * MediaPipe's own thread. [initError] is non-null if the model failed to load.
 *
 * The model file `hand_landmarker.task` must be in app/src/main/assets/.
 * Download: https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task
 */
class HandLandmarkerHelper(
    context: Context,
    private val onResult: (HandResult) -> Unit
) {
    companion object {
        private const val TAG = "HandLandmarkerHelper"
        private const val MODEL_ASSET = "hand_landmarker.task"
        // Landmark 9 = base of the middle finger ≈ centre of the palm; stable under rotation.
        const val PALM_LANDMARK = 9
    }

    private var handLandmarker: HandLandmarker? = null

    /** Non-null if initialization failed (e.g. model missing) — surface this in the UI. */
    var initError: String? = null
        private set

    val isReady: Boolean get() = handLandmarker != null

    @Volatile
    var framesProcessed: Long = 0L
        private set

    // Size of the upright image the landmarks map onto (post-rotation), for overlay alignment.
    @Volatile private var uprightWidth: Int = 0
    @Volatile private var uprightHeight: Int = 0

    init {
        // CPU delegate: the GPU delegate can silently produce no results in LIVE_STREAM mode
        // on some devices, so we use the reliable CPU path.
        handLandmarker = try {
            createLandmarker(context, Delegate.CPU)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to init HandLandmarker. Is hand_landmarker.task in assets?", e)
            initError = e.message ?: e.javaClass.simpleName
            null
        }
    }

    private fun createLandmarker(context: Context, delegate: Delegate): HandLandmarker {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET)
            .setDelegate(delegate)
            .build()

        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.4f)
            .setMinTrackingConfidence(0.4f)
            .setMinHandPresenceConfidence(0.4f)
            .setResultListener { result, _ -> handleResult(result) }
            .setErrorListener { e ->
                Log.e(TAG, "MediaPipe error", e)
                initError = e.message
            }
            .build()

        return HandLandmarker.createFromOptions(context, options)
    }

    private fun handleResult(result: HandLandmarkerResult) {
        val hand = result.landmarks().firstOrNull()
        onResult(HandResult(hand, framesProcessed, uprightWidth, uprightHeight))
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
            // toBitmap() correctly handles row padding / stride, unlike a raw buffer copy,
            // so the hand isn't skewed and detection is far more reliable.
            val bitmap = imageProxy.toBitmap()

            // After rotation, width/height swap for 90°/270° — that's the frame the
            // normalized landmarks map onto.
            val rotation = imageProxy.imageInfo.rotationDegrees
            if (rotation == 90 || rotation == 270) {
                uprightWidth = imageProxy.height
                uprightHeight = imageProxy.width
            } else {
                uprightWidth = imageProxy.width
                uprightHeight = imageProxy.height
            }

            val mpImage = BitmapImageBuilder(bitmap).build()
            val processingOptions = ImageProcessingOptions.builder()
                .setRotationDegrees(rotation)
                .build()

            framesProcessed++
            landmarker.detectAsync(mpImage, processingOptions, imageProxy.imageInfo.timestamp)
        } catch (e: Exception) {
            Log.e(TAG, "detect() failed", e)
            initError = "detect: ${e.message}"
        } finally {
            imageProxy.close()
        }
    }

    fun close() {
        handLandmarker?.close()
        handLandmarker = null
    }
}
