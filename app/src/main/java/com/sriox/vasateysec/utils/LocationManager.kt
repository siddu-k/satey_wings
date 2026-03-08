package com.sriox.vasateysec.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager as AndroidLocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

object LocationManager {
    private const val TAG = "LocationManager"
    private const val LOCATION_TIMEOUT_MS = 15000L // 15 seconds for emergency situations
    private const val RETRY_DELAY_MS = 2000L // 2 seconds between retries

    /**
     * Get current location with multiple fallback strategies
     */
    suspend fun getCurrentLocation(context: Context): Location? {
        Log.d(TAG, "========================================")
        Log.d(TAG, "getCurrentLocation called")
        
        if (!hasLocationPermission(context)) {
            Log.w(TAG, "Location permission not granted")
            return null
        }
        
        // Check if location services are enabled
        if (!isLocationEnabled(context)) {
            Log.w(TAG, "Location services are DISABLED on device")
            return null
        }
        
        Log.d(TAG, "Location permission granted and services enabled")

        val fusedLocationClient: FusedLocationProviderClient =
            LocationServices.getFusedLocationProviderClient(context)

        // Strategy 1: Try to get last known location (fast)
        val lastKnownLocation = getLastKnownLocationSync(fusedLocationClient)
        if (lastKnownLocation != null) {
            val age = System.currentTimeMillis() - lastKnownLocation.time
            Log.d(TAG, "Last known location: ${lastKnownLocation.latitude}, ${lastKnownLocation.longitude}, age: ${age}ms, accuracy: ${lastKnownLocation.accuracy}m")
            
            // If location is recent (less than 5 minutes old) and reasonably accurate, use it
            // Relaxed criteria for emergency situations
            if (age < 300000 && lastKnownLocation.accuracy < 200) {
                Log.d(TAG, "Using recent last known location (age: ${age/1000}s, accuracy: ${lastKnownLocation.accuracy}m)")
                return lastKnownLocation
            }
        }

        // Strategy 2: Request fresh location with timeout
        Log.d(TAG, "Requesting fresh location...")
        val freshLocation = withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
            getCurrentLocationWithRetry(fusedLocationClient)
        }
        
        if (freshLocation != null) {
            Log.d(TAG, "Fresh location obtained: ${freshLocation.latitude}, ${freshLocation.longitude}, accuracy: ${freshLocation.accuracy}m")
            Log.d(TAG, "========================================")
            return freshLocation
        }
        
        // Strategy 3: Try system LocationManager as fallback
        Log.d(TAG, "Trying system LocationManager as fallback...")
        val systemLocation = getSystemLastKnownLocation(context)
        if (systemLocation != null) {
            Log.d(TAG, "Got location from system LocationManager: ${systemLocation.latitude}, ${systemLocation.longitude}")
            Log.d(TAG, "========================================")
            return systemLocation
        }
        
        // Strategy 4: Try passive location provider (last location from any app)
        Log.d(TAG, "Trying passive location provider...")
        val passiveLocation = getPassiveLocation(context)
        if (passiveLocation != null) {
            Log.d(TAG, "Got location from passive provider: ${passiveLocation.latitude}, ${passiveLocation.longitude}")
            Log.d(TAG, "========================================")
            return passiveLocation
        }
        
        // Strategy 5: Fall back to last known location even if old
        if (lastKnownLocation != null) {
            val age = System.currentTimeMillis() - lastKnownLocation.time
            Log.w(TAG, "Using old last known location as final fallback (age: ${age/1000}s, accuracy: ${lastKnownLocation.accuracy}m)")
            Log.d(TAG, "========================================")
            return lastKnownLocation
        }
        
        Log.e(TAG, "Could not obtain any location from any source")
        Log.e(TAG, "Possible reasons:")
        Log.e(TAG, "  1. GPS is disabled or no GPS signal (indoor/blocked)")
        Log.e(TAG, "  2. Network location is unavailable (no WiFi/cell towers)")
        Log.e(TAG, "  3. Device has never recorded a location")
        Log.e(TAG, "  4. Google Play Services location is inactive")
        Log.d(TAG, "========================================")
        return null
    }
    
    /**
     * Get last known location synchronously
     */
    private suspend fun getLastKnownLocationSync(fusedLocationClient: FusedLocationProviderClient): Location? {
        return suspendCancellableCoroutine { continuation ->
            try {
                fusedLocationClient.lastLocation
                    .addOnSuccessListener { location ->
                        continuation.resume(location)
                    }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "Failed to get last known location: ${e.message}")
                        continuation.resume(null)
                    }
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception getting last location", e)
                continuation.resume(null)
            }
        }
    }
    
    /**
     * Get current location with retry logic
     * For emergency situations, prioritize accuracy over power consumption
     */
    private suspend fun getCurrentLocationWithRetry(fusedLocationClient: FusedLocationProviderClient): Location? {
        // Try HIGH_ACCURACY first for emergency situations (GPS + Network)
        // Give each attempt 5 seconds
        Log.d(TAG, "Attempt 1: HIGH_ACCURACY (GPS + Network)")
        var location = withTimeoutOrNull(5000L) {
            requestCurrentLocation(fusedLocationClient, Priority.PRIORITY_HIGH_ACCURACY)
        }
        if (location != null) return location
        
        // Wait and try balanced accuracy (faster, uses network primarily)
        delay(RETRY_DELAY_MS)
        Log.d(TAG, "Attempt 2: BALANCED_POWER_ACCURACY (Network + GPS)")
        location = withTimeoutOrNull(4000L) {
            requestCurrentLocation(fusedLocationClient, Priority.PRIORITY_BALANCED_POWER_ACCURACY)
        }
        if (location != null) return location
        
        // Last attempt with low power (network only, fastest)
        delay(RETRY_DELAY_MS)
        Log.d(TAG, "Attempt 3: LOW_POWER (Network only)")
        return withTimeoutOrNull(3000L) {
            requestCurrentLocation(fusedLocationClient, Priority.PRIORITY_LOW_POWER)
        }
    }
    
    /**
     * Request current location with specific priority
     */
    private suspend fun requestCurrentLocation(
        fusedLocationClient: FusedLocationProviderClient,
        priority: Int
    ): Location? {
        return suspendCancellableCoroutine { continuation ->
            var resumed = false
            
            try {
                val priorityName = when(priority) {
                    Priority.PRIORITY_HIGH_ACCURACY -> "HIGH_ACCURACY"
                    Priority.PRIORITY_BALANCED_POWER_ACCURACY -> "BALANCED"
                    Priority.PRIORITY_LOW_POWER -> "LOW_POWER"
                    else -> "UNKNOWN"
                }
                Log.d(TAG, "Requesting location with priority: $priorityName")
                
                val cancellationTokenSource = CancellationTokenSource()

                fusedLocationClient.getCurrentLocation(
                    priority,
                    cancellationTokenSource.token
                ).addOnSuccessListener { location ->
                    if (!resumed) {
                        resumed = true
                        if (location != null) {
                            Log.d(TAG, "Got location with $priorityName: ${location.latitude}, ${location.longitude}")
                        } else {
                            Log.w(TAG, "Location null with $priorityName")
                        }
                        continuation.resume(location)
                    }
                }.addOnFailureListener { e ->
                    if (!resumed) {
                        resumed = true
                        Log.e(TAG, "Failed with $priorityName: ${e.message}")
                        continuation.resume(null)
                    }
                }

                continuation.invokeOnCancellation {
                    cancellationTokenSource.cancel()
                }
            } catch (e: SecurityException) {
                if (!resumed) {
                    resumed = true
                    Log.e(TAG, "Security exception", e)
                    continuation.resume(null)
                }
            } catch (e: Exception) {
                if (!resumed) {
                    resumed = true
                    Log.e(TAG, "Exception: ${e.message}", e)
                    continuation.resume(null)
                }
            }
        }
    }

    /**
     * Get last known location from system LocationManager (fallback)
     */
    @Suppress("MissingPermission")
    private fun getSystemLastKnownLocation(context: Context): Location? {
        try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? AndroidLocationManager
            if (locationManager == null) {
                Log.w(TAG, "System LocationManager is null")
                return null
            }
            
            val locations = mutableListOf<Location>()
            
            // Try GPS provider
            try {
                val gpsLocation = locationManager.getLastKnownLocation(AndroidLocationManager.GPS_PROVIDER)
                if (gpsLocation != null) {
                    Log.d(TAG, "GPS location: ${gpsLocation.latitude}, ${gpsLocation.longitude}, age: ${(System.currentTimeMillis() - gpsLocation.time)/1000}s")
                    locations.add(gpsLocation)
                }
            } catch (e: Exception) {
                Log.w(TAG, "GPS provider error: ${e.message}")
            }
            
            // Try Network provider
            try {
                val networkLocation = locationManager.getLastKnownLocation(AndroidLocationManager.NETWORK_PROVIDER)
                if (networkLocation != null) {
                    Log.d(TAG, "Network location: ${networkLocation.latitude}, ${networkLocation.longitude}, age: ${(System.currentTimeMillis() - networkLocation.time)/1000}s")
                    locations.add(networkLocation)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Network provider error: ${e.message}")
            }
            
            // Return the most recent location
            val bestLocation = locations.maxByOrNull { it.time }
            if (bestLocation != null) {
                Log.d(TAG, "Using most recent system location: ${bestLocation.provider}")
                return bestLocation
            }
            
            Log.w(TAG, "No location from system LocationManager")
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error getting system location: ${e.message}", e)
            return null
        }
    }
    
    /**
     * Get location from passive provider (last location from any app)
     */
    @Suppress("MissingPermission")
    private fun getPassiveLocation(context: Context): Location? {
        try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? AndroidLocationManager
            if (locationManager == null) return null
            
            val passiveLocation = locationManager.getLastKnownLocation(AndroidLocationManager.PASSIVE_PROVIDER)
            if (passiveLocation != null) {
                val age = System.currentTimeMillis() - passiveLocation.time
                Log.d(TAG, "Passive location: ${passiveLocation.latitude}, ${passiveLocation.longitude}, age: ${age/1000}s, accuracy: ${passiveLocation.accuracy}m")
                return passiveLocation
            }
            return null
        } catch (e: Exception) {
            Log.w(TAG, "Error getting passive location: ${e.message}")
            return null
        }
    }
    
    /**
     * Check if location services are enabled on the device
     */
    private fun isLocationEnabled(context: Context): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? AndroidLocationManager
        return locationManager?.let {
            it.isProviderEnabled(AndroidLocationManager.GPS_PROVIDER) ||
            it.isProviderEnabled(AndroidLocationManager.NETWORK_PROVIDER)
        } ?: false
    }

    /**
     * Check if location permission is granted
     */
    fun hasLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }
}
