package com.sriox.vasateysec.utils

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
    private const val KEY_LAST_SENT_TIME = "last_sms_sent_timestamp"
    
    /**
     * Gets the remaining time in milliseconds until the next SMS can be sent for hardware triggers.
     * Uses persistent storage to ensure accuracy even after app restarts.
     */
    fun getRemainingCooldownMs(context: Context): Long {
        val settingsPrefs = context.getSharedPreferences("vasatey_settings", Context.MODE_PRIVATE)
        val cooldownMinutes = settingsPrefs.getInt("hardware_sms_interval", 2)
        val smsCooldownMs = cooldownMinutes * 60 * 1000L
        
        val lastSentTime = settingsPrefs.getLong(KEY_LAST_SENT_TIME, 0L)
        val currentTime = System.currentTimeMillis()
        val timeDiff = currentTime - lastSentTime
        
        return if (lastSentTime > 0 && timeDiff < smsCooldownMs) {
            smsCooldownMs - timeDiff
        } else {
            0L
        }
    }

    /**
     * Sends emergency SMS and triggers auto-call.
     * Uses persistent timestamps to fix the 2-minute timer bug.
     */
    suspend fun sendEmergencySms(
        context: Context, 
        latitude: Double?, 
        longitude: Double?, 
        isHardware: Boolean = false
    ) {
        val alertPrefs = context.getSharedPreferences("alert_settings", Context.MODE_PRIVATE)
        val settingsPrefs = context.getSharedPreferences("vasatey_settings", Context.MODE_PRIVATE)
        
        // SMS: Always ON for hardware triggers.
        val smsEnabled = if (isHardware) true else alertPrefs.getBoolean("sms_alert_enabled", false)
        
        // Auto-Call: Now strictly respects the "Hardware Auto Call" toggle for ESP32.
        val autoCallEnabled = if (isHardware) {
            settingsPrefs.getBoolean("hardware_auto_call_enabled", false)
        } else {
            alertPrefs.getBoolean("auto_call_enabled", false)
        }

        val cooldownMinutes = settingsPrefs.getInt("hardware_sms_interval", 2)
        val smsCooldownMs = cooldownMinutes * 60 * 1000L
        
        val lastSentTime = settingsPrefs.getLong(KEY_LAST_SENT_TIME, 0L)
        val currentTime = System.currentTimeMillis()
        val timeDiff = currentTime - lastSentTime
        
        Log.d(TAG, "🏁 SOS_TRIGGER -> Hardware: $isHardware, SMS: $smsEnabled, Call: $autoCallEnabled")

        // Apply persistent cooldown for hardware
        if (isHardware && lastSentTime > 0 && timeDiff < smsCooldownMs) {
            val remaining = (smsCooldownMs - timeDiff) / 1000
            Log.d(TAG, "🚫 Persistent Cooldown Active: $remaining seconds left.")
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
                    Log.w(TAG, "🌐 Fallback to Local Storage")
                }

                if (contacts.isEmpty()) contacts = getFromLocalStorage(context)
                if (contacts.isEmpty()) {
                    Log.e(TAG, "❌ ABORT: No contacts found.")
                    return@withContext
                }

                // 1. SMS Sequence
                if (smsEnabled) {
                    sendSmsSequence(context, contacts, latitude, longitude)
                    // PERSIST the sent time immediately
                    settingsPrefs.edit().putLong(KEY_LAST_SENT_TIME, System.currentTimeMillis()).apply()
                    Log.d(TAG, "✅ SMS Sent and Timestamp Persisted")
                }

                // 2. Auto-Call Sequence
                if (autoCallEnabled) {
                    val selectedPhone = alertPrefs.getString("auto_call_recipient", null)
                    var callTarget = selectedPhone ?: contacts.firstOrNull()?.phone
                    
                    if (callTarget != null) {
                        Log.d(TAG, "📞 Triggering Auto-Call to: $callTarget")
                        withContext(Dispatchers.Main) { 
                            triggerAutoCall(context, callTarget) 
                        }
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "🆘 Emergency Error: ${e.message}")
            }
        }
    }

    private fun sendSmsSequence(context: Context, contacts: List<SmsContact>, latitude: Double?, longitude: Double?) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return

        val userName = SessionManager.getUserName() ?: "User"
        val locationUrl = if (latitude != null && longitude != null) "https://maps.google.com/?q=$latitude,$longitude" else "Location unknown"
        val message = "🚨 SOS ALERT! $userName needs help!\nLocation: $locationUrl"

        val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }

        for (contact in contacts) {
            var phone = contact.phone.trim()
            if (phone.length == 10 && !phone.startsWith("+")) phone = "+91$phone"

            val sentIntent = Intent(ACTION_SMS_SENT).apply { 
                putExtra("phone", phone)
                setPackage(context.packageName)
            }
            val pendingIntent = PendingIntent.getBroadcast(context, phone.hashCode(), sentIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

            try {
                val parts = smsManager.divideMessage(message)
                if (parts.size > 1) {
                    val sentIntents = ArrayList<PendingIntent>()
                    for (i in parts.indices) sentIntents.add(pendingIntent)
                    smsManager.sendMultipartTextMessage(phone, null, parts, sentIntents, null)
                } else {
                    smsManager.sendTextMessage(phone, null, message, pendingIntent, null)
                }
            } catch (e: Exception) { }
        }
    }

    private fun triggerAutoCall(context: Context, phoneNumber: String) {
        var phone = phoneNumber.trim()
        if (phone.length == 10 && !phone.startsWith("+")) phone = "+91$phone"

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            try {
                val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phone"))
                callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
        return if (json != null) try { Json.decodeFromString(json) } catch (e: Exception) { emptyList() } else emptyList()
    }
}
