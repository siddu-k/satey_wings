package com.sriox.vasateysec.utils

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
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
    private const val ACTION_SMS_SENT = "com.sriox.vasateysec.SMS_SENT"
    
    private var lastSmsSentTime = 0L

    suspend fun sendEmergencySms(context: Context, latitude: Double?, longitude: Double?, forceSms: Boolean = false) {
        val alertPrefs = context.getSharedPreferences("alert_settings", Context.MODE_PRIVATE)
        val smsEnabled = alertPrefs.getBoolean("sms_alert_enabled", false) || forceSms
        val autoCallEnabled = alertPrefs.getBoolean("auto_call_enabled", false)

        Log.d(TAG, "🔍 Initial Check - smsEnabled: $smsEnabled, forceSms: $forceSms")

        val settingsPrefs = context.getSharedPreferences("vasatey_settings", Context.MODE_PRIVATE)
        val cooldownMinutes = settingsPrefs.getInt("hardware_sms_interval", 2)
        val smsCooldownMs = cooldownMinutes * 60 * 1000L
        
        val currentTime = SystemClock.elapsedRealtime()
        
        if (forceSms && (currentTime - lastSmsSentTime < smsCooldownMs)) {
            Log.d(TAG, "🚫 Spam Blocked: Cooldown of $cooldownMinutes min is active for hardware.")
            return
        }

        withContext(Dispatchers.IO) {
            try {
                var contacts = emptyList<SmsContact>()
                try {
                    val currentUser = SupabaseClient.client.auth.currentUserOrNull()
                    if (currentUser != null) {
                        contacts = SupabaseClient.client.from("sms_contacts")
                            .select { filter { eq("user_id", currentUser.id) } }
                            .decodeList<SmsContact>()
                        if (contacts.isNotEmpty()) saveToLocalStorage(context, contacts)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "🌐 Supabase Fetch Failed: ${e.message}")
                }

                if (contacts.isEmpty()) contacts = getFromLocalStorage(context)
                
                if (contacts.isEmpty()) {
                    Log.e(TAG, "❌ No contacts found.")
                    return@withContext
                }

                if (smsEnabled) {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
                        val userName = SessionManager.getUserName() ?: "User"
                        val locationUrl = if (latitude != null && longitude != null) "https://maps.google.com/?q=$latitude,$longitude" else "Location unavailable"
                        val message = "🚨 SOS ALERT! 🚨\n$userName needs help!\nLocation: $locationUrl"

                        val smsManager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                            context.getSystemService(SmsManager::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            SmsManager.getDefault()
                        }

                        if (smsManager == null) {
                            Log.e(TAG, "❌ SmsManager is NULL")
                            return@withContext
                        }

                        for (contact in contacts) {
                            Log.d(TAG, "📤 Sending to: ${contact.phone}")
                            
                            val sentIntent = Intent(ACTION_SMS_SENT).apply {
                                putExtra("phone", contact.phone)
                                setPackage(context.packageName)
                            }
                            
                            val pendingIntent = PendingIntent.getBroadcast(
                                context, 
                                contact.phone.hashCode(), 
                                sentIntent, 
                                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                            )

                            // Important: If message is long, it might need divideMessage
                            val parts = smsManager.divideMessage(message)
                            if (parts.size > 1) {
                                Log.d(TAG, "Message too long, sending as multipart (${parts.size} parts)")
                                val sentIntents = ArrayList<PendingIntent>()
                                for (i in parts.indices) sentIntents.add(pendingIntent)
                                smsManager.sendMultipartTextMessage(contact.phone, null, parts, sentIntents, null)
                            } else {
                                smsManager.sendTextMessage(contact.phone, null, message, pendingIntent, null)
                            }
                        }
                        
                        lastSmsSentTime = SystemClock.elapsedRealtime()
                        Log.d(TAG, "✅ SMS triggered for ${contacts.size} contacts")
                    } else {
                        Log.e(TAG, "❌ Permission SEND_SMS Missing")
                    }
                }

                if (autoCallEnabled) {
                    val selectedPhone = alertPrefs.getString("auto_call_recipient", null)
                    val callTarget = selectedPhone ?: contacts.firstOrNull()?.phone
                    if (callTarget != null) {
                        withContext(Dispatchers.Main) { triggerAutoCall(context, callTarget) }
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "🆘 Error: ${e.message}", e)
            }
        }
    }

    private fun triggerAutoCall(context: Context, phoneNumber: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            try {
                val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${phoneNumber.trim()}"))
                callIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(callIntent)
            } catch (e: Exception) { }
        }
    }

    fun saveToLocalStorage(context: Context, contacts: List<SmsContact>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(STORAGE_KEY, Json.encodeToString(contacts)).apply()
    }

    fun getFromLocalStorage(context: Context): List<SmsContact> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(STORAGE_KEY, null)
        return if (json != null) {
            try {
                Json.decodeFromString<List<SmsContact>>(json)
            } catch (e: Exception) { emptyList() }
        } else emptyList()
    }
}
