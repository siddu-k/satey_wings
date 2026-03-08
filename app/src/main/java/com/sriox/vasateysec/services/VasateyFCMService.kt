package com.sriox.vasateysec.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.sriox.vasateysec.R
import com.sriox.vasateysec.utils.FCMTokenManager

class VasateyFCMService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "VasateyFCMService"
        private const val CHANNEL_ID = "guardian_alert_channel"
        private const val CHANNEL_NAME = "Safety Alerts"
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
                        com.sriox.vasateysec.utils.LiveLocationHelper.handleLocationRequest(applicationContext, data)
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
        
        val intent = Intent(this, com.sriox.vasateysec.ContactDetailActivity::class.java).apply {
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
            .setColor(android.graphics.Color.parseColor("#FFD700"))
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

        val intent = Intent(this, com.sriox.vasateysec.EmergencyAlertViewerActivity::class.java).apply {
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

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("🚨 Emergency Alert from $fullName")
            .setContentText("$fullName needs help! Tap to view location.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setVibrate(longArrayOf(0, 500, 200, 500, 200, 500))
            .setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI)
            .setColor(android.graphics.Color.RED)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
    
    private fun handleAlertConfirmation(data: Map<String, String>) {
        val title = data["title"] ?: "⚠️ Alert Confirmation"
        val body = data["body"] ?: "Guardian confirmed your alert"
        val alertId = data["alertId"] ?: ""
        val guardianEmail = data["guardianEmail"] ?: ""
        
        val intent = Intent(this, com.sriox.vasateysec.AlertConfirmationActivity::class.java).apply {
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
            .setColor(android.graphics.Color.parseColor("#FF9800"))
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
            .setColor(android.graphics.Color.parseColor("#4CAF50"))
            .build()
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
    
    private fun handleAlertNotCancelled(data: Map<String, String>) {
        val title = data["title"] ?: "🚨 Alert Still Active"
        val body = data["body"] ?: "User did not cancel. This is a real emergency!"
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 500, 200, 500, 200, 500))
            .setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI)
            .setColor(android.graphics.Color.RED)
            .build()
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Safety alerts and contact requests"
                enableVibration(true)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
