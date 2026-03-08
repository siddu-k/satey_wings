package com.sriox.vasateysec.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.sriox.vasateysec.SupabaseClient
import com.sriox.vasateysec.models.SmsContact
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object SmsHelper {
    private const val TAG = "SmsHelper"
    private const val PREFS_NAME = "trusted_contacts_storage"
    private const val STORAGE_KEY = "permanent_sms_contacts"

    /**
     * Send emergency SMS. 
     * Uses Permanent Local Storage if Supabase is offline.
     */
    suspend fun sendEmergencySms(context: Context, latitude: Double?, longitude: Double?) {
        withContext(Dispatchers.IO) {
            try {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                    Log.e(TAG, "❌ No SMS Permission")
                    return@withContext
                }

                val userName = SessionManager.getUserName() ?: "User"
                var contacts = emptyList<SmsContact>()
                
                // 1. Try to get fresh data if online
                try {
                    val currentUser = SupabaseClient.client.auth.currentUserOrNull()
                    if (currentUser != null) {
                        contacts = SupabaseClient.client.from("sms_contacts")
                            .select { filter { eq("user_id", currentUser.id) } }
                            .decodeList<SmsContact>()
                        
                        if (contacts.isNotEmpty()) {
                            saveToLocalStorage(context, contacts)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "🌐 Offline: Using Permanent Local Storage")
                }

                // 2. Fallback to Local Storage if network failed or returned empty
                if (contacts.isEmpty()) {
                    contacts = getFromLocalStorage(context)
                }

                if (contacts.isEmpty()) {
                    Log.e(TAG, "❌ CRITICAL: No contacts found in Database OR Local Storage")
                    return@withContext
                }

                val locationUrl = if (latitude != null && longitude != null) {
                    "https://maps.google.com/?q=$latitude,$longitude"
                } else {
                    "Location unavailable"
                }

                val message = "🚨 SOS ALERT! 🚨\n$userName needs help!\nLocation: $locationUrl"

                val smsManager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    context.getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }

                for (contact in contacts) {
                    val parts = smsManager.divideMessage(message)
                    if (parts.size > 1) {
                        smsManager.sendMultipartTextMessage(contact.phone, null, parts, null, null)
                    } else {
                        smsManager.sendTextMessage(contact.phone, null, message, null, null)
                    }
                    Log.d(TAG, "✅ SMS sent to ${contact.phone}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "SMS Error: ${e.message}")
            }
        }
    }

    fun saveToLocalStorage(context: Context, contacts: List<SmsContact>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = Json.encodeToString(contacts)
        prefs.edit().putString(STORAGE_KEY, json).apply()
        Log.d(TAG, "💾 Saved ${contacts.size} contacts to Permanent Local Storage")
    }

    fun getFromLocalStorage(context: Context): List<SmsContact> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(STORAGE_KEY, null) ?: return emptyList()
        return try {
            Json.decodeFromString<List<SmsContact>>(json)
        } catch (e: Exception) {
            emptyList()
        }
    }
}
