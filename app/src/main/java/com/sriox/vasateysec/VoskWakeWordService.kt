package com.sriox.vasateysec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
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
    private val cooldownPeriod = 5000 
    private var isWaitingForSecondWord = false
    private var firstWordDetectedTime: Long = 0
    private val doubleWordWindow = 5000 

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
                val settings = getSharedPreferences("vasatey_settings", MODE_PRIVATE)
                val isDoubleWordEnabled = settings.getBoolean("double_word_enabled", true)
                val wakeWord = getSharedPreferences("vasatey_prefs", MODE_PRIVATE).getString("wake_word", "help me") ?: "help me"

                if (resultText.contains(wakeWord, ignoreCase = true)) {
                    if (isDoubleWordEnabled) {
                        handleDoubleWordDetection(currentTime, wakeWord)
                    } else {
                        if (currentTime - lastRecognitionTime > cooldownPeriod) {
                            lastRecognitionTime = currentTime
                            triggerAlertSequence()
                        }
                    }
                }
            }
        }
    }

    private fun handleDoubleWordDetection(currentTime: Long, wakeWord: String) {
        if (!isWaitingForSecondWord) {
            isWaitingForSecondWord = true
            firstWordDetectedTime = currentTime
            Log.d("VoskService", "First detection. Waiting for second...")
        } else {
            if (currentTime - firstWordDetectedTime <= doubleWordWindow) {
                isWaitingForSecondWord = false
                triggerAlertSequence()
            } else {
                firstWordDetectedTime = currentTime
            }
        }
    }

    private fun triggerAlertSequence() {
        triggerEmergencyAlert()
    }
    
    private fun triggerEmergencyAlert() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val alertPrefs = getSharedPreferences("alert_settings", Context.MODE_PRIVATE)
                val networkEnabled = alertPrefs.getBoolean("network_alert_enabled", true)
                val smsEnabled = alertPrefs.getBoolean("sms_alert_enabled", false)
                val autoCallEnabled = alertPrefs.getBoolean("auto_call_enabled", false)
                
                val location = LocationManager.getCurrentLocation(this@VoskWakeWordService)

                // 1. Cloud Alert (Network)
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

                // 2. Offline Alerts (SMS or Call)
                // FIX: Check for EITHER SMS or Auto-Call enabled
                if (smsEnabled || autoCallEnabled) {
                    Log.d("VoskService", "Triggering Offline Alerts (SMS: $smsEnabled, Call: $autoCallEnabled)")
                    SmsHelper.sendEmergencySms(this@VoskWakeWordService, location?.latitude, location?.longitude)
                }

            } catch (e: Exception) {
                Log.e("VoskService", "Trigger error: ${e.message}")
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
