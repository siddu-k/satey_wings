package com.sriox.vasateysec

import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sriox.vasateysec.databinding.ActivityAlertDetailBinding
import com.sriox.vasateysec.utils.AlertConfirmationManager
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class AlertDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlertDetailBinding
    private var alertId: String? = null
    private var confirmationId: String? = null
    private var countDownTimer: CountDownTimer? = null
    private var isExpired = false
    private var currentConfirmation: com.sriox.vasateysec.models.AlertConfirmation? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlertDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        alertId = intent.getStringExtra("alertId")
        confirmationId = intent.getStringExtra("confirmationId")

        setupUI()
        loadAlertDetails()
    }

    private fun setupUI() {
        binding.backButton.setOnClickListener {
            finish()
        }

        binding.cancelAlertButton.setOnClickListener {
            performCancel()
        }
        
        binding.keepAlertButton.setOnClickListener {
            countDownTimer?.cancel()
            Toast.makeText(this, "Alert kept active", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun loadAlertDetails() {
        binding.progressBar.visibility = View.VISIBLE
        binding.contentLayout.visibility = View.GONE

        lifecycleScope.launch {
            try {
                // Get alert confirmation details
                val confirmationResponse = SupabaseClient.client.from("alert_confirmations")
                    .select {
                        filter {
                            eq("id", confirmationId ?: "")
                        }
                    }
                    .decodeList<com.sriox.vasateysec.models.AlertConfirmation>()
                
                if (confirmationResponse.isEmpty()) {
                    Toast.makeText(this@AlertDetailActivity, "Alert confirmation not found", Toast.LENGTH_SHORT).show()
                    finish()
                    return@launch
                }
                
                val confirmation = confirmationResponse[0]

                // Get alert history details
                val alertResponse = SupabaseClient.client.from("alert_history")
                    .select {
                        filter {
                            eq("id", alertId ?: "")
                        }
                    }
                    .decodeList<com.sriox.vasateysec.models.AlertHistory>()
                
                if (alertResponse.isEmpty()) {
                    Toast.makeText(this@AlertDetailActivity, "Alert not found", Toast.LENGTH_SHORT).show()
                    finish()
                    return@launch
                }
                
                val alert = alertResponse[0]

                // Get user details
                val userId = alert.user_id
                val user = if (userId != null) {
                    try {
                        val userResponse = SupabaseClient.client.from("users")
                            .select {
                                filter {
                                    eq("id", userId)
                                }
                            }
                            .decodeList<com.sriox.vasateysec.models.User>()
                        if (userResponse.isNotEmpty()) userResponse[0] else null
                    } catch (e: Exception) {
                        android.util.Log.e("AlertDetail", "Error loading user", e)
                        null
                    }
                } else null

                displayAlertDetails(confirmation, alert, user)

            } catch (e: Exception) {
                Toast.makeText(this@AlertDetailActivity, "Error loading details: ${e.message}", Toast.LENGTH_LONG).show()
                android.util.Log.e("AlertDetail", "Error loading", e)
                e.printStackTrace()
                finish()
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun displayAlertDetails(
        confirmation: com.sriox.vasateysec.models.AlertConfirmation,
        alert: com.sriox.vasateysec.models.AlertHistory,
        user: com.sriox.vasateysec.models.User?
    ) {
        binding.contentLayout.visibility = View.VISIBLE
        currentConfirmation = confirmation

        // Alert ID
        binding.alertIdText.text = alertId ?: "N/A"

        // User Info
        binding.userNameText.text = user?.name ?: "Unknown"
        binding.userEmailText.text = user?.email ?: "Unknown"
        binding.userPhoneText.text = user?.phone ?: "N/A"

        // Guardian Info
        binding.guardianEmailText.text = confirmation.guardian_email

        // Status
        val status = confirmation.confirmation_status
        binding.statusText.text = status.uppercase()
        
        when (status) {
            "pending" -> {
                binding.statusBadge.setCardBackgroundColor(getColor(R.color.golden))
                binding.actionButtonsLayout.visibility = View.VISIBLE
                binding.passwordInputLayout.visibility = View.VISIBLE
                binding.timerLayout.visibility = View.VISIBLE
                // Start timer for pending alerts - use created_at (when confirmation was created)
                checkAndStartTimer(confirmation.created_at)
            }
            "cancelled" -> {
                binding.statusBadge.setCardBackgroundColor(getColor(R.color.success))
                binding.actionButtonsLayout.visibility = View.GONE
                binding.passwordInputLayout.visibility = View.GONE
                binding.timerLayout.visibility = View.GONE
            }
            "expired" -> {
                binding.statusBadge.setCardBackgroundColor(getColor(R.color.text_secondary))
                binding.actionButtonsLayout.visibility = View.GONE
                binding.passwordInputLayout.visibility = View.GONE
                binding.timerLayout.visibility = View.GONE
            }
            else -> {
                binding.statusBadge.setCardBackgroundColor(getColor(R.color.violet))
                binding.actionButtonsLayout.visibility = View.GONE
                binding.passwordInputLayout.visibility = View.GONE
                binding.timerLayout.visibility = View.GONE
            }
        }

        // Timestamps
        binding.confirmedAtText.text = formatTimestamp(confirmation.confirmed_at)
        binding.createdAtText.text = formatTimestamp(confirmation.created_at)
        
        val cancelledAt = confirmation.cancelled_at
        if (cancelledAt != null) {
            binding.cancelledAtLabel.visibility = View.VISIBLE
            binding.cancelledAtText.visibility = View.VISIBLE
            binding.cancelledAtText.text = formatTimestamp(cancelledAt)
        } else {
            binding.cancelledAtLabel.visibility = View.GONE
            binding.cancelledAtText.visibility = View.GONE
        }

        // Location
        val latitude = alert.latitude
        val longitude = alert.longitude
        if (latitude != null && longitude != null) {
            binding.locationText.text = "$latitude, $longitude"
        } else {
            binding.locationText.text = "Location not available"
        }

        // Alert Type
        binding.alertTypeText.text = alert.alert_type ?: "Emergency"

        // Alert Created At
        binding.alertCreatedAtText.text = formatTimestamp(alert.created_at)
    }

    private fun formatTimestamp(timestamp: String?): String {
        if (timestamp == null) return "N/A"
        
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            format.timeZone = TimeZone.getTimeZone("UTC")
            val date = format.parse(timestamp)
            
            val displayFormat = SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.getDefault())
            displayFormat.format(date ?: Date())
        } catch (e: Exception) {
            timestamp
        }
    }

    private fun checkAndStartTimer(createdAt: String?) {
        if (createdAt == null) {
            android.util.Log.w("AlertDetail", "No created_at timestamp, using full 60 seconds")
            startTimer(60000)
            return
        }

        try {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            format.timeZone = TimeZone.getTimeZone("UTC")
            val createdTime = format.parse(createdAt)?.time ?: 0
            val now = System.currentTimeMillis()
            val elapsed = now - createdTime

            android.util.Log.d("AlertDetail", "Confirmation created at: $createdAt")
            android.util.Log.d("AlertDetail", "Elapsed time: ${elapsed}ms (${elapsed/1000}s)")
            android.util.Log.d("AlertDetail", "Expiry time: 60 seconds")

            if (elapsed >= 60000) {
                // Already expired (more than 1 minute passed)
                android.util.Log.d("AlertDetail", "Alert already expired!")
                showExpiredState()
            } else {
                // Start timer with remaining time
                val remainingMs = 60000 - elapsed
                android.util.Log.d("AlertDetail", "Starting timer with ${remainingMs}ms (${remainingMs/1000}s)")
                startTimer(remainingMs)
            }
        } catch (e: Exception) {
            android.util.Log.e("AlertDetail", "Error parsing timestamp, using full 60 seconds", e)
            startTimer(60000)
        }
    }

    private fun startTimer(durationMs: Long) {
        countDownTimer = object : CountDownTimer(durationMs, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsRemaining = (millisUntilFinished / 1000).toInt()
                binding.timerText.text = "$secondsRemaining"
                
                when {
                    secondsRemaining <= 10 -> binding.timerText.setTextColor(getColor(android.R.color.holo_red_light))
                    secondsRemaining <= 20 -> binding.timerText.setTextColor(getColor(android.R.color.holo_orange_light))
                    else -> binding.timerText.setTextColor(getColor(R.color.golden))
                }
            }

            override fun onFinish() {
                showExpiredState()
                notifyGuardianExpired()
            }
        }.start()
    }

    private fun showExpiredState() {
        isExpired = true
        binding.timerText.text = "0"
        binding.timerText.setTextColor(getColor(android.R.color.holo_red_light))
        binding.statusText.text = "EXPIRED"
        binding.statusBadge.setCardBackgroundColor(getColor(R.color.text_secondary))
        binding.actionButtonsLayout.visibility = View.GONE
        binding.passwordInputLayout.visibility = View.GONE
        
        // Update status in database
        lifecycleScope.launch {
            try {
                SupabaseClient.client.from("alert_confirmations")
                    .update(mapOf("confirmation_status" to "expired")) {
                        filter {
                            eq("id", confirmationId ?: "")
                        }
                    }
                android.util.Log.d("AlertDetail", "✅ Status updated to expired in database")
            } catch (e: Exception) {
                android.util.Log.e("AlertDetail", "Failed to update status", e)
            }
        }
        
        Toast.makeText(this, "Time expired. Alert remains active.", Toast.LENGTH_LONG).show()
    }

    private fun notifyGuardianExpired() {
        lifecycleScope.launch {
            try {
                AlertConfirmationManager.notifyGuardianExpired(
                    context = this@AlertDetailActivity,
                    alertId = alertId ?: "",
                    guardianEmail = currentConfirmation?.guardian_email ?: ""
                )
            } catch (e: Exception) {
                android.util.Log.e("AlertDetail", "Failed to notify guardian", e)
            }
        }
    }

    private fun performCancel() {
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
                    context = this@AlertDetailActivity,
                    alertId = alertId ?: "",
                    guardianEmail = currentConfirmation?.guardian_email ?: "",
                    password = password
                )

                result.onSuccess { message ->
                    countDownTimer?.cancel()
                    Toast.makeText(this@AlertDetailActivity, message, Toast.LENGTH_LONG).show()
                    finish()
                }.onFailure { error ->
                    binding.cancelAlertButton.isEnabled = true
                    binding.cancelAlertButton.text = "Cancel This Alert"
                    Toast.makeText(this@AlertDetailActivity, "Error: ${error.message}", Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                binding.cancelAlertButton.isEnabled = true
                binding.cancelAlertButton.text = "Cancel This Alert"
                Toast.makeText(this@AlertDetailActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
    }
}
