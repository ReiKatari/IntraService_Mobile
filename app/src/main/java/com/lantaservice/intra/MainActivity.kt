package com.lantaservice.intra

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat
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
                var locationPermissionGranted by remember {
                    mutableStateOf(LocationPermissionHelper.hasLocationPermission(this))
                }
                var locationServiceEnabled by remember {
                    mutableStateOf(LocationPermissionHelper.isLocationServiceEnabled(this))
                }
                var pendingManualRetry by remember { mutableStateOf(false) }
                var shouldShowBeforeRetry by remember { mutableStateOf(false) }

                val locationLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) {
                    val grantedNow = LocationPermissionHelper.hasLocationPermission(this)
                    val serviceNow = LocationPermissionHelper.isLocationServiceEnabled(this)
                    locationPermissionGranted = grantedNow
                    locationServiceEnabled = serviceNow
                    if (grantedNow && serviceNow) {
                        app.geoLocationEngine.warmBreadcrumbAsync()
                    }
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
                    } else if (LocationPermissionHelper.isLocationServiceEnabled(this@MainActivity)) {
                        app.geoLocationEngine.warmBreadcrumbAsync()
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
                            val hasLoc = LocationPermissionHelper.hasLocationPermission(this@MainActivity)
                            val svcEnabled = LocationPermissionHelper.isLocationServiceEnabled(this@MainActivity)
                            locationPermissionGranted = hasLoc
                            locationServiceEnabled = svcEnabled
                            if (hasLoc && svcEnabled) {
                                app.geoLocationEngine.warmBreadcrumbAsync()
                            }
                            appUpdateManager.resumePendingInstallIfPermitted()
                            appUpdateManager.triggerCheckForUpdateAsync(force = false)
                            repository.triggerOutboxSyncAsync()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }

                // Live listener for Android Location switch toggle while app is active
                DisposableEffect(Unit) {
                    val locationReceiver = object : BroadcastReceiver() {
                        override fun onReceive(ctx: Context?, intent: Intent?) {
                            val hasPerm = LocationPermissionHelper.hasLocationPermission(this@MainActivity)
                            val svcEnabled = LocationPermissionHelper.isLocationServiceEnabled(this@MainActivity)
                            locationPermissionGranted = hasPerm
                            locationServiceEnabled = svcEnabled
                            if (hasPerm && svcEnabled) {
                                app.geoLocationEngine.warmBreadcrumbAsync()
                            }
                        }
                    }
                    val filter = IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION).apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            addAction(LocationManager.MODE_CHANGED_ACTION)
                        }
                    }
                    ContextCompat.registerReceiver(
                        this@MainActivity,
                        locationReceiver,
                        filter,
                        ContextCompat.RECEIVER_NOT_EXPORTED
                    )
                    onDispose {
                        runCatching { unregisterReceiver(locationReceiver) }
                    }
                }

                DisposableEffect(Unit) {
                    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    val networkCallback = object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            repository.triggerOutboxSyncAsync()
                            appUpdateManager.triggerCheckForUpdateAsync(force = false)
                        }

                        override fun onCapabilitiesChanged(
                            network: Network,
                            capabilities: NetworkCapabilities
                        ) {
                            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                                repository.triggerOutboxSyncAsync()
                                appUpdateManager.triggerCheckForUpdateAsync(force = false)
                            }
                        }
                    }
                    val registered = runCatching {
                        cm.registerDefaultNetworkCallback(networkCallback)
                        true
                    }.getOrDefault(false)
                    onDispose {
                        if (registered) {
                            runCatching { cm.unregisterNetworkCallback(networkCallback) }
                        }
                    }
                }

                IntraAppRoot(
                    repository = repository,
                    appUpdateManager = appUpdateManager,
                    locationPermissionGranted = locationPermissionGranted,
                    locationServiceEnabled = locationServiceEnabled,
                    onRequestLocationAgain = {
                        shouldShowBeforeRetry = LocationPermissionHelper.shouldShowRationale(this)
                        pendingManualRetry = true
                        locationLauncher.launch(LocationPermissionHelper.LOCATION_PERMISSIONS)
                    },
                    onOpenLocationSettings = {
                        LocationPermissionHelper.openLocationSettings(this)
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
