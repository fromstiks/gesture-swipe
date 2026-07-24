package com.example.gestureswipe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Foreground service that keeps the front camera + hand tracking running even after the user
 * leaves the app and opens TikTok. Detected swipes are forwarded to the AccessibilityService,
 * which performs them on the current foreground app.
 *
 * A LifecycleService is used so CameraX has a LifecycleOwner to bind to.
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
    private var handHelper: HandLandmarkerHelper? = null
    private lateinit var swipeDetector: SwipeDetector

    private val mainHandler = Handler(Looper.getMainLooper())
    private val vibrator: Vibrator? by lazy {
        getSystemService(Vibrator::class.java)
    }

    private var lastHandVisible: Boolean? = null
    private var lastStatusUpdate = 0L

    override fun onCreate() {
        super.onCreate()
        startAsForeground()

        // Detection runs on the camera background thread; hop to the main thread to
        // buzz + dispatch the gesture (dispatchGesture wants a Looper thread).
        swipeDetector = SwipeDetector { direction ->
            mainHandler.post {
                buzz()
                val a11y = SwipeAccessibilityService.instance
                if (a11y == null) {
                    Log.w(TAG, "Swipe detected but accessibility service is not enabled.")
                    return@post
                }
                when (direction) {
                    SwipeDetector.Direction.UP -> a11y.swipeUp()
                    SwipeDetector.Direction.DOWN -> a11y.swipeDown()
                }
            }
        }

        handHelper = HandLandmarkerHelper(this) { palmY ->
            updateHandStatus(palmY != null)
            swipeDetector.onFrame(palmY)
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
        startCamera()

        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    /** Short haptic tick so the user feels that a gesture registered. */
    private fun buzz() {
        vibrator?.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()

                // Lower resolution → much faster MediaPipe inference → less lag.
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
                    handHelper?.detect(imageProxy) ?: imageProxy.close()
                }

                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    analysis
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

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

    /**
     * DIAGNOSTIC: reflect whether MediaPipe currently sees a hand, in the notification.
     * Pull down the shade to check. Throttled so we don't spam the notification manager.
     */
    private fun updateHandStatus(handVisible: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (handVisible == lastHandVisible && now - lastStatusUpdate < 1000L) return
        lastHandVisible = handVisible
        lastStatusUpdate = now

        val text = if (handVisible) "✋ Рука в кадре — маши вверх/вниз" else "Руки не видно"
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
        if (::cameraExecutor.isInitialized) cameraExecutor.shutdown()
        handHelper?.close()
        handHelper = null
        super.onDestroy()
    }
}
