package com.lantaservice.intra.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

enum class LocationAccessStatus {
    READY,
    PERMISSION_DENIED,
    SERVICE_DISABLED
}

/**
 * Verifies runtime location permissions and system Location toggle required for engineer access.
 */
object LocationPermissionHelper {

    val LOCATION_PERMISSIONS = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun shouldShowRationale(activity: Activity?): Boolean {
        if (activity == null) return false
        return LOCATION_PERMISSIONS.any { perm ->
            ActivityCompat.shouldShowRequestPermissionRationale(activity, perm)
        }
    }

    fun isLocationServiceEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                manager.isLocationEnabled
            } else {
                manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }
        }.getOrDefault(false)
    }

    fun evaluateLocationAccess(
        permissionGranted: Boolean,
        serviceEnabled: Boolean
    ): LocationAccessStatus {
        return when {
            !permissionGranted -> LocationAccessStatus.PERMISSION_DENIED
            !serviceEnabled -> LocationAccessStatus.SERVICE_DISABLED
            else -> LocationAccessStatus.READY
        }
    }

    fun isLocationFullyReady(context: Context): Boolean {
        return evaluateLocationAccess(
            permissionGranted = hasLocationPermission(context),
            serviceEnabled = isLocationServiceEnabled(context)
        ) == LocationAccessStatus.READY
    }

    fun openAppSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun openLocationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
