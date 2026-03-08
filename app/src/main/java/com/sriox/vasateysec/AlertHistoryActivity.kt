package com.sriox.vasateysec

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sriox.vasateysec.databinding.ActivityAlertHistoryBinding
import com.sriox.vasateysec.models.AlertHistory
import com.sriox.vasateysec.models.AlertRecipient
import com.sriox.vasateysec.models.ContactRequest
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AlertHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlertHistoryBinding
    private lateinit var alertAdapter: AlertAdapter
    private val historyItems = mutableListOf<HistoryItem>()
    private var currentOffset = 0
    private val pageSize = 10
    private var isLoading = false
    private var hasMore = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlertHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupLoadMore()
        setupBottomNavigation()
        setupFilterButtons()
        
        com.sriox.vasateysec.utils.BottomNavHelper.highlightActiveItem(
            this,
            com.sriox.vasateysec.utils.BottomNavHelper.NavItem.HISTORY
        )
        
        findViewById<android.widget.ImageView>(R.id.backButton)?.setOnClickListener {
            finish()
        }
        
        loadHistory(refresh = true)
    }
    
    private fun setupBottomNavigation() {
        val navGuardians = findViewById<android.widget.LinearLayout>(R.id.navGuardians)
        val navHistory = findViewById<android.widget.LinearLayout>(R.id.navHistory)
        val sosButton = findViewById<com.google.android.material.card.MaterialCardView>(R.id.sosButton)
        val navGhistory = findViewById<android.widget.LinearLayout>(R.id.navGhistory)
        val navProfile = findViewById<android.widget.LinearLayout>(R.id.navProfile)
        
        navGuardians?.setOnClickListener {
            val intent = Intent(this, AddGuardianActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
        }
        navHistory?.setOnClickListener { }
        sosButton?.setOnClickListener {
            com.sriox.vasateysec.utils.SOSHelper.showSOSConfirmation(this)
        }
        navGhistory?.setOnClickListener {
            val intent = Intent(this, GuardianMapActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
        }
        navProfile?.setOnClickListener {
            val intent = Intent(this, EditProfileActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
        }
    }
    
    private fun setupFilterButtons() {
        binding.btnAllAlerts.setOnClickListener {
            updateFilterSelection(it.id)
            loadHistory(refresh = true)
        }
        binding.btnSentAlerts.setOnClickListener {
            updateFilterSelection(it.id)
            loadHistory(refresh = true, filterSent = true)
        }
        binding.btnReceivedAlerts.setOnClickListener {
            updateFilterSelection(it.id)
            loadHistory(refresh = true, filterReceived = true)
        }
    }
    
    private fun updateFilterSelection(selectedId: Int) {
        binding.btnAllAlerts.apply { setBackgroundColor(getColor(R.color.card_elevated)); setTextColor(getColor(R.color.text_secondary)) }
        binding.btnSentAlerts.apply { setBackgroundColor(getColor(R.color.card_elevated)); setTextColor(getColor(R.color.text_secondary)) }
        binding.btnReceivedAlerts.apply { setBackgroundColor(getColor(R.color.card_elevated)); setTextColor(getColor(R.color.text_secondary)) }
        
        when (selectedId) {
            R.id.btnAllAlerts -> binding.btnAllAlerts.apply { setBackgroundColor(getColor(R.color.violet)); setTextColor(getColor(R.color.text_primary)) }
            R.id.btnSentAlerts -> binding.btnSentAlerts.apply { setBackgroundColor(getColor(R.color.violet)); setTextColor(getColor(R.color.text_primary)) }
            R.id.btnReceivedAlerts -> binding.btnReceivedAlerts.apply { setBackgroundColor(getColor(R.color.violet)); setTextColor(getColor(R.color.text_primary)) }
        }
    }

    override fun onResume() {
        super.onResume()
        loadHistory(refresh = true)
    }

    private fun setupLoadMore() {
        binding.loadMoreButton.setOnClickListener {
            if (!isLoading && hasMore) {
                loadHistory(refresh = false)
            }
        }
    }

    private fun setupRecyclerView() {
        val currentUserId = SupabaseClient.client.auth.currentUserOrNull()?.id ?: ""
        alertAdapter = AlertAdapter(historyItems, currentUserId) { item ->
            if (item is HistoryItem.Alert) openAlertDetails(item.alert)
            else if (item is HistoryItem.Contact) openContactDetails(item.request)
        }
        binding.alertsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@AlertHistoryActivity)
            adapter = alertAdapter
        }
    }

    private fun loadHistory(refresh: Boolean = false, filterSent: Boolean = false, filterReceived: Boolean = false) {
        if (isLoading) return
        
        lifecycleScope.launch {
            try {
                isLoading = true
                binding.loadingProgress.visibility = View.VISIBLE
                binding.loadMoreButton.visibility = View.GONE
                
                if (refresh) {
                    currentOffset = 0
                    hasMore = true
                    historyItems.clear()
                }
                
                val currentUser = SupabaseClient.client.auth.currentUserOrNull() ?: return@launch

                // Fetch Alerts
                val alertsDeferred = async {
                    try {
                        val sent = SupabaseClient.client.from("alert_history").select { filter { eq("user_id", currentUser.id) } }.decodeList<AlertHistory>()
                        val receivedRecipients = SupabaseClient.client.from("alert_recipients").select { filter { eq("guardian_user_id", currentUser.id) } }.decodeList<AlertRecipient>()
                        val receivedIds = receivedRecipients.mapNotNull { it.alert_id }
                        val received = if (receivedIds.isEmpty()) emptyList() else SupabaseClient.client.from("alert_history").select { filter { isIn("id", receivedIds) } }.decodeList<AlertHistory>()
                        
                        when {
                            filterSent -> sent
                            filterReceived -> received
                            else -> (sent + received).distinctBy { it.id }
                        }
                    } catch (e: Exception) { emptyList() }
                }

                // Fetch Contact Requests
                val contactsDeferred = async {
                    try {
                        val sent = SupabaseClient.client.from("contact_requests").select { filter { eq("from_user_id", currentUser.id) } }.decodeList<ContactRequest>()
                        val received = SupabaseClient.client.from("contact_requests").select { filter { eq("to_user_id", currentUser.id) } }.decodeList<ContactRequest>()
                        
                        when {
                            filterSent -> sent
                            filterReceived -> received
                            else -> (sent + received).distinctBy { it.id }
                        }
                    } catch (e: Exception) { emptyList() }
                }

                val allAlerts = alertsDeferred.await().map { HistoryItem.Alert(it) }
                val allContacts = contactsDeferred.await().map { HistoryItem.Contact(it) }
                
                val combined = (allAlerts + allContacts).sortedByDescending { it.timestamp }
                val paged = combined.drop(currentOffset).take(pageSize)

                historyItems.addAll(paged)
                hasMore = paged.size >= pageSize
                currentOffset += paged.size

                if (historyItems.isEmpty()) {
                    binding.emptyView.visibility = View.VISIBLE
                    binding.alertsRecyclerView.visibility = View.GONE
                } else {
                    binding.emptyView.visibility = View.GONE
                    binding.alertsRecyclerView.visibility = View.VISIBLE
                    binding.loadMoreButton.visibility = if (hasMore) View.VISIBLE else View.GONE
                }

                alertAdapter.notifyDataSetChanged()

            } catch (e: Exception) {
                Toast.makeText(this@AlertHistoryActivity, "Failed to load: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                isLoading = false
                binding.loadingProgress.visibility = View.GONE
            }
        }
    }

    private fun openAlertDetails(alert: AlertHistory) {
        val intent = Intent(this, HelpRequestActivity::class.java).apply {
            putExtra("fullName", alert.user_name); putExtra("email", alert.user_email); putExtra("phoneNumber", alert.user_phone)
            putExtra("latitude", alert.latitude?.toString() ?: ""); putExtra("longitude", alert.longitude?.toString() ?: "")
            putExtra("timestamp", alert.created_at); putExtra("frontPhotoUrl", alert.front_photo_url ?: ""); putExtra("backPhotoUrl", alert.back_photo_url ?: "")
        }
        startActivity(intent)
    }

    private fun openContactDetails(request: ContactRequest) {
        val intent = Intent(this, ContactDetailActivity::class.java).apply {
            putExtra("request_id", request.id)
        }
        startActivity(intent)
    }
}

sealed class HistoryItem {
    abstract val timestamp: String?
    data class Alert(val alert: AlertHistory) : HistoryItem() { override val timestamp = alert.created_at }
    data class Contact(val request: ContactRequest) : HistoryItem() { override val timestamp = request.created_at }
}

class AlertAdapter(
    private val items: List<HistoryItem>,
    private val currentUserId: String,
    private val onClick: (HistoryItem) -> Unit
) : RecyclerView.Adapter<AlertAdapter.AlertViewHolder>() {

    class AlertViewHolder(val binding: com.sriox.vasateysec.databinding.ItemAlertBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): AlertViewHolder {
        return AlertViewHolder(com.sriox.vasateysec.databinding.ItemAlertBinding.inflate(android.view.LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: AlertViewHolder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context
        
        when (item) {
            is HistoryItem.Alert -> {
                val alert = item.alert
                val isSentByMe = alert.user_id == currentUserId
                holder.binding.alertUserName.text = if (isSentByMe) "Sent Alert" else alert.user_name
                holder.binding.alertEmail.text = alert.user_email
                holder.binding.alertPhone.text = alert.user_phone
                holder.binding.alertStatus.text = "EMERGENCY"
                holder.binding.alertType.text = if (isSentByMe) "↑" else "↓"
                holder.binding.alertType.setTextColor(context.getColor(R.color.pink))
                holder.binding.alertLocation.text = if (alert.latitude != null) "📍 Lat: %.4f, Long: %.4f".format(alert.latitude, alert.longitude) else "📍 Location unavailable"
                formatTime(alert.created_at, holder.binding.alertTime)
                
                if (!alert.front_photo_url.isNullOrEmpty() || !alert.back_photo_url.isNullOrEmpty()) {
                    holder.binding.photosContainer.visibility = View.VISIBLE
                    if (!alert.front_photo_url.isNullOrEmpty()) com.bumptech.glide.Glide.with(context).load(alert.front_photo_url).into(holder.binding.frontPhotoThumb)
                } else holder.binding.photosContainer.visibility = View.GONE
            }
            is HistoryItem.Contact -> {
                val req = item.request
                val isSentByMe = req.from_user_id == currentUserId
                holder.binding.alertUserName.text = if (isSentByMe) "Contact Sent to ${req.to_user_id.take(8)}..." else req.from_user_name
                holder.binding.alertEmail.text = "Contact Request"
                holder.binding.alertPhone.text = req.from_user_phone
                holder.binding.alertStatus.text = req.status.uppercase()
                holder.binding.alertType.text = "📞"
                holder.binding.alertType.setTextColor(context.getColor(R.color.golden))
                holder.binding.alertLocation.text = "Tap to view details"
                formatTime(req.created_at, holder.binding.alertTime)
                holder.binding.photosContainer.visibility = View.GONE
            }
        }
        holder.itemView.setOnClickListener { onClick(item) }
    }

    private fun formatTime(timestamp: String?, textView: android.widget.TextView) {
        if (timestamp == null) { textView.text = "Recently"; return }
        try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            val date = sdf.parse(timestamp)
            val diff = Date().time - (date?.time ?: 0)
            textView.text = when {
                diff < 60000 -> "Just now"
                diff < 3600000 -> "${diff / 60000}m ago"
                diff < 86400000 -> "${diff / 3600000}h ago"
                else -> "${diff / 86400000}d ago"
            }
        } catch (e: Exception) { textView.text = "Recently" }
    }

    override fun getItemCount() = items.size
}
