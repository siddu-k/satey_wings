package com.sriox.vasateysec.utils

import android.content.Context
import android.util.Log
import com.sriox.vasateysec.SupabaseClient
import com.sriox.vasateysec.models.AlertHistory
import com.sriox.vasateysec.models.AlertRecipient
import com.sriox.vasateysec.models.Guardian
import com.sriox.vasateysec.models.UserProfile
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

object AlertManager {
    private const val TAG = "AlertManager"

    /**
     * Send emergency alert to all guardians
     */
    suspend fun sendEmergencyAlert(
        context: Context,
        latitude: Double?,
        longitude: Double?,
        locationAccuracy: Float? = null,
        frontPhotoFile: File? = null,
        backPhotoFile: File? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "sendEmergencyAlert called with location: lat=$latitude, lon=$longitude, accuracy=$locationAccuracy")
            
            // Try to get current user from Supabase auth
            val currentUser = SupabaseClient.client.auth.currentUserOrNull()
            
            // If Supabase auth is not available (e.g., when app is closed), use SessionManager
            val userId: String
            val userName: String
            val userEmail: String
            val userPhone: String
            
            if (currentUser != null) {
                // Supabase session is available
                Log.d(TAG, "Using Supabase session for user: ${currentUser.id}")
                userId = currentUser.id
                
                // Get user profile from database
                val userProfile = try {
                    SupabaseClient.client.from("users")
                        .select {
                            filter {
                                eq("id", currentUser.id)
                            }
                        }
                        .decodeSingle<UserProfile>()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to fetch user profile from DB: ${e.message}")
                    UserProfile(id = userId, name = SessionManager.getUserName() ?: "Unknown", email = currentUser.email ?: "")
                }

                userName = userProfile.name ?: "Unknown"
                userEmail = userProfile.email ?: ""
                userPhone = userProfile.phone ?: ""
            } else {
                // Fallback to SessionManager (for when app is closed but service is running)
                Log.d(TAG, "Supabase session not available, using SessionManager")
                
                if (!SessionManager.isLoggedIn()) {
                    return@withContext Result.failure(Exception("User not logged in"))
                }
                
                userId = SessionManager.getUserId() ?: return@withContext Result.failure(Exception("User ID not found in session"))
                userName = SessionManager.getUserName() ?: "Unknown"
                userEmail = SessionManager.getUserEmail() ?: ""
                
                // Get user phone from database using userId
                userPhone = try {
                    val userProfile = SupabaseClient.client.from("users")
                        .select {
                            filter {
                                eq("id", userId)
                            }
                        }
                        .decodeSingle<UserProfile>()
                    userProfile.phone ?: ""
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to fetch phone from database: ${e.message}")
                    ""
                }
            }
            
            Log.d(TAG, "Sending alert for user: $userName ($userEmail)")

            // Upload photos to Supabase Storage if available
            var frontPhotoUrl: String? = null
            var backPhotoUrl: String? = null
            
            try {
                withTimeout(15000L) {
                    // Launch both uploads in parallel
                    val frontJob = async {
                        if (frontPhotoFile != null && frontPhotoFile.exists()) {
                            uploadPhotoToStorage(userId, frontPhotoFile, "front")
                        } else null
                    }
                    
                    val backJob = async {
                        if (backPhotoFile != null && backPhotoFile.exists()) {
                            uploadPhotoToStorage(userId, backPhotoFile, "back")
                        } else null
                    }
                    
                    frontPhotoUrl = frontJob.await()
                    backPhotoUrl = backJob.await()
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "⏱️ Photo upload timeout")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Photo upload error: ${e.message}")
            }

            // Create alert history record
            val alertHistory = AlertHistory(
                user_id = userId,
                user_name = userName,
                user_email = userEmail,
                user_phone = userPhone,
                latitude = latitude,
                longitude = longitude,
                location_accuracy = locationAccuracy,
                alert_type = "voice_help",
                status = "sent",
                front_photo_url = frontPhotoUrl,
                back_photo_url = backPhotoUrl
            )
            
            val insertResponse = SupabaseClient.client.from("alert_history")
                .insert(alertHistory) {
                    select()
                }

            val insertedAlert = try {
                insertResponse.decodeSingle<AlertHistory>()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decode inserted alert: ${e.message}")
                return@withContext Result.failure(Exception("Failed to create alert record"))
            }

            val alertId = insertedAlert.id 
                ?: return@withContext Result.failure(Exception("Alert created but no ID returned"))
            
            // Clean up old alerts
            cleanupOldAlerts(userId)

            // Get guardians
            val guardians = try {
                SupabaseClient.client.from("guardians")
                    .select {
                        filter {
                            eq("user_id", userId)
                            eq("status", "active")
                        }
                    }
                    .decodeList<Guardian>()
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching guardians: ${e.message}")
                emptyList<Guardian>()
            }
                
            if (guardians.isEmpty()) {
                Log.w(TAG, "No active guardians found")
                return@withContext Result.failure(Exception("No guardians found. Please add guardians first."))
            }

            val guardianEmails = guardians.map { it.guardian_email }
            Log.d(TAG, "Sending alerts to ${guardianEmails.size} guardians")

            // Get FCM tokens for guardians
            val guardianTokens = try {
                val tokenPairs = FCMTokenManager.getGuardianTokens(guardianEmails)
                
                val tokensMap = mutableMapOf<String, Map<String, String>>()
                for ((email, token) in tokenPairs) {
                    val guardianInfo = guardians.find { it.guardian_email == email }
                    if (guardianInfo != null) {
                        // FIX: guardianUserId must be a valid UUID or null. DO NOT use email as fallback.
                        val guardianUserId = guardianInfo.guardian_user_id
                        tokensMap[guardianUserId ?: email] = mapOf(
                            "token" to token,
                            "email" to email,
                            "user_id" to (guardianUserId ?: "")
                        )
                    }
                }
                tokensMap
            } catch (e: Exception) {
                Log.e(TAG, "Error getting guardian tokens: ${e.message}")
                emptyMap<String, Map<String, String>>()
            }
            
            if (guardianTokens.isEmpty()) {
                Log.w(TAG, "No FCM tokens found for guardians")
                return@withContext Result.failure(Exception("No active guardians found with the app installed"))
            }
            
            var successCount = 0
            var failedCount = 0
            
            // Send notifications
            for ((key, tokenInfo) in guardianTokens) {
                val guardianEmail = tokenInfo["email"] ?: ""
                val token = tokenInfo["token"] ?: continue
                val guardianUserId = tokenInfo["user_id"].takeIf { it?.isNotEmpty() == true }
                
                val title = "🚨 $userName needs help!"
                val body = "$userName has triggered an emergency alert. Tap to view their location."
                
                val success = sendNotificationToSupabase(
                    alertId = alertId,
                    token = token,
                    title = title,
                    body = body,
                    userEmail = userEmail,
                    guardianEmail = guardianEmail,
                    userName = userName,
                    userPhone = userPhone,
                    latitude = latitude,
                    longitude = longitude,
                    frontPhotoUrl = frontPhotoUrl,
                    backPhotoUrl = backPhotoUrl,
                    alertIdForIntent = alertId
                )

                if (success) {
                    successCount++
                    // Create alert recipient record ONLY if notification was dispatched
                    try {
                        val recipient = AlertRecipient(
                            alert_id = alertId,
                            guardian_email = guardianEmail,
                            guardian_user_id = guardianUserId, // Now safely passing valid UUID or null
                            fcm_token = token,
                            notification_sent = true,
                            notification_delivered = false
                        )
                        
                        SupabaseClient.client.from("alert_recipients").insert(recipient)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to create recipient record for $guardianEmail: ${e.message}")
                    }
                } else {
                    failedCount++
                }
            }

            if (successCount > 0) {
                Result.success("Alert sent to $successCount guardian(s)")
            } else {
                Result.failure(Exception("Failed to reach any guardians"))
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to send emergency alert", e)
            Result.failure(e)
        }
    }

    private suspend fun sendNotificationToSupabase(
        alertId: String,
        token: String,
        title: String,
        body: String,
        userEmail: String,
        guardianEmail: String,
        userName: String,
        userPhone: String,
        latitude: Double?,
        longitude: Double?,
        frontPhotoUrl: String? = null,
        backPhotoUrl: String? = null,
        alertIdForIntent: String
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val client = okhttp3.OkHttpClient()
                
                val jsonBody = """
                    {
                        "token": "$token",
                        "title": "$title",
                        "body": "$body",
                        "email": "$userEmail",
                        "isSelfAlert": false,
                        "fullName": "$userName",
                        "phoneNumber": "$userPhone",
                        "lastKnownLatitude": ${latitude ?: "null"},
                        "lastKnownLongitude": ${longitude ?: "null"},
                        "frontPhotoUrl": ${if (frontPhotoUrl != null) "\"$frontPhotoUrl\"" else "null"},
                        "backPhotoUrl": ${if (backPhotoUrl != null) "\"$backPhotoUrl\"" else "null"},
                        "alertId": "$alertIdForIntent"
                    }
                """.trimIndent()
                
                val requestBody = okhttp3.RequestBody.create(null, jsonBody)
                
                val request = okhttp3.Request.Builder()
                    .url("https://vasatey-notify-msg.vercel.app/api/sendNotification")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    FCMTokenManager.markTokenAsValidated(token)
                    true
                } else false
            } catch (e: Exception) {
                false
            }
        }
    }
    
    private suspend fun uploadPhotoToStorage(userId: String, photoFile: File, cameraType: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val timestamp = System.currentTimeMillis()
                val fileName = "emergency_${userId}_${cameraType}_${timestamp}.jpg"
                val bucketName = "emergency-photos"
                
                val bucket = SupabaseClient.client.storage.from(bucketName)
                bucket.upload(path = fileName, data = photoFile.readBytes(), upsert = true)
                
                return@withContext bucket.publicUrl(fileName)
            } catch (e: Exception) {
                return@withContext null
            }
        }
    }
    
    private suspend fun cleanupOldAlerts(userId: String) {
        try {
            val allAlerts = SupabaseClient.client.from("alert_history")
                .select { filter { eq("user_id", userId) } }
                .decodeList<AlertHistory>()
                .sortedByDescending { it.created_at }
            
            if (allAlerts.size > 10) {
                val alertsToDelete = allAlerts.drop(10)
                alertsToDelete.forEach { alert ->
                    alert.id?.let { alertId ->
                        try {
                            SupabaseClient.client.from("alert_recipients").delete { filter { eq("alert_id", alertId) } }
                            SupabaseClient.client.from("alert_history").delete { filter { eq("id", alertId) } }
                        } catch (e: Exception) { }
                    }
                }
            }
        } catch (e: Exception) { }
    }
}
