package com.sriox.vasateysec.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.sriox.vasateysec.AlertConfirmationActivity
import com.sriox.vasateysec.ContactDetailActivity
import com.sriox.vasateysec.EmergencyAlertViewerActivity
import com.sriox.vasateysec.R
import com.sriox.vasateysec.utils.FCMTokenManager
import com.sriox.vasateysec.utils.LiveLocationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class VasateyFCMService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "VasateyFCMService"
        private const val CHANNEL_ID = "guardian_alert_channel_v4"
        private const val CHANNEL_NAME = "Critical Safety Alerts"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        FCMTokenManager.updateFCMToken(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        
        message.data.let { data ->
            if (data.isNotEmpty()) {
                val messageType = data["type"] ?: "alert"
                when (messageType) {
                    "location_request" -> {
                        LiveLocationHelper.handleLocationRequest(applicationContext, data)
                    }
                    "contact_request" -> {
                        handleContactRequest(data)
                    }
                    "alert_confirmation" -> handleAlertConfirmation(data)
                    "alert_cancelled" -> handleAlertCancellation(data)
                    "alert_not_cancelled" -> handleAlertNotCancelled(data)
                    else -> handleDataMessage(data)
                }
            }
        }
    }

    private fun handleContactRequest(data: Map<String, String>) {
        val requestId = data["requestId"] ?: ""
        val fromName = data["fromName"] ?: "Someone"
        
        val intent = Intent(this, ContactDetailActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("request_id", requestId)
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("📞 Contact Request")
            .setContentText("$fromName needs to contact you. Tap to view details.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setColor(Color.parseColor("#FFD700"))
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun handleDataMessage(data: Map<String, String>) {
        val fullName = data["fullName"] ?: ""
        val email = data["email"] ?: ""
        val phoneNumber = data["phoneNumber"] ?: ""
        val latitudeStr = data["lastKnownLatitude"] ?: data["latitude"] ?: ""
        val longitudeStr = data["lastKnownLongitude"] ?: data["longitude"] ?: ""
        val alertId = data["alertId"] ?: ""
        val alertType = data["alertType"] ?: "emergency"
        val timestamp = data["timestamp"] ?: ""
        val isSelfAlert = data["isSelfAlert"]?.toBoolean() ?: false
        val frontPhotoUrl = data["frontPhotoUrl"] ?: ""
        val backPhotoUrl = data["backPhotoUrl"] ?: ""

        if (isSelfAlert) return

        val prefs = getSharedPreferences("vasatey_settings", Context.MODE_PRIVATE)
        val soundEnabled = prefs.getBoolean("sound_enabled", true)

        // Programmatic Siren Effect (Single Beep Siren)
        if (soundEnabled) {
            playEmergencySirenEffect()
        }

        val intent = Intent(this, EmergencyAlertViewerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra("fullName", fullName)
            putExtra("email", email)
            putExtra("phoneNumber", phoneNumber)
            putExtra("latitude", latitudeStr)
            putExtra("longitude", longitudeStr)
            putExtra("alertType", alertType)
            putExtra("timestamp", timestamp)
            putExtra("frontPhotoUrl", frontPhotoUrl)
            putExtra("backPhotoUrl", backPhotoUrl)
            putExtra("alertId", alertId)
            putExtra("fromNotification", true)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("🚨 Emergency Alert from $fullName")
            .setContentText("$fullName needs help! Tap to view location.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setVibrate(longArrayOf(0, 1000, 500, 1000, 500, 1000))
            .setColor(Color.RED)
            .setFullScreenIntent(pendingIntent, true)
            .setSound(null) // Remove soft alarm sound

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(alertId.hashCode(), notificationBuilder.build())
    }

    /**
     * Programmatically generates a siren-like pulsing sound using a single distinct beep tone.
     */
    private fun playEmergencySirenEffect() {
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100)
                // Repeat a single high-pitched tone for a "beep-beep" siren effect
                repeat(10) {
                    toneGen.startTone(ToneGenerator.TONE_SUP_ERROR, 500) 
                    delay(800)
                }
                toneGen.release()
            } catch (e: Exception) {
                Log.e(TAG, "Siren effect failed", e)
            }
        }
    }
    
    private fun handleAlertConfirmation(data: Map<String, String>) {
        val title = data["title"] ?: "⚠️ Alert Confirmation"
        val body = data["body"] ?: "Guardian confirmed your alert"
        val alertId = data["alertId"] ?: ""
        val guardianEmail = data["guardianEmail"] ?: ""
        
        val intent = Intent(this, AlertConfirmationActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra("alertId", alertId)
            putExtra("guardianEmail", guardianEmail)
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setColor(Color.parseColor("#FF9800"))
            .build()
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
    
    private fun handleAlertCancellation(data: Map<String, String>) {
        val title = data["title"] ?: "✅ Alert Cancelled"
        val body = data["body"] ?: "The user has cancelled the alert"
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setColor(Color.parseColor("#4CAF50"))
            .build()
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
    
    private fun handleAlertNotCancelled(data: Map<String, String>) {
        val title = data["title"] ?: "🚨 Alert Still Active"
        val body = data["body"] ?: "User did not cancel. This is a real emergency!"

        val prefs = getSharedPreferences("vasatey_settings", Context.MODE_PRIVATE)
        val soundEnabled = prefs.getBoolean("sound_enabled", true)
        
        if (soundEnabled) {
            playEmergencySirenEffect()
        }

        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 1000, 500, 1000))
            .setColor(Color.RED)
            .setSound(null) // Remove soft alarm sound
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(System.currentTimeMillis().toInt(), notificationBuilder.build())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // Delete old channels to ensure new settings apply
            notificationManager.deleteNotificationChannel("guardian_alert_channel")
            notificationManager.deleteNotificationChannel("guardian_alert_channel_v2")
            notificationManager.deleteNotificationChannel("guardian_alert_channel_v3")

            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical Safety Alerts"
                enableVibration(true)
                setSound(null, null) // Disable system sound for this channel
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
}
