package com.streamflixreborn.streamflix

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askNotificationPermission()

    }
    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (isGranted) {
                // Permission granted → you can post notifications
            } else {
                // Permission denied → disable or explain feature
            }
        }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    // Already granted
                }

                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    // Optional: show explanation UI before requesting again
                    requestPermissionLauncher.launch(
                        Manifest.permission.POST_NOTIFICATIONS
                    )
                }

                else -> {
                    requestPermissionLauncher.launch(
                        Manifest.permission.POST_NOTIFICATIONS
                    )
                }
            }
        } else {
            // No runtime permission needed (Android 12 and below)
        }
    }

}