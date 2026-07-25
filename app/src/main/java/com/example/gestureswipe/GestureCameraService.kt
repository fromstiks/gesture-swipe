package com.example.gestureswipe

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Foreground service that keeps the front camera + detection running even after the user leaves
 * the app and opens TikTok. Detected swipes are forwarded to the AccessibilityService.
 *
 * Two detection engines, chosen via [AppPrefs]:
 *  - HAND: MediaPipe hand landmarks (needs the whole hand ~30-40cm away).
 *  - MOTION: frame-difference motion (works with a finger close to the camera).
 *
 * If overlay permission is granted, a small draggable camera preview floats over other apps so
 * the user can aim without flying blind.
 */
class GestureCameraService : LifecycleService() {

    companion object {
        private const val TAG = "GestureCameraService"
        private const val CHANNEL_ID = "gesture_camera_channel"
        private const val NOTIFICATION_ID = 42

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var swipeDetector: SwipeDetector
    private var handHelper: HandLandmarkerHelper? = null
    private var motionDetector: MotionDetector? = null
    private lateinit var mode: AppPrefs.Mode

    private val mainHandler = Handler(Looper.getMainLooper())
    private val vibrator: Vibrator? by lazy { getSystemService(Vibrator::class.java) }

    private var lastDetected: Boolean? = null
    private var lastStatusUpdate = 0L

    // Floating overlay
    private var windowManager: WindowManager? = null
    private var overlayRoot: View? = null
    private var overlayPreview: PreviewView? = null
    private var overlayHand: HandOverlayView? = null
    private var overlayStatus: TextView? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        mode = AppPrefs.getMode(this)

        swipeDetector = SwipeDetector { direction ->
            mainHandler.post {
                val a11y = SwipeAccessibilityService.instance
                if (a11y == null) {
                    Log.w(TAG, "Swipe detected but accessibility service is not enabled.")
                    buzzLong() // long buzz = swipe service OFF
                    setOverlayStatus("Свайп ВЫКЛ (вкл. службу)")
                    return@post
                }
                buzz() // short buzz = dispatched
                when (direction) {
                    SwipeDetector.Direction.UP -> a11y.swipeUp()
                    SwipeDetector.Direction.DOWN -> a11y.swipeDown()
                }
                setOverlayStatus(if (direction == SwipeDetector.Direction.UP) "▲ свайп вверх" else "▼ свайп вниз")
            }
        }

        setupEngines()
        setupOverlay()

        cameraExecutor = Executors.newSingleThreadExecutor()
        startCamera()

        isRunning = true
    }

    private fun setupEngines() {
        when (mode) {
            AppPrefs.Mode.HAND -> {
                handHelper = HandLandmarkerHelper(this) { result ->
                    val hand = result.landmarks
                    val palmY = hand?.getOrNull(HandLandmarkerHelper.PALM_LANDMARK)?.y()
                    updateDetected(palmY != null, if (palmY != null) "✋ рука" else "нет руки")
                    overlayHand?.let { ov -> mainHandler.post { ov.setResults(hand, result.imageWidth, result.imageHeight) } }
                    swipeDetector.onFrame(palmY)
                }
            }
            AppPrefs.Mode.MOTION -> {
                motionDetector = MotionDetector { motionY ->
                    updateDetected(motionY != null, if (motionY != null) "движение" else "тихо")
                    overlayHand?.let { ov -> mainHandler.post { ov.setMotionY(motionY) } }
                    swipeDetector.onFrame(motionY)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    private fun buzz() {
        vibrator?.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun buzzLong() {
        vibrator?.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()

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

                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    when (mode) {
                        AppPrefs.Mode.HAND -> handHelper?.detect(imageProxy) ?: imageProxy.close()
                        AppPrefs.Mode.MOTION -> motionDetector?.detect(imageProxy) ?: imageProxy.close()
                    }
                }

                val useCases = mutableListOf<androidx.camera.core.UseCase>(analysis)
                overlayPreview?.let { pv ->
                    val preview = Preview.Builder().build()
                    preview.setSurfaceProvider(pv.surfaceProvider)
                    useCases.add(preview)
                }

                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    *useCases.toTypedArray()
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ---------- Floating overlay ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun setupOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Log.i(TAG, "Overlay permission not granted; running without floating preview.")
            return
        }
        val wm = getSystemService(WindowManager::class.java) ?: return
        val root = LayoutInflater.from(this).inflate(R.layout.overlay_window, null)
        overlayPreview = root.findViewById(R.id.overlayPreview)
        overlayHand = root.findViewById(R.id.overlayHand)
        overlayStatus = root.findViewById(R.id.overlayStatus)

        val params = WindowManager.LayoutParams(
            dp(130), dp(200),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(90)
        }

        // Simple drag handling.
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        root.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY; startX = params.x; startY = params.y; true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (ev.rawX - downX).toInt()
                    params.y = startY + (ev.rawY - downY).toInt()
                    wm.updateViewLayout(root, params); true
                }
                else -> false
            }
        }

        try {
            wm.addView(root, params)
            windowManager = wm
            overlayRoot = root
        } catch (e: Exception) {
            Log.e(TAG, "addView overlay failed", e)
        }
    }

    private fun setOverlayStatus(text: String) {
        overlayStatus?.let { tv -> mainHandler.post { tv.text = text } }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------- Notification status ----------

    private fun buildNotification(text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateDetected(detected: Boolean, label: String) {
        setOverlayStatus(label)
        val now = SystemClock.uptimeMillis()
        if (detected == lastDetected && now - lastStatusUpdate < 1000L) return
        lastDetected = detected
        lastStatusUpdate = now
        val text = if (detected) "✋ Есть сигнал — маши вверх/вниз" else "Сигнала нет"
        mainHandler.post {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        val notification = buildNotification(getString(R.string.notif_text))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        isRunning = false
        try {
            ProcessCameraProvider.getInstance(this).get().unbindAll()
        } catch (_: Exception) { }
        overlayRoot?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        overlayRoot = null
        if (::cameraExecutor.isInitialized) cameraExecutor.shutdown()
        handHelper?.close()
        handHelper = null
        super.onDestroy()
    }
}
