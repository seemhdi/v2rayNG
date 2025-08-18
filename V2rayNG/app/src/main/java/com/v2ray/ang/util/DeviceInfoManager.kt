package com.v2ray.ang.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.tasks.await

object DeviceInfoManager {

    /**
     * Gets the device manufacturer and model.
     */
    fun getDeviceName(): String {
        val manufacturer = Build.MANUFACTURER
        val model = Build.MODEL
        return if (model.startsWith(manufacturer)) {
            model
        } else {
            "$manufacturer $model"
        }
    }

    /**
     * Gets the current battery level as a percentage.
     * Returns -1 if the level cannot be determined.
     */
    fun getBatteryLevel(context: Context): Int {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    /**
     * Gets the current device location using the Fused Location Provider.
     * This is a suspend function that must be called from a coroutine.
     * It requires the ACCESS_FINE_LOCATION permission to be granted.
     *
     * @return A [Location] object on success, or null if permission is denied or location is unavailable.
     */
    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(context: Context): Location? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return null // Permission not granted
        }

        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

        // Try getting the last known location first, as it's fast.
        val lastLocation = fusedLocationClient.lastLocation.await()
        if (lastLocation != null) {
            return lastLocation
        }

        // If last location is not available, request the current location.
        // This can take a few seconds. We use a medium accuracy request.
        return try {
            val location = fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                CancellationTokenSource().token
            ).await()
            location
        } catch (e: Exception) {
            // Can happen if location services are disabled or there's another issue.
            null
        }
    }
}
