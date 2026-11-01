package com.evcompanion.core.location

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.evcompanion.core.debug.DebugFileLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Provides the vehicle's real-time geographic location.
 * When connected via Android Auto, the projection protocol feeds the car's exterior roof GNSS
 * antenna data directly into the system LocationManager.
 */
class VehicleLocationProvider(private val context: Context) {
    private val tag = "VehicleLocation"

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()

    private var isListening = false

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            _currentLocation.value = location
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    init {
        updateLastKnownLocation()
        startListening()
    }

    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    @SuppressLint("MissingPermission")
    fun updateLastKnownLocation(): Location? {
        if (!hasLocationPermission() || locationManager == null) return null
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        for (provider in providers) {
            try {
                if (locationManager.isProviderEnabled(provider)) {
                    val loc = locationManager.getLastKnownLocation(provider)
                    if (loc != null) {
                        _currentLocation.value = loc
                        return loc
                    }
                }
            } catch (e: Exception) {
                DebugFileLogger.w(tag, "Error checking provider $provider: ${e.message}")
            }
        }
        return _currentLocation.value
    }

    @SuppressLint("MissingPermission")
    fun startListening() {
        if (isListening || !hasLocationPermission() || locationManager == null) return
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    10000L,
                    50f,
                    locationListener
                )
                isListening = true
                DebugFileLogger.i(tag, "Started GPS location updates")
            } else if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    15000L,
                    100f,
                    locationListener
                )
                isListening = true
                DebugFileLogger.i(tag, "Started Network location updates")
            }
        } catch (e: Exception) {
            DebugFileLogger.w(tag, "Error starting location listener: ${e.message}")
        }
    }

    fun stopListening() {
        if (!isListening || locationManager == null) return
        try {
            locationManager.removeUpdates(locationListener)
            isListening = false
            DebugFileLogger.i(tag, "Stopped location updates")
        } catch (e: Exception) {
            DebugFileLogger.w(tag, "Error stopping location listener: ${e.message}")
        }
    }
}
