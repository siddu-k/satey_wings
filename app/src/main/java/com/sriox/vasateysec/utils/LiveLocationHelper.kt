package com.sriox.vasateysec.utils

import android.content.Context
import android.util.Log
import com.sriox.vasateysec.SupabaseClient
import com.sriox.vasateysec.models.FCMToken
import com.sriox.vasateysec.models.Guardian
import com.sriox.vasateysec.models.LiveLocation
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * INDEPENDENT Live Location Helper
 * Handles requesting and fetching live locations
 * Works independently from existing code
 */
object LiveLocationHelper {
    
    private const val TAG = "LiveLocationHelper"
    private const val FCM_ENDPOINT = "https://vasatey-notify-msg.vercel.app/api/sendNotification"
    
    /**
     * Handle incoming location request from guardian (called by FCM service)
     * This is INDEPENDENT from main app logic
     */
    fun handleLocationRequest(context: Context, data: Map<String, String>) {
        val guardianEmail = data["guardianEmail"] ?: ""
        Log.d(TAG, "========================================")
        Log.d(TAG, "📍 LOCATION REQUEST RECEIVED")
        Log.d(TAG, "Guardian Email: $guardianEmail")
        Log.d(TAG, "All FCM data: $data")
        Log.d(TAG, "========================================")
        
        // Show notification to user
        showLocationRequestNotification(context, guardianEmail)
        
        // Launch coroutine to get and update location
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Step 1: Check permissions
                Log.d(TAG, "📍 Step 1: Checking location permissions...")
                if (!LocationManager.hasLocationPermission(context)) {
                    Log.e(TAG, "❌ CRITICAL: Location permission NOT granted!")
                    Log.e(TAG, "User needs to grant location permission for live tracking")
                    return@launch
                }
                Log.d(TAG, "✅ Location permission granted")
                
                // Step 2: Get current location
                Log.d(TAG, "📍 Step 2: Getting current location...")
                val startTime = System.currentTimeMillis()
                val location = withContext(Dispatchers.IO) {
                    LocationManager.getCurrentLocation(context)
                }
                val timeTaken = System.currentTimeMillis() - startTime
                
                if (location == null) {
                    Log.e(TAG, "❌ CRITICAL: Could not get current location after ${timeTaken}ms")
                    Log.e(TAG, "Possible reasons: GPS disabled, no signal, timeout")
                    return@launch
                }
                
                Log.d(TAG, "✅ Location obtained in ${timeTaken}ms")
                Log.d(TAG, "   Latitude: ${location.latitude}")
                Log.d(TAG, "   Longitude: ${location.longitude}")
                Log.d(TAG, "   Accuracy: ${location.accuracy}m")
                Log.d(TAG, "   Provider: ${location.provider}")
                
                // Step 3: Get current user
                Log.d(TAG, "📍 Step 3: Getting current user...")
                val currentUser = SupabaseClient.client.auth.currentUserOrNull()
                if (currentUser == null) {
                    Log.e(TAG, "❌ CRITICAL: User not logged in!")
                    Log.e(TAG, "User needs to be logged in for live tracking")
                    return@launch
                }
                
                Log.d(TAG, "✅ User authenticated")
                Log.d(TAG, "   User ID: ${currentUser.id}")
                Log.d(TAG, "   User Email: ${currentUser.email}")
                
                // Step 4: Update live_locations table
                Log.d(TAG, "📍 Step 4: Updating live location in database...")
                val liveLocation = LiveLocation(
                    user_id = currentUser.id,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracy = location.accuracy,
                    updated_at = java.time.Instant.now().toString()
                )
                
                Log.d(TAG, "Upserting location: $liveLocation")
                val updateStartTime = System.currentTimeMillis()
                
                // CRITICAL: Delete old location first to ensure we don't have duplicates
                // and to force a fresh "INSERT/UPDATE" event for Realtime
                try {
                    SupabaseClient.client.from("live_locations").delete {
                        filter {
                            eq("user_id", currentUser.id)
                        }
                    }
                    Log.d(TAG, "🗑️ Deleted old location for user ${currentUser.id}")
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Failed to delete old location (might be first time): ${e.message}")
                }

                SupabaseClient.client.from("live_locations")
                    .upsert(liveLocation)
                val updateTimeTaken = System.currentTimeMillis() - updateStartTime
                
                // ALSO update user's own location in users table
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
                    Log.d(TAG, "✅ Updated user's own location in users table")
                } catch (e: Exception) {
                    Log.e(TAG, "⚠️ Failed to update users table location: ${e.message}")
                }
                
                Log.d(TAG, "✅ Live location updated successfully in ${updateTimeTaken}ms!")
                Log.d(TAG, "========================================")
                Log.d(TAG, "📍 LOCATION REQUEST COMPLETED SUCCESSFULLY")
                Log.d(TAG, "Total time: ${System.currentTimeMillis() - startTime}ms")
                Log.d(TAG, "========================================")
                
            } catch (e: Exception) {
                Log.e(TAG, "========================================")
                Log.e(TAG, "❌ CRITICAL ERROR in handleLocationRequest")
                Log.e(TAG, "Error type: ${e.javaClass.simpleName}")
                Log.e(TAG, "Error message: ${e.message}")
                Log.e(TAG, "Stack trace:", e)
                Log.e(TAG, "========================================")
            }
        }
    }
    
    /**
     * Request live locations from all users who added current user as guardian
     * Uses Supabase Edge Function to handle FCM sending
     * Returns list of user IDs that were sent requests
     */
    suspend fun requestLiveLocations(context: Context): List<String> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "========================================")
            Log.d(TAG, "📍 REQUESTING LIVE LOCATIONS")
            Log.d(TAG, "========================================")
            
            // Get current user
            val currentUser = SupabaseClient.client.auth.currentUserOrNull()
            if (currentUser == null) {
                Log.e(TAG, "❌ User not logged in")
                return@withContext emptyList()
            }
            
            val currentUserEmail = currentUser.email ?: ""
            Log.d(TAG, "Guardian Email: $currentUserEmail")
            
            // Get list of users who added me as guardian FIRST
            Log.d(TAG, "📍 Step 1: Fetching users who added me as guardian...")
            val usersWhoAddedMe = SupabaseClient.client.from("guardians")
                .select {
                    filter {
                        eq("guardian_email", currentUserEmail)
                    }
                }
                .decodeList<Guardian>()
            
            val userIds = usersWhoAddedMe.map { it.user_id }
            Log.d(TAG, "✅ Found ${userIds.size} users who added me as guardian")
            
            if (userIds.isEmpty()) {
                Log.w(TAG, "⚠️ No users found. No one has added you as guardian yet.")
                return@withContext emptyList()
            }
            
            // Log each user
            usersWhoAddedMe.forEachIndexed { index, guardian ->
                Log.d(TAG, "   User ${index + 1}: ${guardian.user_id}")
            }
            
            // Call Supabase Edge Function to send FCM notifications
            Log.d(TAG, "📍 Step 2: Calling Supabase Edge Function...")
            val response = callSupabaseFunction(currentUserEmail)
            
            Log.d(TAG, "✅ Supabase function response: $response")
            Log.d(TAG, "📍 Location requests sent to ${userIds.size} users")
            Log.d(TAG, "========================================")
            
            return@withContext userIds
            
        } catch (e: Exception) {
            Log.e(TAG, "========================================")
            Log.e(TAG, "❌ ERROR in requestLiveLocations")
            Log.e(TAG, "Error type: ${e.javaClass.simpleName}")
            Log.e(TAG, "Error message: ${e.message}")
            Log.e(TAG, "Stack trace:", e)
            Log.e(TAG, "========================================")
            return@withContext emptyList()
        }
    }
    
    /**
     * Call Supabase Edge Function to request live locations
     */
    private suspend fun callSupabaseFunction(guardianEmail: String): String {
        try {
            val client = OkHttpClient()
            
            // Get Supabase config
            val supabaseUrl = "https://acgsmcxmesvsftzugeik.supabase.co"
            val supabaseAnonKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImFjZ3NtY3htZXN2c2Z0enVnZWlrIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NjIyNzIzNTYsImV4cCI6MjA3Nzg0ODM1Nn0.EwiJajiscMqz1jHyyl-BDS4YIvc0nihBUn3m8pPUP1c"
            
            // Get current user's access token
            val currentUser = SupabaseClient.client.auth.currentSessionOrNull()
            val accessToken = currentUser?.accessToken ?: supabaseAnonKey
            
            val functionUrl = "$supabaseUrl/functions/v1/request-live-locations"
            
            // Create JSON payload
            val json = JSONObject().apply {
                put("guardianEmail", guardianEmail)  // Changed from guardian_email to guardianEmail
            }
            
            val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            
            val request = Request.Builder()
                .url(functionUrl)
                .post(body)
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("apikey", supabaseAnonKey)
                .build()
            
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            
            Log.d(TAG, "📤 Supabase function called: ${response.code}")
            Log.d(TAG, "📤 Response: $responseBody")
            
            response.close()
            return responseBody
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error calling Supabase function: ${e.message}", e)
            return "{\"error\": \"${e.message}\"}"
        }
    }
    
    /**
     * Fetch live locations for specific user IDs
     * Returns map of userId to LiveLocation
     */
    suspend fun fetchLiveLocations(userIds: List<String>): Map<String, LiveLocation> = withContext(Dispatchers.IO) {
        val locationMap = mutableMapOf<String, LiveLocation>()
        
        try {
            Log.d(TAG, "========================================")
            Log.d(TAG, "📍 FETCHING LIVE LOCATIONS")
            Log.d(TAG, "Requesting locations for ${userIds.size} users")
            Log.d(TAG, "========================================")
            
            // Fetch all live locations for the given user IDs
            Log.d(TAG, "Querying live_locations table...")
            val liveLocations = SupabaseClient.client.from("live_locations")
                .select()
                .decodeList<LiveLocation>()
            
            Log.d(TAG, "✅ Query returned ${liveLocations.size} total records")
            
            // Filter and map by user IDs
            liveLocations.forEach { location ->
                if (userIds.contains(location.user_id)) {
                    locationMap[location.user_id] = location
                    Log.d(TAG, "✅ Found location for user: ${location.user_id}")
                    Log.d(TAG, "   Lat: ${location.latitude}, Lon: ${location.longitude}")
                    Log.d(TAG, "   Accuracy: ${location.accuracy}m")
                }
            }
            
            // Log which users we DIDN'T get locations for
            val missingUsers = userIds.filter { !locationMap.containsKey(it) }
            if (missingUsers.isNotEmpty()) {
                Log.w(TAG, "⚠️ Missing locations for ${missingUsers.size} users:")
                missingUsers.forEach { userId ->
                    Log.w(TAG, "   - $userId")
                }
            }
            
            Log.d(TAG, "========================================")
            Log.d(TAG, "📍 FETCH COMPLETE: ${locationMap.size}/${userIds.size} locations found")
            Log.d(TAG, "========================================")
            
        } catch (e: Exception) {
            Log.e(TAG, "========================================")
            Log.e(TAG, "❌ ERROR fetching live locations")
            Log.e(TAG, "Error type: ${e.javaClass.simpleName}")
            Log.e(TAG, "Error message: ${e.message}")
            Log.e(TAG, "Stack trace:", e)
            Log.e(TAG, "========================================")
        }
        
        return@withContext locationMap
    }
    
    /**
     * Verify FCM tokens for debugging
     */
    suspend fun verifyFCMTokens(userIds: List<String>): Map<String, Int> = withContext(Dispatchers.IO) {
        val tokenCounts = mutableMapOf<String, Int>()
        
        try {
            Log.d(TAG, "========================================")
            Log.d(TAG, "🔍 VERIFYING FCM TOKENS")
            Log.d(TAG, "========================================")
            
            for (userId in userIds) {
                val tokens = SupabaseClient.client.from("fcm_tokens")
                    .select {
                        filter {
                            eq("user_id", userId)
                            eq("is_active", true)
                        }
                    }
                    .decodeList<FCMToken>()
                
                tokenCounts[userId] = tokens.size
                
                if (tokens.isEmpty()) {
                    Log.w(TAG, "⚠️ User $userId has NO active FCM tokens!")
                } else {
                    Log.d(TAG, "✅ User $userId has ${tokens.size} active token(s)")
                    tokens.forEach { token ->
                        Log.d(TAG, "   Token: ${token.token.take(20)}...")
                        Log.d(TAG, "   Device: ${token.device_name ?: "Unknown"}")
                    }
                }
            }
            
            Log.d(TAG, "========================================")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error verifying FCM tokens: ${e.message}", e)
        }
        
        return@withContext tokenCounts
    }
    
    /**
     * Get live location for a single user
     */
    suspend fun getLiveLocation(userId: String): LiveLocation? = withContext(Dispatchers.IO) {
        try {
            val locations = SupabaseClient.client.from("live_locations")
                .select {
                    filter {
                        eq("user_id", userId)
                    }
                }
                .decodeList<LiveLocation>()
            
            return@withContext locations.firstOrNull()
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error getting live location for user $userId: ${e.message}", e)
            return@withContext null
        }
    }
    
    /**
     * Show notification when guardian requests location
     */
    private fun showLocationRequestNotification(context: Context, guardianEmail: String) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            
            // Create notification channel for Android O and above
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    "location_request_channel",
                    "Location Requests",
                    android.app.NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Notifications when guardian requests your location"
                }
                notificationManager.createNotificationChannel(channel)
            }
            
            // Build notification
            val notification = android.app.Notification.Builder(context).apply {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    setChannelId("location_request_channel")
                }
                setSmallIcon(android.R.drawable.ic_menu_mylocation)
                setContentTitle("📍 Location Request")
                setContentText("Guardian ($guardianEmail) is checking your location")
                setAutoCancel(true)
                setPriority(android.app.Notification.PRIORITY_DEFAULT)
            }.build()
            
            notificationManager.notify(System.currentTimeMillis().toInt(), notification)
            Log.d(TAG, "✅ Notification shown to user")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error showing notification: ${e.message}", e)
        }
    }
}
