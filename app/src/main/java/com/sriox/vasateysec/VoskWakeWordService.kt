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

    override fun onCreate() {
        super.onCreate()
        initVosk()
    }

    private fun initVosk() {
        Thread {
            // Get custom wake word from SharedPreferences
            val prefs = getSharedPreferences("vasatey_prefs", MODE_PRIVATE)
            val wakeWord = prefs.getString("wake_word", "help me") ?: "help me"
            Log.d("VoskService", "Using wake word: $wakeWord")
            
            // The path here is now simplified to "model".
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

    override fun onPartialResult(hypothesis: String?) {
        // Not used in service, but must be implemented
    }

    override fun onResult(hypothesis: String?) {
        hypothesis?.let {
            val resultText = getResultTextFromJson(it)
            if (resultText.isNotBlank()) {
                val currentTime = SystemClock.elapsedRealtime()
                if (currentTime - lastRecognitionTime > cooldownPeriod) {
                    // Get custom wake word
                    val prefs = getSharedPreferences("vasatey_prefs", MODE_PRIVATE)
                    val wakeWord = prefs.getString("wake_word", "help me") ?: "help me"
                    
                    if (resultText.contains(wakeWord, ignoreCase = true)) {
                        lastRecognitionTime = currentTime
                        Log.d("VoskService", "Wake word detected: $wakeWord")
                        // Show immediate feedback that we heard the command
                        showWakeWordDetectedNotification()
                        // Trigger the emergency alert
                        triggerEmergencyAlert()
                    }
                }
            }
        }
    }
    
    private fun showWakeWordDetectedNotification() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "WAKE_WORD_DETECTED_CHANNEL"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Wake Word Detections", NotificationManager.IMPORTANCE_HIGH)
            channel.description = "Notifications for wake word detections"
            channel.enableVibration(true)
            channel.vibrationPattern = longArrayOf(0, 200, 100, 200, 100, 200)
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("🆘 Help is on the way!")
            .setContentText("We've detected 'help me' and are alerting your guardians...")
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
                Log.d("VoskService", "🚨 EMERGENCY ALERT TRIGGERED!")
                
                // Check if location services are enabled
                val locationManager = getSystemService(LOCATION_SERVICE) as? android.location.LocationManager
                val isGpsEnabled = locationManager?.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ?: false
                val isNetworkEnabled = locationManager?.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) ?: false
                
                Log.d("VoskService", "GPS enabled: $isGpsEnabled, Network enabled: $isNetworkEnabled")
                
                if (!isGpsEnabled && !isNetworkEnabled) {
                    Log.e("VoskService", "⚠️ LOCATION SERVICES ARE DISABLED!")
                    launch(Dispatchers.Main) {
                        Toast.makeText(
                            this@VoskWakeWordService,
                            "⚠️ Location is OFF! Enable it in Settings for accurate alerts",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                
                Log.d("VoskService", "========================================")
                Log.d("VoskService", "📍 Getting current location...")
                Log.d("VoskService", "========================================")
                
                // Check if location tracking is enabled in settings
                val locationSettingsPrefs = getSharedPreferences("vasatey_settings", MODE_PRIVATE)
                val locationTrackingEnabled = locationSettingsPrefs.getBoolean("location_tracking_enabled", true)
                
                // Get location with retry logic
                var location: android.location.Location? = null
                
                if (locationTrackingEnabled) {
                    val maxAttempts = 3
                    for (attempts in 1..maxAttempts) {
                        Log.d("VoskService", "📍 Location attempt $attempts/$maxAttempts")
                        location = LocationManager.getCurrentLocation(this@VoskWakeWordService)
                        
                        if (location != null) {
                            Log.d("VoskService", "✅ Location obtained: ${location.latitude}, ${location.longitude}, accuracy: ${location.accuracy}m")
                            
                            // Save location to SharedPreferences for future use
                            val prefs = getSharedPreferences("vasatey_prefs", MODE_PRIVATE)
                            prefs.edit().apply {
                                putString("last_known_lat", location.latitude.toString())
                                putString("last_known_lon", location.longitude.toString())
                                putFloat("last_known_accuracy", location.accuracy)
                                putLong("last_known_time", location.time)
                                apply()
                            }
                            Log.d("VoskService", "💾 Saved location to cache")
                            
                            break
                        } else {
                            Log.w("VoskService", "❌ Location attempt $attempts failed")
                            if (attempts < maxAttempts) {
                                Log.d("VoskService", "⏳ Waiting 3 seconds before retry...")
                                kotlinx.coroutines.delay(3000) // Wait 3 seconds between attempts
                            }
                        }
                    }
                } else {
                    Log.d("VoskService", "📍 Location tracking disabled in settings - skipping location")
                }
                
                if (location == null) {
                    Log.w("VoskService", "❌ Could not get fresh location, checking cached location...")
                    
                    // Try to use cached location from SharedPreferences
                    val prefs = getSharedPreferences("vasatey_prefs", MODE_PRIVATE)
                    val cachedLat = prefs.getString("last_known_lat", null)
                    val cachedLon = prefs.getString("last_known_lon", null)
                    val cachedTime = prefs.getLong("last_known_time", 0)
                    
                    if (cachedLat != null && cachedLon != null) {
                        val age = System.currentTimeMillis() - cachedTime
                        val ageMinutes = age / 60000
                        Log.d("VoskService", "📦 Found cached location: $cachedLat, $cachedLon (age: $ageMinutes minutes)")
                        
                        // Create a Location object from cached data
                        location = android.location.Location("cached").apply {
                            latitude = cachedLat.toDouble()
                            longitude = cachedLon.toDouble()
                            accuracy = prefs.getFloat("last_known_accuracy", 0f)
                            time = cachedTime
                        }
                        
                        Log.d("VoskService", "✅ Using cached location for alert")
                    } else {
                        Log.e("VoskService", "❌ No cached location available, sending alert WITHOUT location")
                        launch(Dispatchers.Main) {
                            Toast.makeText(
                                this@VoskWakeWordService,
                                "⚠️ Could not get location. Alert sent without location.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                } else {
                    Log.d("VoskService", "✅ Will send alert WITH fresh location: ${location.latitude}, ${location.longitude}")
                }
                
                Log.d("VoskService", "========================================")
                Log.d("VoskService", "📸 Capturing emergency photos...")
                Log.d("VoskService", "========================================")
                
                // Check if photo capture is enabled in settings
                val photoSettingsPrefs = getSharedPreferences("vasatey_settings", MODE_PRIVATE)
                val photoCaptureEnabled = photoSettingsPrefs.getBoolean("photo_capture_enabled", true)
                
                val photos = if (photoCaptureEnabled) {
                    // Show progress notification
                    launch(Dispatchers.Main) {
                        showResultNotification("📸 Capturing Photos", "Taking emergency photos from both cameras...")
                    }
                    
                    // Capture photos from both cameras
                    val capturedPhotos = CameraManager.captureEmergencyPhotos(this@VoskWakeWordService)
                    
                    Log.d("VoskService", "========================================")
                    Log.d("VoskService", "📸 Photo capture results:")
                    Log.d("VoskService", "Front photo: ${if (capturedPhotos.frontPhoto != null) "✅ ${capturedPhotos.frontPhoto.absolutePath} (${capturedPhotos.frontPhoto.length()} bytes)" else "❌ NULL"}")
                    Log.d("VoskService", "Back photo: ${if (capturedPhotos.backPhoto != null) "✅ ${capturedPhotos.backPhoto.absolutePath} (${capturedPhotos.backPhoto.length()} bytes)" else "❌ NULL"}")
                    Log.d("VoskService", "========================================")
                    
                    capturedPhotos
                } else {
                    Log.d("VoskService", "📸 Photo capture disabled in settings - skipping photos")
                    CameraManager.CapturedPhotos(null, null)
                }
                
                Log.d("VoskService", "========================================")
                
                Log.d("VoskService", "Sending emergency alert...")
                
                // Show uploading notification
                launch(Dispatchers.Main) {
                    showResultNotification("📤 Uploading Data", "Uploading photos and sending alert to guardians...")
                }
                
                // Send alert to guardians with photos
                val result = AlertManager.sendEmergencyAlert(
                    context = this@VoskWakeWordService,
                    latitude = location?.latitude,
                    longitude = location?.longitude,
                    locationAccuracy = location?.accuracy,
                    frontPhotoFile = photos.frontPhoto,
                    backPhotoFile = photos.backPhoto
                )
                
                // Show result notification
                launch(Dispatchers.Main) {
                    result.onSuccess { message ->
                        Log.d("VoskService", "Alert sent successfully: $message")
                        showResultNotification("✅ Alert Sent", message)
                        Toast.makeText(this@VoskWakeWordService, message, Toast.LENGTH_LONG).show()
                    }.onFailure { error ->
                        Log.e("VoskService", "Failed to send alert", error)
                        showResultNotification("❌ Alert Failed", error.message ?: "Unknown error")
                        Toast.makeText(this@VoskWakeWordService, "Failed: ${error.message}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("VoskService", "Error triggering alert", e)
                launch(Dispatchers.Main) {
                    showResultNotification("❌ Error", "Failed to send alert: ${e.message}")
                }
            }
        }
    }
    
    private fun showResultNotification(title: String, message: String) {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "ALERT_RESULT_CHANNEL"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Alert Results", NotificationManager.IMPORTANCE_HIGH)
            channel.description = "Notifications for alert sending results"
            channel.enableVibration(true)
            channel.vibrationPattern = longArrayOf(0, 200, 100, 200, 100, 200)
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 200, 100, 200, 100, 200))
            .build()

        notificationManager.notify(3, notification)
    }

    private fun getResultTextFromJson(json: String): String {
        return try {
            json.substringAfter("\"text\" : \"").substringBefore("\"")
        } catch (e: Exception) {
            ""
        }
    }

    override fun onFinalResult(hypothesis: String?) {}

    override fun onError(exception: Exception?) {
        Log.e("VoskService", "Vosk error", exception)
    }

    override fun onTimeout() {
        speechService?.startListening(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        speechService?.stop()
        speechService?.shutdown()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}