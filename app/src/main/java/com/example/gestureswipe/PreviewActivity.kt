package com.example.gestureswipe

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import android.util.Size
import com.example.gestureswipe.databinding.ActivityPreviewBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Debug screen: shows the live camera + tracked hand landmarks + running stats.
 * Runs the whole detection pipeline in the foreground (no service, no accessibility)
 * so we can see exactly what the camera and MediaPipe are doing.
 */
class PreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPreviewBinding
    private lateinit var cameraExecutor: ExecutorService
    private var handHelper: HandLandmarkerHelper? = null
    private lateinit var swipeDetector: SwipeDetector

    private val vibrator: Vibrator? by lazy { getSystemService(Vibrator::class.java) }
    private var lastSwipeText = "—"

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startCamera() else binding.stats.text = "Нет разрешения на камеру" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        swipeDetector = SwipeDetector { direction ->
            lastSwipeText = if (direction == SwipeDetector.Direction.UP) "▲ ВВЕРХ" else "▼ ВНИЗ"
            runOnUiThread { vibrator?.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE)) }
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        handHelper = HandLandmarkerHelper(this) { result ->
            val hand = result.landmarks
            val palmY = hand?.getOrNull(HandLandmarkerHelper.PALM_LANDMARK)?.y()
            swipeDetector.onFrame(palmY)

            runOnUiThread {
                binding.overlay.setResults(hand, result.imageWidth, result.imageHeight)
                val helper = handHelper
                val err = helper?.initError
                binding.stats.text = buildString {
                    append(if (helper?.isReady == true) "MediaPipe: готов\n" else "MediaPipe: НЕ готов\n")
                    append("Кадров обработано: ${helper?.framesProcessed ?: 0}\n")
                    append("Рука в кадре: ${if (hand != null) "ДА (${hand.size} точек)" else "нет"}\n")
                    append("Palm Y: ${palmY?.let { "%.2f".format(it) } ?: "—"}\n")
                    append("Последний свайп: $lastSwipeText")
                    if (err != null) append("\nОшибка: $err")
                }
            }
        }

        val err0 = handHelper?.initError
        if (err0 != null) {
            binding.stats.text = "Модель не загрузилась:\n$err0"
        }

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            binding.previewView.scaleType = androidx.camera.view.PreviewView.ScaleType.FILL_CENTER
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(480, 640),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                    )
                )
                .build()

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setResolutionSelector(resolutionSelector)
                .build()
                .also { it.setAnalyzer(cameraExecutor) { proxy -> handHelper?.detect(proxy) ?: proxy.close() } }

            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        handHelper?.close()
    }
}
