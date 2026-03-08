package com.sriox.vasateysec.workers

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.messaging.FirebaseMessaging
import com.sriox.vasateysec.SupabaseClient
import com.sriox.vasateysec.utils.FCMTokenManager
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.tasks.await

class FCMTokenRefreshWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "FCMTokenRefreshWorker"
    }

    override suspend fun doWork(): Result {
        return try {
            Log.d(TAG, "Starting FCM token refresh and cleanup...")

            // Check if user is logged in
            val currentUser = SupabaseClient.client.auth.currentUserOrNull()
            if (currentUser == null) {
                Log.w(TAG, "User not logged in, skipping token refresh")
                return Result.success()
            }

            // 1. Refresh current device's token
            try {
                val token = FirebaseMessaging.getInstance().token.await()
                Log.d(TAG, "Refreshed FCM token: ${token.take(20)}...")
                FCMTokenManager.updateFCMToken(applicationContext, token)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh FCM token: ${e.message}")
            }

            // 2. Clean up stale tokens (not validated in 30+ days)
            try {
                val thirtyDaysAgo = java.time.Instant.now().minus(30, java.time.temporal.ChronoUnit.DAYS).toString()
                
                // Delete tokens that haven't been validated in 30 days
                // For now, just delete tokens with last_used_at older than 30 days
                val deletedCount = SupabaseClient.client.from("fcm_tokens").delete {
                    filter {
                        eq("user_id", currentUser.id)
                        lt("last_used_at", thirtyDaysAgo)
                    }
                }
                
                Log.d(TAG, "Cleaned up stale tokens for user ${currentUser.id}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clean up stale tokens: ${e.message}")
            }

            Log.d(TAG, "✅ FCM token refresh and cleanup complete")
            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in FCM token refresh worker: ${e.message}", e)
            Result.failure()
        }
    }
}
