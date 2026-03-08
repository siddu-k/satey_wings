package com.sriox.vasateysec.utils

import android.content.Context
import android.util.Log
import com.sriox.vasateysec.SupabaseClient
import com.sriox.vasateysec.models.AlertConfirmation
import com.sriox.vasateysec.models.AlertHistory
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.*

object AlertConfirmationManager {
    private const val TAG = "AlertConfirmationMgr"

    /**
     * Guardian confirms the alert - sends notification back to user via Supabase Edge Function
     */
    suspend fun confirmAlert(
        context: Context,
        alertId: String,
        guardianEmail: String,
        guardianUserId: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Guardian $guardianEmail confirming alert $alertId")

            // Call Supabase Edge Function via HTTP
            val client = okhttp3.OkHttpClient()
            
            val jsonBody = """
                {
                    "alertId": "$alertId",
                    "guardianEmail": "$guardianEmail",
                    "guardianUserId": "$guardianUserId"
                }
            """.trimIndent()
            
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = jsonBody.toRequestBody(mediaType)
            
            val request = okhttp3.Request.Builder()
                .url("https://acgsmcxmesvsftzugeik.supabase.co/functions/v1/send-confirmation-notification")
                .addHeader("Authorization", "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImFjZ3NtY3htZXN2c2Z0enVnZWlrIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NjIyNzIzNTYsImV4cCI6MjA3Nzg0ODM1Nn0.EwiJajiscMqz1jHyyl-BDS4YIvc0nihBUn3m8pPUP1c")
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()
            
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            
            if (response.isSuccessful) {
                Log.d(TAG, "✅ Confirmation sent via Supabase Edge Function")
                Result.success("Alert confirmed. User has 30 seconds to cancel.")
            } else {
                Log.e(TAG, "Failed to send confirmation: $responseBody")
                Result.failure(Exception("Failed to send confirmation: ${response.code}"))
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to confirm alert", e)
            Result.failure(e)
        }
    }

    /**
     * User cancels the alert with password
     */
    suspend fun cancelAlert(
        context: Context,
        alertId: String,
        guardianEmail: String,
        password: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "User attempting to cancel alert $alertId")

            // Get current user
            val currentUser = SupabaseClient.client.auth.currentUserOrNull()
                ?: return@withContext Result.failure(Exception("User not logged in"))

            // Verify password
            val userProfile = SupabaseClient.client.from("users")
                .select {
                    filter {
                        eq("id", currentUser.id)
                    }
                }
                .decodeSingle<Map<String, String>>()

            val savedPassword = userProfile["cancel_password"] ?: ""
            
            if (savedPassword.isEmpty()) {
                return@withContext Result.failure(Exception("No cancel password set. Please set one in your profile."))
            }

            if (password != savedPassword) {
                return@withContext Result.failure(Exception("Incorrect password"))
            }

            Log.d(TAG, "Password verified, cancelling alert")

            // Check if confirmation exists and is not expired
            val confirmations = SupabaseClient.client.from("alert_confirmations")
                .select {
                    filter {
                        eq("alert_id", alertId)
                        eq("guardian_email", guardianEmail)
                    }
                }
                .decodeList<AlertConfirmation>()

            if (confirmations.isEmpty()) {
                return@withContext Result.failure(Exception("No confirmation found"))
            }

            val confirmation = confirmations.first()

            // Check if expired
            if (confirmation.confirmation_status == "expired") {
                return@withContext Result.failure(Exception("Confirmation has expired"))
            }

            // Check if already cancelled
            if (confirmation.confirmation_status == "cancelled") {
                return@withContext Result.failure(Exception("Alert already cancelled"))
            }

            // Check if within 30 seconds
            val expiresAt = parseDate(confirmation.expires_at ?: "")
            if (expiresAt != null && Date().after(expiresAt)) {
                // Update to expired
                SupabaseClient.client.from("alert_confirmations")
                    .update(mapOf("confirmation_status" to "expired")) {
                        filter {
                            eq("id", confirmation.id ?: "")
                        }
                    }
                return@withContext Result.failure(Exception("Time expired. You had 30 seconds to cancel."))
            }

            // Update confirmation status to cancelled
            val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            dateFormat.timeZone = TimeZone.getTimeZone("UTC")
            
            SupabaseClient.client.from("alert_confirmations")
                .update(mapOf(
                    "confirmation_status" to "cancelled",
                    "cancelled_at" to dateFormat.format(Date())
                )) {
                    filter {
                        eq("id", confirmation.id ?: "")
                    }
                }

            Log.d(TAG, "✅ Alert cancelled successfully")

            // Send notification back to guardian
            sendCancellationNotification(guardianEmail, alertId)

            Result.success("Alert cancelled successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel alert", e)
            Result.failure(e)
        }
    }

    /**
     * Get all confirmations for a user's alerts
     */
    suspend fun getUserAlertConfirmations(userId: String): List<AlertConfirmation> = withContext(Dispatchers.IO) {
        try {
            // Get all alerts for this user
            val alerts = SupabaseClient.client.from("alert_history")
                .select {
                    filter {
                        eq("user_id", userId)
                    }
                }
                .decodeList<AlertHistory>()

            val alertIds = alerts.mapNotNull { it.id }

            if (alertIds.isEmpty()) {
                return@withContext emptyList()
            }

            // Get confirmations for these alerts
            val confirmations = SupabaseClient.client.from("alert_confirmations")
                .select {
                    filter {
                        isIn("alert_id", alertIds)
                    }
                }
                .decodeList<AlertConfirmation>()

            Log.d(TAG, "Found ${confirmations.size} confirmations for user $userId")
            
            // Return confirmations as-is, expiry will be handled when user opens the detail page
            confirmations

        } catch (e: Exception) {
            Log.e(TAG, "Failed to get user confirmations", e)
            emptyList()
        }
    }

    /**
     * Get all confirmations where user is the guardian
     */
    suspend fun getGuardianConfirmations(guardianUserId: String): List<AlertConfirmation> = withContext(Dispatchers.IO) {
        try {
            val confirmations = SupabaseClient.client.from("alert_confirmations")
                .select {
                    filter {
                        eq("guardian_user_id", guardianUserId)
                    }
                }
                .decodeList<AlertConfirmation>()

            Log.d(TAG, "Found ${confirmations.size} confirmations for guardian $guardianUserId")
            confirmations

        } catch (e: Exception) {
            Log.e(TAG, "Failed to get guardian confirmations", e)
            emptyList()
        }
    }

    /**
     * Notify guardian that user did NOT cancel (timer expired)
     */
    suspend fun notifyGuardianExpired(context: Context, alertId: String, guardianEmail: String) {
        try {
            Log.d(TAG, "Notifying guardian that alert was NOT cancelled: $alertId")
            
            // Call Supabase Edge Function via HTTP
            val client = okhttp3.OkHttpClient()
            
            val jsonBody = """
                {
                    "alertId": "$alertId",
                    "guardianEmail": "$guardianEmail",
                    "expired": true
                }
            """.trimIndent()
            
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = jsonBody.toRequestBody(mediaType)
            
            val request = okhttp3.Request.Builder()
                .url("https://acgsmcxmesvsftzugeik.supabase.co/functions/v1/send-expiry-notification")
                .addHeader("Authorization", "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImFjZ3NtY3htZXN2c2Z0enVnZWlrIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NjIyNzIzNTYsImV4cCI6MjA3Nzg0ODM1Nn0.EwiJajiscMqz1jHyyl-BDS4YIvc0nihBUn3m8pPUP1c")
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()
            
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                Log.d(TAG, "Expiry notification sent to guardian")
            } else {
                Log.e(TAG, "Failed to send expiry notification: ${response.code}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to send expiry notification", e)
        }
    }
    
    /**
     * Send cancellation notification to guardian via Supabase Edge Function
     */
    private suspend fun sendCancellationNotification(guardianEmail: String, alertId: String) {
        try {
            // Call Supabase Edge Function via HTTP
            val client = okhttp3.OkHttpClient()
            
            val jsonBody = """
                {
                    "alertId": "$alertId",
                    "guardianEmail": "$guardianEmail"
                }
            """.trimIndent()
            
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = jsonBody.toRequestBody(mediaType)
            
            val request = okhttp3.Request.Builder()
                .url("https://acgsmcxmesvsftzugeik.supabase.co/functions/v1/send-cancellation-notification")
                .addHeader("Authorization", "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImFjZ3NtY3htZXN2c2Z0enVnZWlrIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NjIyNzIzNTYsImV4cCI6MjA3Nzg0ODM1Nn0.EwiJajiscMqz1jHyyl-BDS4YIvc0nihBUn3m8pPUP1c")
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()
            
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                Log.d(TAG, "Cancellation notification sent via Supabase Edge Function")
            } else {
                Log.e(TAG, "Failed to send cancellation: ${response.code}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to send cancellation notification", e)
        }
    }

    /**
     * Parse ISO date string
     */
    private fun parseDate(dateStr: String): Date? {
        return try {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            dateFormat.timeZone = TimeZone.getTimeZone("UTC")
            dateFormat.parse(dateStr)
        } catch (e: Exception) {
            null
        }
    }
}
