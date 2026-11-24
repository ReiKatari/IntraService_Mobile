package com.lantaservice.intra

import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lantaservice.intra.ui.IntraAppRoot
import com.lantaservice.intra.ui.theme.IntraTheme
import com.lantaservice.intra.util.LocationPermissionHelper

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        )

        val app = application as IntraApplication
        val repository = app.repository
        val appUpdateManager = app.appUpdateManager

        setContent {
            IntraTheme {
                val lifecycleOwner = LocalLifecycleOwner.current
                var locationGranted by remember {
                    mutableStateOf(LocationPermissionHelper.hasLocationPermission(this))
                }
                var pendingManualRetry by remember { mutableStateOf(false) }
                var shouldShowBeforeRetry by remember { mutableStateOf(false) }

                val locationLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) {
                    val grantedNow = LocationPermissionHelper.hasLocationPermission(this)
                    locationGranted = grantedNow
                    if (!grantedNow && pendingManualRetry) {
                        val canShowRationale = LocationPermissionHelper.shouldShowRationale(this)
                        if (!shouldShowBeforeRetry && !canShowRationale) {
                            Toast.makeText(
                                this,
                                "Разрешите местоположение в настройках приложения",
                                Toast.LENGTH_LONG
                            ).show()
                            LocationPermissionHelper.openAppSettings(this)
                        }
                    }
                    pendingManualRetry = false
                }

                // Request location permission and check GitHub releases silently at startup
                LaunchedEffect(Unit) {
                    if (!LocationPermissionHelper.hasLocationPermission(this@MainActivity)) {
                        locationLauncher.launch(LocationPermissionHelper.LOCATION_PERMISSIONS)
                    }
                }

                LaunchedEffect(Unit) {
                    appUpdateManager.checkForUpdateInBackground()
                }

                LaunchedEffect(Unit) {
                    repository.unauthorizedEvents.collect {
                        repository.handleUnauthorized()
                    }
                }

                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            locationGranted = LocationPermissionHelper.hasLocationPermission(this@MainActivity)
                            appUpdateManager.resumePendingInstallIfPermitted()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }

                IntraAppRoot(
                    repository = repository,
                    appUpdateManager = appUpdateManager,
                    locationGranted = locationGranted,
                    onRequestLocationAgain = {
                        shouldShowBeforeRetry = LocationPermissionHelper.shouldShowRationale(this)
                        pendingManualRetry = true
                        locationLauncher.launch(LocationPermissionHelper.LOCATION_PERMISSIONS)
                    },
                    onExitApp = {
                        repository.logoutImmediate()
                        finishAndRemoveTask()
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        // Always require login screen again once app closes (preserving configuration changes)
        if (isFinishing && !isChangingConfigurations) {
            (application as IntraApplication).repository.clearInMemorySession()
        }
        super.onDestroy()
    }
}
