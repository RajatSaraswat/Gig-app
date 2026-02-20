package com.driver.profitcalculator

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Main entry point - handles permissions and starts Silent Eye service
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val REQUEST_MEDIA_PROJECTION = 1001
        const val REQUEST_OVERLAY_PERMISSION = 1002
    }

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (Settings.canDrawOverlays(this)) {
            requestMediaProjection()
        } else {
            Toast.makeText(this, "Overlay permission required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)

        startButton.setOnClickListener {
            checkPermissionsAndStart()
        }

        stopButton.setOnClickListener {
            stopService()
        }

        updateButtonStates()
    }

    private fun checkPermissionsAndStart() {
        when {
            !Settings.canDrawOverlays(this) -> requestOverlayPermission()
            else -> requestMediaProjection()
        }
    }

    /**
     * Request SYSTEM_ALERT_WINDOW permission for ghost overlay
     */
    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        overlayPermissionLauncher.launch(intent)
    }

    /**
     * Request MediaProjection for screen capture
     */
    private fun requestMediaProjection() {
        val intent = mediaProjectionManager.createScreenCaptureIntent()
        startActivityForResult(intent, REQUEST_MEDIA_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                startCaptureService(resultCode, data)
            } else {
                Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startCaptureService(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra("resultCode", resultCode)
            putExtra("data", data)
        }
        
        ContextCompat.startForegroundService(this, serviceIntent)
        Toast.makeText(this, "Profit calculator started", Toast.LENGTH_SHORT).show()
        updateButtonStates()
    }

    private fun stopService() {
        stopService(Intent(this, ScreenCaptureService::class.java))
        Toast.makeText(this, "Profit calculator stopped", Toast.LENGTH_SHORT).show()
        updateButtonStates()
    }

    private fun updateButtonStates() {
        val isRunning = isServiceRunning(ScreenCaptureService::class.java)
        startButton.isEnabled = !isRunning
        stopButton.isEnabled = isRunning
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        return manager.getRunningServices(Integer.MAX_VALUE)
            .any { it.service.className == serviceClass.name }
    }
}
