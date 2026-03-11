package com.sriox.vasateysec.utils

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.sriox.vasateysec.SupabaseClient
import com.sriox.vasateysec.models.FCMToken
import com.sriox.vasateysec.models.UserProfile
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
                
                val currentUser = SupabaseClient.client.auth.currentUserOrNull()
                if (currentUser == null) {
                    Log.w(TAG, "❌ No user logged in, storing token locally only")
                    saveTokenLocally(context, token)
                    return@launch
                }

                val deviceId = getDeviceId(context)
                val deviceName = getDeviceName()

                saveTokenLocally(context, token)

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
                    emptyList()
                }

                if (existingTokens.isNotEmpty()) {
                    val existingToken = existingTokens.first()
                    
                    if (existingToken.token == token) {
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
                        } catch (e: Exception) { }
                    } else {
                        try {
                            SupabaseClient.client.from("fcm_tokens").delete {
                                filter {
                                    eq("user_id", currentUser.id)
                                    eq("device_id", deviceId)
                                }
                            }
                        } catch (e: Exception) { }

                        val fcmToken = FCMToken(
                            user_id = currentUser.id,
                            token = token,
                            device_id = deviceId,
                            device_name = deviceName,
                            platform = "android",
                            is_active = true
                        )
                        SupabaseClient.client.from("fcm_tokens").insert(fcmToken)
                    }
                } else {
                    val fcmToken = FCMToken(
                        user_id = currentUser.id,
                        token = token,
                        device_id = deviceId,
                        device_name = deviceName,
                        platform = "android",
                        is_active = true
                    )
                    SupabaseClient.client.from("fcm_tokens").insert(fcmToken)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save FCM token", e)
            }
        }
    }

    fun deactivateFCMToken(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val token = getTokenLocally(context) ?: return@launch
                SupabaseClient.client.from("fcm_tokens").delete { filter { eq("token", token) } }
                clearTokenLocally(context)
            } catch (e: Exception) { }
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
                    // FIX: Using UserProfile model instead of Map<String, String> to handle nulls
                    val userProfiles = try {
                        SupabaseClient.client.from("users")
                            .select {
                                filter {
                                    eq("email", email)
                                }
                            }
                            .decodeList<UserProfile>()
                    } catch (e: Exception) {
                        Log.w(TAG, "No user found for email $email: ${e.message}")
                        emptyList()
                    }

                    if (userProfiles.isNotEmpty()) {
                        val userId = userProfiles[0].id

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
                            emptyList()
                        }

                        if (fcmTokens.isNotEmpty()) {
                            tokens.add(Pair(email, fcmTokens.first().token))
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing guardian $email", e)
                }
            }
            tokens
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveTokenLocally(context: Context, token: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_FCM_TOKEN, token).apply()
    }

    private fun getTokenLocally(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_FCM_TOKEN, null)
    }

    private fun clearTokenLocally(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().remove(KEY_FCM_TOKEN).apply()
    }

    private fun getDeviceId(context: Context): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    }

    private fun getDeviceName(): String {
        return "${Build.MANUFACTURER} ${Build.MODEL}"
    }
    
    suspend fun deactivateInvalidToken(token: String) {
        try {
            SupabaseClient.client.from("fcm_tokens").delete { filter { eq("token", token) } }
        } catch (e: Exception) { }
    }
    
    suspend fun markTokenAsValidated(token: String) {
        try {
            SupabaseClient.client.from("fcm_tokens").update({
                set("last_used_at", java.time.Instant.now().toString())
            }) { filter { eq("token", token) } }
        } catch (e: Exception) { }
    }
}
