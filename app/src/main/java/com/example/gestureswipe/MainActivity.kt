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
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
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
        AppCompatDelegate.setDefaultNightMode(AppPrefs.getNightMode(this))
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnThemeToggle.text = if (AppPrefs.isDark(this)) "🌙" else "☀️"
        binding.btnThemeToggle.setOnClickListener { toggleTheme() }

        // Setup accordion.
        binding.setupHeader.setOnClickListener {
            val open = binding.setupContent.visibility == View.VISIBLE
            binding.setupContent.visibility = if (open) View.GONE else View.VISIBLE
            binding.setupHeader.text =
                if (open) "⚙️  Первичная настройка   ▾" else "⚙️  Первичная настройка   ▴"
        }

        binding.btnGrantCamera.setOnClickListener { requestPermissions() }
        binding.btnOpenA11y.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.btnToggleService.setOnClickListener { toggleService() }
        binding.btnPreview.setOnClickListener {
            startActivity(Intent(this, PreviewActivity::class.java))
        }
        binding.btnOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    )
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun toggleTheme() {
        val newMode = if (AppPrefs.isDark(this)) {
            AppCompatDelegate.MODE_NIGHT_NO
        } else {
            AppCompatDelegate.MODE_NIGHT_YES
        }
        AppPrefs.setNightMode(this, newMode)
        AppCompatDelegate.setDefaultNightMode(newMode) // recreates the activity with the new theme
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

        setStatus(binding.statusCamera, camOk, "Камера — доступ выдан", "Камера — нет доступа")
        setStatus(binding.statusA11y, a11yOk, "Служба свайпов — включена", "Служба свайпов — выключена")
        setStatus(binding.statusService, running, "Распознавание — работает", "Распознавание — остановлено")

        binding.btnToggleService.text =
            getString(if (running) R.string.btn_stop else R.string.btn_start)

        val overlayOk = Settings.canDrawOverlays(this)
        val modeName = if (AppPrefs.getMode(this) == AppPrefs.Mode.MOTION) "Палец / движение" else "Рука"
        binding.modeInfo.text = buildString {
            append("Режим: $modeName (меняется в экране отладки)\n")
            append(if (overlayOk) "Окно поверх: разрешено" else "Окно поверх: нет (жми кнопку выше)")
        }
        binding.btnOverlay.isEnabled = !overlayOk
    }

    private fun setStatus(view: android.widget.TextView, ok: Boolean, onText: String, offText: String) {
        view.text = (if (ok) "✓  " else "✗  ") + (if (ok) onText else offText)
        view.setTextColor(
            ContextCompat.getColor(this, if (ok) R.color.status_good else R.color.status_bad)
        )
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
