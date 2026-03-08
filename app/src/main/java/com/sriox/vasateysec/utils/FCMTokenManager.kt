package com.sriox.vasateysec.utils

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.sriox.vasateysec.SupabaseClient
import com.sriox.vasateysec.models.FCMToken
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

object FCMTokenManager {
    private const val TAG = "FCMTokenManager"
    private const val PREFS_NAME = "fcm_prefs"
    private const val KEY_FCM_TOKEN = "fcm_token"

    /**
     * Initialize FCM and save token to Supabase
     */
    fun initializeFCM(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val token = FirebaseMessaging.getInstance().token.await()
                Log.d(TAG, "FCM Token obtained: ${token.take(20)}...")
                updateFCMToken(context, token)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get FCM token", e)
            }
        }
    }

    /**
     * Update FCM token in Supabase
     */
    fun updateFCMToken(context: Context, token: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "========================================")
                Log.d(TAG, "🔄 Updating FCM Token...")
                Log.d(TAG, "Token: ${token.take(30)}...")
                
                val currentUser = SupabaseClient.client.auth.currentUserOrNull()
                if (currentUser == null) {
                    Log.w(TAG, "❌ No user logged in, storing token locally locally only")
                    // Still save locally so we can sync it once user logs in
                    saveTokenLocally(context, token)
                    return@launch
                }

                Log.d(TAG, "✅ User logged in: ${currentUser.id}")
                val deviceId = getDeviceId(context)
                val deviceName = getDeviceName()
                Log.d(TAG, "📱 Device: $deviceName ($deviceId)")

                // Save token locally
                saveTokenLocally(context, token)
                Log.d(TAG, "💾 Token saved locally")

                // Check if this exact token already exists for this device
                Log.d(TAG, "🔍 Checking for existing token...")
                val existingTokens = try {
                    SupabaseClient.client.from("fcm_tokens")
                        .select {
                            filter {
                                eq("user_id", currentUser.id)
                                eq("device_id", deviceId)
                            }
                        }
                        .decodeList<FCMToken>()
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Error checking existing tokens: ${e.message}")
                    emptyList()
                }

                if (existingTokens.isNotEmpty()) {
                    val existingToken = existingTokens.first()
                    
                    if (existingToken.token == token) {
                        // Token unchanged, just update last_used_at
                        Log.d(TAG, "✅ Token already exists and is current, updating last_used_at")
                        try {
                            SupabaseClient.client.from("fcm_tokens").update({
                                set("last_used_at", java.time.Instant.now().toString())
                                set("is_active", true)
                            }) {
                                filter {
                                    eq("user_id", currentUser.id)
                                    eq("device_id", deviceId)
                                }
                            }
                            Log.d(TAG, "✅ Token refreshed successfully")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to update token timestamp: ${e.message}")
                        }
                    } else {
                        // Token changed for this device, delete old and insert new
                        Log.d(TAG, "🔄 Token changed for this device, updating...")
                        try {
                            SupabaseClient.client.from("fcm_tokens").delete {
                                filter {
                                    eq("user_id", currentUser.id)
                                    eq("device_id", deviceId)
                                }
                            }
                            Log.d(TAG, "🗑️ Deleted old token for this device")
                        } catch (e: Exception) {
                            Log.w(TAG, "⚠️ Error deleting old token: ${e.message}")
                        }

                        // Insert new token
                        val fcmToken = FCMToken(
                            user_id = currentUser.id,
                            token = token,
                            device_id = deviceId,
                            device_name = deviceName,
                            platform = "android",
                            is_active = true
                        )
                        SupabaseClient.client.from("fcm_tokens").insert(fcmToken)
                        Log.d(TAG, "✅ New token inserted for this device")
                    }
                } else {
                    // No existing token for this device, insert new
                    Log.d(TAG, "💾 No existing token, inserting new...")
                    val fcmToken = FCMToken(
                        user_id = currentUser.id,
                        token = token,
                        device_id = deviceId,
                        device_name = deviceName,
                        platform = "android",
                        is_active = true
                    )
                    SupabaseClient.client.from("fcm_tokens").insert(fcmToken)
                    Log.d(TAG, "✅ New FCM token saved to Supabase")
                }

                Log.d(TAG, "========================================")
                Log.d(TAG, "✅ FCM Token update complete!")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save FCM token to Supabase", e)
            }
        }
    }

    /**
     * Delete FCM token on logout
     */
    fun deactivateFCMToken(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val token = getTokenLocally(context) ?: return@launch

                // Delete the token from database
                SupabaseClient.client.from("fcm_tokens").delete {
                    filter {
                        eq("token", token)
                    }
                }

                clearTokenLocally(context)
                Log.d(TAG, "FCM token deleted")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete FCM token", e)
            }
        }
    }

    /**
     * Get FCM tokens for a list of guardian emails
     */
    suspend fun getGuardianTokens(guardianEmails: List<String>): List<Pair<String, String>> {
        return try {
            val tokens = mutableListOf<Pair<String, String>>()

            for (email in guardianEmails) {
                try {
                    // Get user ID from email
                    val users = try {
                        SupabaseClient.client.from("users")
                            .select {
                                filter {
                                    eq("email", email)
                                }
                            }
                            .decodeList<Map<String, String>>()
                    } catch (e: Exception) {
                        Log.w(TAG, "No user found for email $email: ${e.message}")
                        emptyList()
                    }

                    if (users.isNotEmpty()) {
                        val userId = users[0]["id"] ?: continue

                        // Get the latest active FCM token for this user (should only be one)
                        val fcmTokens = try {
                            SupabaseClient.client.from("fcm_tokens")
                                .select {
                                    filter {
                                        eq("user_id", userId)
                                        eq("is_active", true)
                                    }
                                }
                                .decodeList<FCMToken>()
                        } catch (e: Exception) {
                            Log.w(TAG, "No FCM tokens found for user $userId: ${e.message}")
                            emptyList()
                        }

                        // Only use the first active token (there should only be one)
                        if (fcmTokens.isNotEmpty()) {
                            val latestToken = fcmTokens.first()
                            tokens.add(Pair(email, latestToken.token))
                            Log.d(TAG, "Found token for guardian $email")
                        } else {
                            Log.w(TAG, "No active token found for guardian $email")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing guardian email $email", e)
                }
            }

            Log.d(TAG, "Retrieved ${tokens.size} FCM tokens for ${guardianEmails.size} guardians")
            tokens
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get guardian tokens", e)
            emptyList()
        }
    }

    private fun saveTokenLocally(context: Context, token: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FCM_TOKEN, token)
            .apply()
    }

    private fun getTokenLocally(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_FCM_TOKEN, null)
    }

    private fun clearTokenLocally(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_FCM_TOKEN)
            .apply()
    }

    private fun getDeviceId(context: Context): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    }

    private fun getDeviceName(): String {
        return "${Build.MANUFACTURER} ${Build.MODEL}"
    }
    
    /**
     * Deactivate a specific invalid FCM token
     */
    suspend fun deactivateInvalidToken(token: String) {
        try {
            Log.w(TAG, "Deactivating invalid FCM token: ${token.take(20)}...")
            
            // Delete the invalid token from database
            SupabaseClient.client.from("fcm_tokens").delete {
                filter {
                    eq("token", token)
                }
            }
            
            Log.d(TAG, "Invalid FCM token removed from database")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deactivate invalid token", e)
        }
    }
    
    /**
     * Mark token as validated (successfully sent notification)
     */
    suspend fun markTokenAsValidated(token: String) {
        try {
            Log.d(TAG, "Marking token as validated: ${token.take(20)}...")
            
            // Update last_validated_at and last_used_at
            SupabaseClient.client.from("fcm_tokens").update({
                set("last_validated_at", java.time.Instant.now().toString())
                set("last_used_at", java.time.Instant.now().toString())
            }) {
                filter {
                    eq("token", token)
                }
            }
            
            Log.d(TAG, "✅ Token marked as validated")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mark token as validated", e)
        }
    }
    
    /**
     * Get the current user's FCM token
     */
    suspend fun getCurrentUserToken(context: Context): String? {
        return try {
            val currentUser = SupabaseClient.client.auth.currentUserOrNull()
            if (currentUser == null) {
                Log.w(TAG, "No user logged in, cannot get FCM token")
                return null
            }
            
            // First try to get from local storage
            val localToken = getTokenLocally(context)
            if (localToken != null) {
                return localToken
            }
            
            // If not found locally, try to get from FCM
            val token = FirebaseMessaging.getInstance().token.await()
            if (token.isNotEmpty()) {
                saveTokenLocally(context, token)
                updateFCMToken(context, token)
                token
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get current user FCM token", e)
            null
        }
    }
}
