package com.example.wakewordassistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val audioGranted = grants[Manifest.permission.RECORD_AUDIO] == true
            val notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                grants[Manifest.permission.POST_NOTIFICATIONS] == true
            } else {
                true
            }

            if (audioGranted && notificationsGranted) {
                startVoiceService()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.startServiceButton).setOnClickListener {
            requestPermissionsAndStartService()
        }
    }

    private fun requestPermissionsAndStartService() {
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            startVoiceService()
        } else {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun startVoiceService() {
        val serviceIntent = Intent(this, VoiceService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
    }
}
