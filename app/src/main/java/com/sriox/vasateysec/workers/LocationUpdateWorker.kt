package com.sriox.vasateysec.workers

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.location.LocationServices
import com.sriox.vasateysec.SupabaseClient
import com.sriox.vasateysec.models.LiveLocation
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.tasks.await

class LocationUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "LocationUpdateWorker"
    }

    override suspend fun doWork(): Result {
        return try {
            Log.d(TAG, "Starting automatic location update...")

            val currentUser = SupabaseClient.client.auth.currentUserOrNull()
            if (currentUser == null) {
                Log.w(TAG, "User not logged in, skipping location update")
                return Result.success()
            }

            if (android.content.pm.PackageManager.PERMISSION_GRANTED != 
                androidx.core.content.ContextCompat.checkSelfPermission(
                    applicationContext,
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                )) {
                Log.w(TAG, "Location permission not granted, skipping update")
                return Result.success()
            }

            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(applicationContext)
            val location = try {
                fusedLocationClient.lastLocation.await()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get location: ${e.message}")
                return Result.retry()
            }

            if (location == null) {
                Log.w(TAG, "Location is null, skipping update")
                return Result.success()
            }

            val timestamp = java.time.Instant.now().toString()

            // 1. Update users table
            try {
                SupabaseClient.client.from("users").update({
                    set("last_latitude", location.latitude)
                    set("last_longitude", location.longitude)
                    set("last_location_updated_at", timestamp)
                }) {
                    filter { eq("id", currentUser.id) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update users table: ${e.message}")
            }

            // 2. Update live_locations table (Fresh Sync)
            try {
                // Delete old to prevent duplicates and force Realtime event
                SupabaseClient.client.from("live_locations").delete {
                    filter { eq("user_id", currentUser.id) }
                }

                val liveLocation = LiveLocation(
                    user_id = currentUser.id,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracy = location.accuracy,
                    updated_at = timestamp
                )
                SupabaseClient.client.from("live_locations").insert(liveLocation)
                Log.d(TAG, "✅ Live location updated automatically")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update live_locations: ${e.message}")
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error: ${e.message}", e)
            Result.failure()
        }
    }
}
