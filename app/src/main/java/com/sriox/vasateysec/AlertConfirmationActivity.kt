package com.sriox.vasateysec

import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sriox.vasateysec.databinding.ActivityAlertConfirmationBinding
import com.sriox.vasateysec.utils.AlertConfirmationManager
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.launch

class AlertConfirmationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlertConfirmationBinding
    private var alertId: String? = null
    private var guardianEmail: String? = null
    private var countDownTimer: CountDownTimer? = null
    private var isExpired = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("AlertConfirmation", "=== onCreate called ===")
        
        binding = ActivityAlertConfirmationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Get data from intent
        alertId = intent.getStringExtra("alertId")
        guardianEmail = intent.getStringExtra("guardianEmail")

        Log.d("AlertConfirmation", "Received Alert ID: $alertId")
        Log.d("AlertConfirmation", "Received Guardian: $guardianEmail")
        Log.d("AlertConfirmation", "All extras: ${intent.extras?.keySet()?.joinToString()}")

        if (alertId == null || guardianEmail == null) {
            Log.e("AlertConfirmation", "❌ Missing data! AlertId: $alertId, Guardian: $guardianEmail")
            Toast.makeText(this, "Invalid alert data. Missing: ${if (alertId == null) "alertId " else ""}${if (guardianEmail == null) "guardianEmail" else ""}", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        Log.d("AlertConfirmation", "✅ Data valid, checking if expired")
        checkAndStartTimer()
    }

    private fun checkAndStartTimer() {
        lifecycleScope.launch {
            try {
                // Get the confirmation record to check when it was confirmed
                val confirmation = SupabaseClient.client.from("alert_confirmations")
                    .select {
                        filter {
                            eq("alert_id", alertId ?: "")
                        }
                    }
                    .decodeSingle<Map<String, Any?>>()

                val createdAt = confirmation["created_at"] as? String
                
                if (createdAt != null) {
                    // Calculate elapsed time from when confirmation was created
                    val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault())
                    format.timeZone = java.util.TimeZone.getTimeZone("UTC")
                    val createdTime = format.parse(createdAt)?.time ?: 0
                    val now = System.currentTimeMillis()
                    val elapsed = now - createdTime
                    
                    Log.d("AlertConfirmation", "Confirmation created at: $createdAt")
                    Log.d("AlertConfirmation", "Elapsed time: ${elapsed}ms (${elapsed/1000}s)")
                    Log.d("AlertConfirmation", "Expiry time: 60 seconds")
                    
                    if (elapsed >= 60000) {
                        // Already expired (more than 1 minute passed)
                        Log.d("AlertConfirmation", "❌ Alert already expired!")
                        showExpiredUI()
                        return@launch
                    }
                    
                    // Calculate remaining time
                    val remainingMs = 60000 - elapsed
                    Log.d("AlertConfirmation", "⏱️ Remaining time: ${remainingMs}ms (${remainingMs/1000}s)")
                    
                    setupUI()
                    startTimer(remainingMs)
                } else {
                    // No created_at timestamp, use full 60 seconds
                    Log.d("AlertConfirmation", "⚠️ No created_at timestamp, using full 60 seconds")
                    setupUI()
                    startTimer(60000)
                }
                
            } catch (e: Exception) {
                Log.e("AlertConfirmation", "Error checking confirmation time", e)
                // Fallback to full 60 seconds
                setupUI()
                startTimer(60000)
            }
        }
    }

    private fun setupUI() {
        binding.confirmationMessage.text = 
            "Guardian $guardianEmail has confirmed your alert.\n\nIf this is a FALSE ALARM, you have 60 seconds to cancel it."

        binding.cancelAlertButton.setOnClickListener {
            cancelAlert()
        }

        binding.keepAlertButton.setOnClickListener {
            Toast.makeText(this, "Alert kept active", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
    
    private fun showExpiredUI() {
        binding.confirmationMessage.text = 
            "Guardian $guardianEmail confirmed your alert.\n\nThe 60-second cancellation window has expired. Alert remains active."
        
        binding.timerText.text = "0"
        binding.timerText.setTextColor(getColor(android.R.color.holo_red_light))
        binding.statusMessage.text = "⏱️ Time expired! Alert remains active."
        binding.statusMessage.visibility = View.VISIBLE
        binding.statusMessage.setTextColor(getColor(android.R.color.holo_red_light))
        
        binding.cancelAlertButton.isEnabled = false
        binding.keepAlertButton.isEnabled = false
        
        Toast.makeText(this, "Cancellation window expired. Alert remains active.", Toast.LENGTH_LONG).show()
        
        // Notify guardian that user did NOT cancel
        notifyGuardianExpired()
        
        // Close after 3 seconds
        binding.root.postDelayed({
            finish()
        }, 3000)
    }

    private fun startTimer(durationMs: Long = 30000) {
        Log.d("AlertConfirmation", "Starting timer with ${durationMs}ms (${durationMs/1000}s)")
        countDownTimer = object : CountDownTimer(durationMs, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsRemaining = (millisUntilFinished / 1000).toInt()
                binding.timerText.text = secondsRemaining.toString()
                
                // Change color as time runs out
                when {
                    secondsRemaining <= 10 -> binding.timerText.setTextColor(getColor(android.R.color.holo_red_light))
                    secondsRemaining <= 20 -> binding.timerText.setTextColor(getColor(android.R.color.holo_orange_light))
                    else -> binding.timerText.setTextColor(getColor(R.color.golden))
                }
            }

            override fun onFinish() {
                isExpired = true
                binding.timerText.text = "0"
                binding.timerText.setTextColor(getColor(android.R.color.holo_red_light))
                binding.statusMessage.text = "⏱️ Time expired! Alert remains active."
                binding.statusMessage.visibility = View.VISIBLE
                binding.statusMessage.setTextColor(getColor(android.R.color.holo_red_light))
                
                // Disable buttons
                binding.cancelAlertButton.isEnabled = false
                binding.keepAlertButton.isEnabled = false
                
                Toast.makeText(this@AlertConfirmationActivity, "Time expired. Alert remains active.", Toast.LENGTH_LONG).show()
                
                // Notify guardian that user did NOT cancel
                notifyGuardianExpired()
                
                // Close activity after 3 seconds
                binding.root.postDelayed({
                    finish()
                }, 3000)
            }
        }.start()
    }

    private fun cancelAlert() {
        if (isExpired) {
            Toast.makeText(this, "Time expired. Cannot cancel alert.", Toast.LENGTH_SHORT).show()
            return
        }

        val password = binding.passwordInput.text.toString()

        if (password.isEmpty()) {
            Toast.makeText(this, "Please enter your cancel password", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                binding.cancelAlertButton.isEnabled = false
                binding.cancelAlertButton.text = "Cancelling..."

                val result = AlertConfirmationManager.cancelAlert(
                    context = this@AlertConfirmationActivity,
                    alertId = alertId!!,
                    guardianEmail = guardianEmail!!,
                    password = password
                )

                result.onSuccess { message ->
                    // Stop timer
                    countDownTimer?.cancel()
                    
                    binding.statusMessage.text = "✅ $message"
                    binding.statusMessage.visibility = View.VISIBLE
                    binding.statusMessage.setTextColor(getColor(android.R.color.holo_green_light))
                    
                    Toast.makeText(this@AlertConfirmationActivity, message, Toast.LENGTH_LONG).show()
                    
                    // Disable all buttons
                    binding.cancelAlertButton.isEnabled = false
                    binding.keepAlertButton.isEnabled = false
                    
                    // Close activity after 2 seconds
                    binding.root.postDelayed({
                        finish()
                    }, 2000)
                    
                }.onFailure { error ->
                    binding.cancelAlertButton.isEnabled = true
                    binding.cancelAlertButton.text = "Cancel Alert"
                    
                    binding.statusMessage.text = "❌ ${error.message}"
                    binding.statusMessage.visibility = View.VISIBLE
                    binding.statusMessage.setTextColor(getColor(android.R.color.holo_red_light))
                    
                    Toast.makeText(this@AlertConfirmationActivity, "Error: ${error.message}", Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                Toast.makeText(this@AlertConfirmationActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                binding.cancelAlertButton.isEnabled = true
                binding.cancelAlertButton.text = "Cancel Alert"
            }
        }
    }

    private fun notifyGuardianExpired() {
        lifecycleScope.launch {
            try {
                AlertConfirmationManager.notifyGuardianExpired(
                    context = this@AlertConfirmationActivity,
                    alertId = alertId ?: "",
                    guardianEmail = guardianEmail ?: ""
                )
                Log.d("AlertConfirmation", "Guardian notified: alert not cancelled")
            } catch (e: Exception) {
                Log.e("AlertConfirmation", "Failed to notify guardian", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
    }
}
