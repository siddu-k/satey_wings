package com.sriox.vasateysec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.sriox.vasateysec.utils.AlertManager
import com.sriox.vasateysec.utils.CameraManager
import com.sriox.vasateysec.utils.LocationManager
import com.sriox.vasateysec.utils.SmsHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.IOException

class VoskWakeWordService : Service(), RecognitionListener {

    private var speechService: SpeechService? = null
    private var lastRecognitionTime: Long = 0
    private val cooldownPeriod = 5000 // 5 seconds
    
    // Logic for double-word activation
    private var isWaitingForSecondWord = false
    private var firstWordDetectedTime: Long = 0
    private val doubleWordWindow = 5000 // Must say it again within 5 seconds

    override fun onCreate() {
        super.onCreate()
        initVosk()
    }

    private fun initVosk() {
        Thread {
            val prefs = getSharedPreferences("vasatey_prefs", MODE_PRIVATE)
            val wakeWord = prefs.getString("wake_word", "help me") ?: "help me"
            
            StorageService.unpack(this, "model", "model",
                { model: Model? ->
                    try {
                        val recognizer = Recognizer(model, 16000f, "[\"$wakeWord\", \"[unk]\"]")
                        speechService = SpeechService(recognizer, 16000f)
                        speechService?.startListening(this)
                    } catch (e: IOException) {
                        Log.e("VoskService", "Recognizer initialization failed", e)
                    }
                },
                { exception: IOException ->
                    Log.e("VoskService", "Failed to unpack model", exception)
                })
        }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, createNotification("Listening for wake word..."))
        return START_STICKY
    }

    private fun createNotification(contentText: String): Notification {
        val channelId = "VOSK_SERVICE_CHANNEL"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Wake Word Service", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Wake Word Detection")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
    }

    override fun onPartialResult(hypothesis: String?) {}

    override fun onResult(hypothesis: String?) {
        hypothesis?.let {
            val resultText = getResultTextFromJson(it)
            if (resultText.isNotBlank()) {
                val currentTime = SystemClock.elapsedRealtime()
                
                // Get settings
                val settings = getSharedPreferences("vasatey_settings", MODE_PRIVATE)
                val isDoubleWordEnabled = settings.getBoolean("double_word_enabled", false)
                val wakeWord = getSharedPreferences("vasatey_prefs", MODE_PRIVATE).getString("wake_word", "help me") ?: "help me"

                if (resultText.contains(wakeWord, ignoreCase = true)) {
                    if (isDoubleWordEnabled) {
                        handleDoubleWordDetection(currentTime, wakeWord)
                    } else {
                        if (currentTime - lastRecognitionTime > cooldownPeriod) {
                            lastRecognitionTime = currentTime
                            triggerAlertSequence(wakeWord)
                        }
                    }
                }
            }
        }
    }

    private fun handleDoubleWordDetection(currentTime: Long, wakeWord: String) {
        if (!isWaitingForSecondWord) {
            // First time detecting the word
            isWaitingForSecondWord = true
            firstWordDetectedTime = currentTime
            Log.d("VoskService", "First wake word detected. Waiting for second...")
            
            // Give subtle feedback
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val notification = NotificationCompat.Builder(this, "VOSK_SERVICE_CHANNEL")
                .setContentTitle("Confirmation Required")
                .setContentText("Say '$wakeWord' again to confirm alert.")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setVibrate(longArrayOf(0, 100))
                .build()
            notificationManager.notify(4, notification)
            
        } else {
            // Second detection
            if (currentTime - firstWordDetectedTime <= doubleWordWindow) {
                // Successfully said twice within 5 seconds
                isWaitingForSecondWord = false
                Log.d("VoskService", "Double wake word confirmed!")
                triggerAlertSequence(wakeWord)
            } else {
                // Too much time passed, treat this as a new "first" detection
                firstWordDetectedTime = currentTime
                Log.d("VoskService", "Second word too late. Restarting window.")
            }
        }
    }

    private fun triggerAlertSequence(wakeWord: String) {
        showWakeWordDetectedNotification()
        triggerEmergencyAlert()
    }
    
    private fun showWakeWordDetectedNotification() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "WAKE_WORD_DETECTED_CHANNEL"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Wake Word Detections", NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("🆘 Alert Triggered!")
            .setContentText("Emergency sequence started. Notifying contacts...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 200, 100, 200, 100, 200))
            .build()

        notificationManager.notify(2, notification)
    }
    
    private fun triggerEmergencyAlert() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val alertPrefs = getSharedPreferences("alert_settings", MODE_PRIVATE)
                val networkEnabled = alertPrefs.getBoolean("network_alert_enabled", true)
                val smsEnabled = alertPrefs.getBoolean("sms_alert_enabled", false)
                
                var location: android.location.Location? = LocationManager.getCurrentLocation(this@VoskWakeWordService)

                if (networkEnabled) {
                    val photos = CameraManager.captureEmergencyPhotos(this@VoskWakeWordService)
                    AlertManager.sendEmergencyAlert(
                        context = this@VoskWakeWordService,
                        latitude = location?.latitude,
                        longitude = location?.longitude,
                        locationAccuracy = location?.accuracy,
                        frontPhotoFile = photos.frontPhoto,
                        backPhotoFile = photos.backPhoto
                    )
                }

                if (smsEnabled) {
                    SmsHelper.sendEmergencySms(this@VoskWakeWordService, location?.latitude, location?.longitude)
                }

            } catch (e: Exception) {
                Log.e("VoskService", "Error in trigger sequence: ${e.message}")
            }
        }
    }

    private fun getResultTextFromJson(json: String): String {
        return try {
            json.substringAfter("\"text\" : \"").substringBefore("\"")
        } catch (e: Exception) { "" }
    }

    override fun onFinalResult(hypothesis: String?) {}
    override fun onError(exception: Exception?) {}
    override fun onTimeout() { speechService?.startListening(this) }

    override fun onDestroy() {
        super.onDestroy()
        speechService?.stop()
        speechService?.shutdown()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
