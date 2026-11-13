package com.earthonline.direct

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val initialGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        setContent {
            EarthOnlineTheme {
                val granted = remember { mutableStateOf(initialGranted) }
                val denied = remember { mutableStateOf(false) }

                val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { ok ->
                    granted.value = ok
                    denied.value = !ok
                }

                androidx.compose.runtime.LaunchedEffect(Unit) {
                    if (!granted.value) launcher.launch(Manifest.permission.CAMERA)
                }

                val vm: MainViewModel = viewModel()
                AppScreen(
                    vm = vm,
                    hasCameraPermission = granted.value,
                    permissionDenied = denied.value,
                    requestPermission = { launcher.launch(Manifest.permission.CAMERA) }
                )
            }
        }
    }
}