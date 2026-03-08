package com.sriox.vasateysec

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import com.sriox.vasateysec.databinding.ActivityMyAlertsBinding
import com.sriox.vasateysec.databinding.ItemAlertConfirmationBinding
import com.sriox.vasateysec.models.AlertConfirmation
import com.sriox.vasateysec.utils.AlertConfirmationManager
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MyAlertsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMyAlertsBinding
    private lateinit var adapter: AlertConfirmationAdapter
    private val confirmations = mutableListOf<AlertConfirmation>()
    private var currentFilter = "all"
    private val guardianNameCache = mutableMapOf<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMyAlertsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        setupRecyclerView()
        loadConfirmations()
    }

    private fun setupUI() {
        binding.backButton.setOnClickListener {
            finish()
        }

        // Filter buttons
        binding.filterAllButton.setOnClickListener {
            updateFilter("all", binding.filterAllButton.id)
        }
        binding.filterPendingButton.setOnClickListener {
            updateFilter("pending", binding.filterPendingButton.id)
        }
        binding.filterCancelledButton.setOnClickListener {
            updateFilter("cancelled", binding.filterCancelledButton.id)
        }
        binding.filterExpiredButton.setOnClickListener {
            updateFilter("expired", binding.filterExpiredButton.id)
        }
    }

    private fun setupRecyclerView() {
        adapter = AlertConfirmationAdapter(
            confirmations = confirmations,
            onCancelClick = { confirmation ->
                showCancelDialog(confirmation)
            }
        )
        binding.alertsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.alertsRecyclerView.adapter = adapter
    }

    private fun updateFilter(filter: String, selectedButtonId: Int) {
        currentFilter = filter

        // Update button styles
        val buttons = listOf(
            binding.filterAllButton,
            binding.filterPendingButton,
            binding.filterCancelledButton,
            binding.filterExpiredButton
        )

        buttons.forEach { button ->
            if (button.id == selectedButtonId) {
                button.setBackgroundColor(getColor(R.color.violet))
                button.setTextColor(getColor(R.color.text_primary))
            } else {
                button.setBackgroundColor(getColor(R.color.card_elevated))
                button.setTextColor(getColor(R.color.text_secondary))
            }
        }

        loadConfirmations()
    }

    private fun loadConfirmations() {
        binding.progressBar.visibility = View.VISIBLE
        binding.emptyStateLayout.visibility = View.GONE

        lifecycleScope.launch {
            try {
                // Get current user
                val currentUser = SupabaseClient.client.auth.currentUserOrNull()
                if (currentUser == null) {
                    Toast.makeText(this@MyAlertsActivity, "Please log in", Toast.LENGTH_SHORT).show()
                    binding.progressBar.visibility = View.GONE
                    return@launch
                }
                
                val allConfirmations = AlertConfirmationManager.getUserAlertConfirmations(currentUser.id)

                // Filter based on current filter
                val filtered = when (currentFilter) {
                    "pending" -> allConfirmations.filter { it.confirmation_status == "pending" }
                    "cancelled" -> allConfirmations.filter { it.confirmation_status == "cancelled" }
                    "expired" -> allConfirmations.filter { it.confirmation_status == "expired" }
                    else -> allConfirmations
                }

                confirmations.clear()
                // Sort by created_at descending (newest first)
                confirmations.addAll(filtered.sortedByDescending { it.created_at })
                adapter.notifyDataSetChanged()

                if (confirmations.isEmpty()) {
                    binding.emptyStateLayout.visibility = View.VISIBLE
                    binding.alertsRecyclerView.visibility = View.GONE
                } else {
                    binding.emptyStateLayout.visibility = View.GONE
                    binding.alertsRecyclerView.visibility = View.VISIBLE
                }

            } catch (e: Exception) {
                Toast.makeText(this@MyAlertsActivity, "Error loading alerts: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun showCancelDialog(confirmation: AlertConfirmation) {
        android.util.Log.d("MyAlerts", "=== Opening AlertDetailActivity ===")
        android.util.Log.d("MyAlerts", "Alert ID: ${confirmation.alert_id}")
        android.util.Log.d("MyAlerts", "Confirmation ID: ${confirmation.id}")
        android.util.Log.d("MyAlerts", "Status: ${confirmation.confirmation_status}")
        
        // Open AlertDetailActivity to show all information
        val intent = Intent(this, AlertDetailActivity::class.java).apply {
            putExtra("alertId", confirmation.alert_id)
            putExtra("confirmationId", confirmation.id)
        }
        
        try {
            startActivity(intent)
            android.util.Log.d("MyAlerts", "✅ Activity started successfully")
        } catch (e: Exception) {
            android.util.Log.e("MyAlerts", "❌ Failed to start activity", e)
            Toast.makeText(this, "Error opening alert details: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun cancelAlert(confirmation: AlertConfirmation, password: String) {
        lifecycleScope.launch {
            try {
                val result = AlertConfirmationManager.cancelAlert(
                    context = this@MyAlertsActivity,
                    alertId = confirmation.alert_id,
                    guardianEmail = confirmation.guardian_email,
                    password = password
                )

                result.onSuccess { message ->
                    Toast.makeText(this@MyAlertsActivity, message, Toast.LENGTH_LONG).show()
                    loadConfirmations() // Refresh list
                }.onFailure { error ->
                    Toast.makeText(this@MyAlertsActivity, "Error: ${error.message}", Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                Toast.makeText(this@MyAlertsActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private suspend fun getGuardianName(email: String): String? {
        // Check cache first
        if (guardianNameCache.containsKey(email)) {
            return guardianNameCache[email]
        }
        
        return try {
            val user = SupabaseClient.client.from("users")
                .select {
                    filter {
                        eq("email", email)
                    }
                }
                .decodeSingle<Map<String, String>>()
            
            val name = user["name"] ?: email
            guardianNameCache[email] = name
            name
        } catch (e: Exception) {
            email
        }
    }

    // Adapter for RecyclerView
    inner class AlertConfirmationAdapter(
        private val confirmations: List<AlertConfirmation>,
        private val onCancelClick: (AlertConfirmation) -> Unit
    ) : RecyclerView.Adapter<AlertConfirmationAdapter.ViewHolder>() {

        inner class ViewHolder(val binding: ItemAlertConfirmationBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemAlertConfirmationBinding.inflate(layoutInflater, parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val confirmation = confirmations[position]

            // Set guardian name - fetch from database or show email as fallback
            holder.binding.guardianName.text = "Loading..."
            lifecycleScope.launch {
                try {
                    val guardianName = getGuardianName(confirmation.guardian_email)
                    holder.binding.guardianName.text = guardianName ?: confirmation.guardian_email
                } catch (e: Exception) {
                    holder.binding.guardianName.text = confirmation.guardian_email
                }
            }

            // Set time
            confirmation.confirmed_at?.let { confirmedAt ->
                holder.binding.confirmationTime.text = getRelativeTime(confirmedAt)
            }

            // Set status
            when (confirmation.confirmation_status) {
                "pending" -> {
                    holder.binding.statusIcon.text = "⚠️"
                    holder.binding.statusText.text = "PENDING"
                    holder.binding.statusBadge.setCardBackgroundColor(getColor(R.color.golden))
                    holder.binding.alertDetails.text = "Tap to view details and cancel"
                    holder.binding.cancelButton.visibility = View.GONE
                }
                "cancelled" -> {
                    holder.binding.statusIcon.text = "✅"
                    holder.binding.statusText.text = "CANCELLED"
                    holder.binding.statusBadge.setCardBackgroundColor(getColor(R.color.success))
                    holder.binding.alertDetails.text = "You cancelled this alert. Guardian was notified."
                    holder.binding.cancelButton.visibility = View.GONE
                }
                "expired" -> {
                    holder.binding.statusIcon.text = "⏰"
                    holder.binding.statusText.text = "EXPIRED"
                    holder.binding.statusBadge.setCardBackgroundColor(getColor(R.color.text_secondary))
                    holder.binding.alertDetails.text = "Cancellation window expired. Alert remains active."
                    holder.binding.cancelButton.visibility = View.GONE
                }
                else -> {
                    holder.binding.statusIcon.text = "📋"
                    holder.binding.statusText.text = "CONFIRMED"
                    holder.binding.statusBadge.setCardBackgroundColor(getColor(R.color.violet))
                    holder.binding.alertDetails.text = "Guardian confirmed your alert"
                    holder.binding.cancelButton.visibility = View.GONE
                }
            }

            // Make ALL alerts clickable to view details
            holder.itemView.setOnClickListener {
                android.util.Log.d("MyAlerts", "✅✅✅ CARD CLICKED for alert: ${confirmation.alert_id}")
                Toast.makeText(this@MyAlertsActivity, "Opening alert details...", Toast.LENGTH_SHORT).show()
                onCancelClick(confirmation)
            }
            
            android.util.Log.d("MyAlerts", "✅ Configured clickable for alert: ${confirmation.alert_id}, status: ${confirmation.confirmation_status}")
        }

        override fun getItemCount() = confirmations.size

        private fun getRelativeTime(dateStr: String): String {
            return try {
                // Parse UTC timestamp from Supabase
                val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
                format.timeZone = java.util.TimeZone.getTimeZone("UTC")
                val date = format.parse(dateStr)
                
                // Get current time
                val now = Date()
                val diff = now.time - (date?.time ?: 0)

                when {
                    diff < 60000 -> "Just now"
                    diff < 3600000 -> "${diff / 60000} minutes ago"
                    diff < 86400000 -> "${diff / 3600000} hours ago"
                    else -> "${diff / 86400000} days ago"
                }
            } catch (e: Exception) {
                "Recently"
            }
        }
    }
}
