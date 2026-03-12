package com.sriox.vasateysec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
    private val NOTIFICATION_ID = 1
    
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isListening = false

    override fun onCreate() {
        super.onCreate()
        initVosk()
        startWatchdog()
    }

    private fun initVosk() {
        Log.d("VoskService", "Initializing Vosk Engine...")
        Thread {
            val prefs = getSharedPreferences("vasatey_prefs", MODE_PRIVATE)
            val wakeWord = prefs.getString("wake_word", "help me") ?: "help me"
            StorageService.unpack(this, "model", "model",
                { model: Model? ->
                    try {
                        val recognizer = Recognizer(model, 16000f, "[\"$wakeWord\", \"[unk]\"]")
                        speechService = SpeechService(recognizer, 16000f)
                        startListening()
                    } catch (e: IOException) {
                        Log.e("VoskService", "Recognizer initialization failed", e)
                    }
                },
                { exception: IOException ->
                    Log.e("VoskService", "Failed to unpack model", exception)
                })
        }.start()
    }

    private fun startListening() {
        try {
            speechService?.startListening(this)
            isListening = true
            Log.d("VoskService", "Voice recognition started")
        } catch (e: Exception) {
            Log.e("VoskService", "Failed to start listening: ${e.message}")
            retryListening()
        }
    }

    private fun retryListening() {
        isListening = false
        mainHandler.postDelayed({
            Log.d("VoskService", "Attempting to restart listener...")
            startListening()
        }, 3000)
    }

    // Watchdog to ensure we never stop listening permanently
    private fun startWatchdog() {
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                if (!isListening) {
                    Log.w("VoskService", "Watchdog: Engine not listening, restarting...")
                    startListening()
                }
                mainHandler.postDelayed(this, 20000) // Check every 20 seconds
            }
        }, 20000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification("Safety Guardian", "Continuous voice monitoring active"))
        return START_STICKY
    }

    private fun updateNotification(title: String, text: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(title, text))
    }

    private fun createNotification(title: String, contentText: String): Notification {
        val channelId = "VOSK_SERVICE_CHANNEL"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Wake Word Service", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
    }

    override fun onPartialResult(hypothesis: String?) {}

    override fun onResult(hypothesis: String?) {
        processHypothesis(hypothesis)
    }

    override fun onFinalResult(hypothesis: String?) {
        processHypothesis(hypothesis)
    }

    private fun processHypothesis(hypothesis: String?) {
        hypothesis?.let {
            val resultText = getResultTextFromJson(it)
            if (resultText.isNotBlank()) {
                Log.d("VoskService", "Recognized: $resultText")
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
            updateNotification("Alert Triggered", "Wake word detected once. Waiting for second...")
        } else {
            if (currentTime - firstWordDetectedTime <= doubleWordWindow) {
                isWaitingForSecondWord = false
                triggerAlertSequence()
            } else {
                // Window expired, set this as the new first word
                firstWordDetectedTime = currentTime
            }
        }
    }

    private fun triggerAlertSequence() {
        updateNotification("SOS ACTIVATED", "Initiating emergency sequence...")
        triggerEmergencyAlert()
    }
    
    private fun triggerEmergencyAlert() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val alertPrefs = getSharedPreferences("alert_settings", Context.MODE_PRIVATE)
                val networkEnabled = alertPrefs.getBoolean("network_alert_enabled", true)
                val smsEnabled = alertPrefs.getBoolean("sms_alert_enabled", false)
                val autoCallEnabled = alertPrefs.getBoolean("auto_call_enabled", false)
                
                updateNotification("SOS: Processing", "Getting location...")
                val location = LocationManager.getCurrentLocation(this@VoskWakeWordService)

                // 1. Cloud Alert (Network)
                if (networkEnabled) {
                    updateNotification("SOS: Processing", "Capturing evidence photos...")
                    val photos = CameraManager.captureEmergencyPhotos(this@VoskWakeWordService)
                    
                    updateNotification("SOS: Sending", "Uploading data to Cloud...")
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
                if (smsEnabled || autoCallEnabled) {
                    val status = buildString {
                        if (smsEnabled) append("SMS")
                        if (smsEnabled && autoCallEnabled) append(" & ")
                        if (autoCallEnabled) append("Call")
                    }
                    updateNotification("SOS: Sending", "Dispatching $status alerts...")
                    SmsHelper.sendEmergencySms(this@VoskWakeWordService, location?.latitude, location?.longitude)
                }

                updateNotification("SOS SENT", "All alerts dispatched successfully.")
                kotlinx.coroutines.delay(10000)
                updateNotification("Safety Guardian", "Continuous voice monitoring active")

            } catch (e: Exception) {
                Log.e("VoskService", "Trigger error: ${e.message}")
                updateNotification("SOS ERROR", "Restarting listener...")
                kotlinx.coroutines.delay(5000)
                updateNotification("Safety Guardian", "Continuous voice monitoring active")
            }
        }
    }

    private fun getResultTextFromJson(json: String): String {
        return try {
            json.substringAfter("\"text\" : \"").substringBefore("\"")
        } catch (e: Exception) { "" }
    }

    override fun onError(exception: Exception?) {
        Log.e("VoskService", "Vosk Error: ${exception?.message}")
        retryListening()
    }

    override fun onTimeout() {
        Log.d("VoskService", "Vosk Timeout - restarting...")
        startListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        isListening = false
        mainHandler.removeCallbacksAndMessages(null)
        speechService?.stop()
        speechService?.shutdown()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
