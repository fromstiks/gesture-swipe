package com.example.gestureswipe

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Size
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.example.gestureswipe.databinding.ActivityPreviewBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Debug screen: live camera + tracked landmarks / motion line + running stats, plus a switch
 * to compare the HAND and MOTION engines. Runs the pipeline in the foreground (no service,
 * no accessibility) so we can see exactly what is detected.
 */
class PreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPreviewBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var swipeDetector: SwipeDetector

    private var handHelper: HandLandmarkerHelper? = null
    private var motionDetector: MotionDetector? = null
    private lateinit var mode: AppPrefs.Mode

    private val vibrator: Vibrator? by lazy { getSystemService(Vibrator::class.java) }
    private var lastSwipeText = "—"

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startCamera() else binding.stats.text = "Нет разрешения на камеру" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mode = AppPrefs.getMode(this)
        binding.modeSwitch.isChecked = mode == AppPrefs.Mode.MOTION
        updateModeLabel()
        binding.modeSwitch.setOnCheckedChangeListener { _, checked ->
            mode = if (checked) AppPrefs.Mode.MOTION else AppPrefs.Mode.HAND
            AppPrefs.setMode(this, mode)
            motionDetector?.reset()
            updateModeLabel()
        }

        swipeDetector = SwipeDetector { direction ->
            lastSwipeText = if (direction == SwipeDetector.Direction.UP) "▲ ВВЕРХ" else "▼ ВНИЗ"
            runOnUiThread {
                vibrator?.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }

        handHelper = HandLandmarkerHelper(this) { result ->
            if (mode == AppPrefs.Mode.HAND) {
                val hand = result.landmarks
                val palmY = hand?.getOrNull(HandLandmarkerHelper.PALM_LANDMARK)?.y()
                swipeDetector.onFrame(palmY)
                runOnUiThread {
                    binding.overlay.setResults(hand, result.imageWidth, result.imageHeight)
                    binding.stats.text = buildString {
                        append("Режим: РУКА\n")
                        append(if (handHelper?.isReady == true) "MediaPipe: готов\n" else "MediaPipe: НЕ готов\n")
                        append("Кадров: ${handHelper?.framesProcessed ?: 0}\n")
                        append("Рука: ${if (hand != null) "ДА (${hand.size})" else "нет"}\n")
                        append("Palm Y: ${palmY?.let { "%.2f".format(it) } ?: "—"}\n")
                        append("Свайп: $lastSwipeText")
                        handHelper?.initError?.let { append("\nОшибка: $it") }
                    }
                }
            }
        }

        motionDetector = MotionDetector { motionY ->
            if (mode == AppPrefs.Mode.MOTION) {
                swipeDetector.onFrame(motionY)
                runOnUiThread {
                    binding.overlay.setMotionY(motionY)
                    binding.stats.text = buildString {
                        append("Режим: ПАЛЕЦ/ДВИЖЕНИЕ\n")
                        append("Кадров: ${motionDetector?.framesProcessed ?: 0}\n")
                        append("Движение: ${motionDetector?.lastMotionAmount ?: 0} точек\n")
                        append("Motion Y: ${motionY?.let { "%.2f".format(it) } ?: "—"}\n")
                        append("Свайп: $lastSwipeText")
                    }
                }
            }
        }

        // Sensitivity slider — retunes the detectors live and persists for the service.
        val sens = AppPrefs.getSensitivity(this)
        applySensitivity(sens)
        binding.sensSlider.value = sens.toFloat()
        binding.sensLabel.text = "Чувствительность: $sens"
        binding.sensSlider.addOnChangeListener { _, value, _ ->
            val v = value.toInt()
            binding.sensLabel.text = "Чувствительность: $v"
            AppPrefs.setSensitivity(this, v)
            applySensitivity(v)
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

    private fun applySensitivity(sens: Int) {
        swipeDetector.minDelta = AppPrefs.minDelta(sens)
        motionDetector?.minMovingPixels = AppPrefs.minMovingPixels(sens)
    }

    private fun updateModeLabel() {
        binding.modeSwitch.text =
            if (mode == AppPrefs.Mode.MOTION) "Режим: Палец / движение (близко)"
            else "Режим: Рука (30–40 см)"
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            binding.previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
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
                .also {
                    it.setAnalyzer(cameraExecutor) { proxy ->
                        when (mode) {
                            AppPrefs.Mode.HAND -> handHelper?.detect(proxy) ?: proxy.close()
                            AppPrefs.Mode.MOTION -> motionDetector?.detect(proxy) ?: proxy.close()
                        }
                    }
                }

            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        handHelper?.close()
    }
}
