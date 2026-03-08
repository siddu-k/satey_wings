package com.sriox.vasateysec.workers

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.location.LocationServices
import com.sriox.vasateysec.SupabaseClient
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

            // Check if user is logged in
            val currentUser = SupabaseClient.client.auth.currentUserOrNull()
            if (currentUser == null) {
                Log.w(TAG, "User not logged in, skipping location update")
                return Result.success()
            }

            // Check location permission
            if (android.content.pm.PackageManager.PERMISSION_GRANTED != 
                androidx.core.content.ContextCompat.checkSelfPermission(
                    applicationContext,
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                )) {
                Log.w(TAG, "Location permission not granted, skipping update")
                return Result.success()
            }

            // Get location
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

            // Update users table
            try {
                SupabaseClient.client.from("users").update({
                    set("last_latitude", location.latitude)
                    set("last_longitude", location.longitude)
                    set("last_location_updated_at", java.time.Instant.now().toString())
                }) {
                    filter {
                        eq("id", currentUser.id)
                    }
                }

                Log.d(TAG, "✅ Location updated automatically: ${location.latitude}, ${location.longitude}")
                Result.success()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update location in database: ${e.message}")
                Result.retry()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in location update worker: ${e.message}", e)
            Result.failure()
        }
    }
}
