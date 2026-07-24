package com.example.gestureswipe

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.gestureswipe.databinding.ActivityMainBinding

/**
 * Setup screen. Walks the user through the three prerequisites:
 *  1. Camera permission (+ notifications on Android 13+).
 *  2. Enabling the accessibility service (opens system settings — must be done by hand).
 *  3. Starting / stopping the foreground detection service.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnGrantCamera.setOnClickListener { requestPermissions() }
        binding.btnOpenA11y.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.btnToggleService.setOnClickListener { toggleService() }
        binding.btnPreview.setOnClickListener {
            startActivity(Intent(this, PreviewActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun toggleService() {
        if (GestureCameraService.isRunning) {
            stopService(Intent(this, GestureCameraService::class.java))
        } else {
            if (!hasCameraPermission()) {
                requestPermissions()
                return
            }
            ContextCompat.startForegroundService(
                this,
                Intent(this, GestureCameraService::class.java)
            )
        }
        // Give the service a moment to flip its running flag before we re-read status.
        binding.root.postDelayed({ refreshStatus() }, 300)
    }

    private fun refreshStatus() {
        val camOk = hasCameraPermission()
        val a11yOk = isAccessibilityServiceEnabled()
        val running = GestureCameraService.isRunning

        binding.statusCamera.text =
            getString(if (camOk) R.string.status_camera_on else R.string.status_camera_off)
        binding.statusA11y.text =
            getString(if (a11yOk) R.string.status_a11y_on else R.string.status_a11y_off)
        binding.statusService.text =
            getString(if (running) R.string.status_service_on else R.string.status_service_off)

        binding.btnToggleService.text =
            getString(if (running) R.string.btn_stop else R.string.btn_start)
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(this, SwipeAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected, ignoreCase = true)) return true
        }
        return false
    }
}
