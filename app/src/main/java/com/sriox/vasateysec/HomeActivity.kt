package com.sriox.vasateysec

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.navigation.NavigationView
import com.sriox.vasateysec.databinding.ActivityHomeBinding
import com.sriox.vasateysec.models.Helpline
import com.sriox.vasateysec.models.SmsContact
import com.sriox.vasateysec.models.UserProfile
import com.sriox.vasateysec.services.BleGuardianService
import com.sriox.vasateysec.utils.BottomNavHelper
import com.sriox.vasateysec.utils.FCMTokenManager
import com.sriox.vasateysec.utils.SOSHelper
import com.sriox.vasateysec.utils.SessionManager
import com.sriox.vasateysec.utils.SmsHelper
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.launch

class HomeActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var toggle: ActionBarDrawerToggle
    private var allHelplines = listOf<Helpline>()
    private lateinit var helplineAdapter: HelplineAdapter
    private var smsContacts = listOf<SmsContact>()
    private var isSosActive = false

    private val watchStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra(BleGuardianService.EXTRA_STATUS)
            Log.d("HomeActivity", "Received Watch Status: $status")
            updateHardwareStatusUi(status)
        }
    }

    companion object {
        private const val RECORD_AUDIO_PERMISSION_CODE = 123
        private const val ALL_PERMISSIONS_CODE = 126
        private const val PREF_SOS_ACTIVE = "is_sos_active_persistent"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Restore SOS state from persistence
        val prefs = getSharedPreferences("vasatey_settings", MODE_PRIVATE)
        isSosActive = prefs.getBoolean(PREF_SOS_ACTIVE, false)

        setupQuickActions()
        setupBottomNavigation()
        setupHelplineSection()
        setupRealtimeContactListener()
        
        BottomNavHelper.highlightActiveItem(
            this,
            BottomNavHelper.NavItem.NONE
        )
        
        ensureSessionValid()
        loadUserProfile()
        loadSmsContactsForSpinner()
        FCMTokenManager.initializeFCM(this)
        restoreVoiceAlertState()
        requestAllPermissions()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(BleGuardianService.ACTION_WATCH_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(watchStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(watchStatusReceiver, filter)
        }
        
        LocalBroadcastManager.getInstance(this).registerReceiver(watchStatusReceiver, filter)
        checkInitialHardwareStatus()
    }

    override fun onStop() {
        super.onStop()
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(watchStatusReceiver)
            unregisterReceiver(watchStatusReceiver)
        } catch (e: Exception) {}
    }

    private fun loadSmsContactsForSpinner() {
        lifecycleScope.launch {
            try {
                val currentUser = SupabaseClient.client.auth.currentUserOrNull()
                if (currentUser != null) {
                    smsContacts = SupabaseClient.client.from("sms_contacts")
                        .select { filter { eq("user_id", currentUser.id) } }
                        .decodeList<SmsContact>()
                }
                
                if (smsContacts.isEmpty()) {
                    smsContacts = SmsHelper.getFromLocalStorage(this@HomeActivity)
                }

                if (smsContacts.isNotEmpty()) {
                    val contactNames = smsContacts.map { it.name }
                    val adapter = ArrayAdapter(this@HomeActivity, android.R.layout.simple_spinner_item, contactNames)
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    binding.spinnerCallRecipient.adapter = adapter
                    
                    val savedPhone = getSharedPreferences("alert_settings", MODE_PRIVATE).getString("auto_call_recipient", null)
                    val index = smsContacts.indexOfFirst { it.phone == savedPhone }
                    if (index >= 0) binding.spinnerCallRecipient.setSelection(index)
                }
            } catch (e: Exception) { }
        }
    }

    private fun checkInitialHardwareStatus() {
        val prefs = getSharedPreferences("vasatey_settings", MODE_PRIVATE)
        val isHardwareEnabled = prefs.getBoolean("hardware_sos_enabled", false)
        if (isHardwareEnabled) {
            binding.hardwareStatusLayout.visibility = View.VISIBLE
            // If it was SOS active before closing, show it immediately
            if (isSosActive) {
                updateHardwareStatusUi(BleGuardianService.STATUS_SOS_ACTIVE)
            } else {
                updateHardwareStatusUi(BleGuardianService.STATUS_DISCONNECTED)
            }
        } else {
            binding.hardwareStatusLayout.visibility = View.GONE
        }
    }

    private fun updateHardwareStatusUi(status: String?) {
        binding.hardwareStatusLayout.visibility = View.VISIBLE
        val prefs = getSharedPreferences("vasatey_settings", MODE_PRIVATE)
        
        // Handle persistent SOS state locking
        if (isSosActive) {
            // Only clear SOS state if we get "0" (STATUS_CONNECTED)
            if (status == BleGuardianService.STATUS_CONNECTED) {
                isSosActive = false
                prefs.edit().putBoolean(PREF_SOS_ACTIVE, false).apply()
            } else {
                // Ensure UI remains in SOS ACTIVE even if searching/syncing
                binding.hardwareStatusDot.backgroundTintList = ColorStateList.valueOf(Color.RED)
                binding.hardwareStatusText.text = "SOS ACTIVE"
                return
            }
        }

        when (status) {
            BleGuardianService.STATUS_CONNECTING -> {
                binding.hardwareStatusDot.backgroundTintList = ColorStateList.valueOf(Color.YELLOW)
                binding.hardwareStatusText.text = "Syncing..."
            }
            BleGuardianService.STATUS_CONNECTED -> {
                binding.hardwareStatusDot.backgroundTintList = ColorStateList.valueOf(Color.GREEN)
                binding.hardwareStatusText.text = "Watch Online"
            }
            BleGuardianService.STATUS_SOS_ACTIVE -> {
                isSosActive = true
                prefs.edit().putBoolean(PREF_SOS_ACTIVE, true).apply()
                binding.hardwareStatusDot.backgroundTintList = ColorStateList.valueOf(Color.RED)
                binding.hardwareStatusText.text = "SOS ACTIVE"
            }
            BleGuardianService.STATUS_GPS_RECEIVED -> {
                binding.hardwareStatusDot.backgroundTintList = ColorStateList.valueOf(Color.BLUE)
                binding.hardwareStatusText.text = "Location Fix"
                binding.hardwareStatusDot.postDelayed({
                    if (!isSosActive) updateHardwareStatusUi(BleGuardianService.STATUS_CONNECTED)
                }, 1500)
            }
            else -> {
                binding.hardwareStatusDot.backgroundTintList = ColorStateList.valueOf(Color.GRAY)
                binding.hardwareStatusText.text = "Searching..."
            }
        }
    }
    
    private fun setupRealtimeContactListener() {
        lifecycleScope.launch {
            try {
                val currentUser = SupabaseClient.client.auth.currentUserOrNull() ?: return@launch
                val channel = SupabaseClient.client.realtime.channel("contact-requests")
                
                val changeFlow = channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
                    table = "contact_requests"
                }

                channel.subscribe()

                changeFlow.collect { action ->
                    val toUserId = action.record["to_user_id"]?.toString()
                    if (toUserId == currentUser.id) {
                        val fromName = action.record["from_user_name"]?.toString() ?: "Someone"
                        val requestId = action.record["id"]?.toString() ?: ""
                        
                        runOnUiThread {
                            showInAppNotification(fromName, requestId)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("HomeActivity", "Realtime error: ${e.message}")
            }
        }
    }

    private fun showInAppNotification(name: String, requestId: String) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("📞 Contact Request")
            .setMessage("$name wants to contact you. Open details?")
            .setPositiveButton("View") { _, _ ->
                val intent = Intent(this, ContactDetailActivity::class.java).apply {
                    putExtra("request_id", requestId)
                }
                startActivity(intent)
            }
            .setNegativeButton("Dismiss", null)
            .show()
            
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(getColor(R.color.golden))
    }

    private fun setupHelplineSection() {
        binding.helplineHeader.setOnClickListener {
            if (binding.helplineExpandArea.visibility == View.GONE) {
                binding.helplineExpandArea.visibility = View.VISIBLE
                binding.helplineArrow.setImageResource(R.drawable.ic_arrow_up)
                loadHelplines()
            } else {
                binding.helplineExpandArea.visibility = View.GONE
                binding.helplineArrow.setImageResource(R.drawable.ic_arrow_down)
            }
        }

        helplineAdapter = HelplineAdapter(mutableListOf()) { helpline ->
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${helpline.number}"))
            startActivity(intent)
        }

        binding.rvHelplines.apply {
            layoutManager = LinearLayoutManager(this@HomeActivity)
            adapter = helplineAdapter
        }

        binding.etHelplineSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterHelplines(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun loadHelplines() {
        lifecycleScope.launch {
            try {
                allHelplines = SupabaseClient.client.from("helplines")
                    .select()
                    .decodeList<Helpline>()
                
                if (allHelplines.isNotEmpty()) {
                    binding.rvHelplines.visibility = View.VISIBLE
                    binding.tvHelplineNoResult.visibility = View.GONE
                    helplineAdapter.updateData(allHelplines)
                }
            } catch (e: Exception) {
                Log.e("HomeActivity", "Error loading helplines", e)
            }
        }
    }

    private fun filterHelplines(query: String) {
        val filteredList = allHelplines.filter { 
            it.name.contains(query, ignoreCase = true) || it.number.contains(query)
        }
        
        if (filteredList.isEmpty() && query.isNotEmpty()) {
            binding.rvHelplines.visibility = View.GONE
            binding.tvHelplineNoResult.visibility = View.VISIBLE
        } else {
            binding.rvHelplines.visibility = View.VISIBLE
            binding.tvHelplineNoResult.visibility = View.GONE
            helplineAdapter.updateData(filteredList)
        }
    }

    private fun ensureSessionValid() {
        if (!SessionManager.isLoggedIn()) {
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
            return
        }
    }

    private fun setupQuickActions() {
        binding.voiceAlertCard.setOnClickListener {
            binding.voiceAlertSwitch.isChecked = !binding.voiceAlertSwitch.isChecked
        }
        
        binding.voiceAlertSwitch.setOnCheckedChangeListener { _, isChecked ->
            saveVoiceAlertState(isChecked)
            if (isChecked) {
                requestAudioPermission()
            } else {
                stopVoiceService()
            }
        }
        
        val alertPrefs = getSharedPreferences("alert_settings", MODE_PRIVATE)
        binding.switchNetworkAlert.isChecked = alertPrefs.getBoolean("network_alert_enabled", true)
        binding.switchSmsAlert.isChecked = alertPrefs.getBoolean("sms_alert_enabled", false)
        binding.switchAutoCall.isChecked = alertPrefs.getBoolean("auto_call_enabled", false)

        if (binding.switchAutoCall.isChecked) binding.callRecipientLayout.visibility = View.VISIBLE

        binding.switchNetworkAlert.setOnCheckedChangeListener { _, isChecked ->
            alertPrefs.edit().putBoolean("network_alert_enabled", isChecked).apply()
        }

        binding.switchSmsAlert.setOnCheckedChangeListener { _, isChecked ->
            alertPrefs.edit().putBoolean("sms_alert_enabled", isChecked).apply()
            if (isChecked) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.SEND_SMS), 101)
                }
            }
        }

        binding.switchAutoCall.setOnCheckedChangeListener { _, isChecked ->
            alertPrefs.edit().putBoolean("auto_call_enabled", isChecked).apply()
            binding.callRecipientLayout.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CALL_PHONE), 104)
                }
            }
        }

        binding.spinnerCallRecipient.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedPhone = smsContacts[position].phone
                alertPrefs.edit().putString("auto_call_recipient", selectedPhone).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.cardAISafetyHome.setOnClickListener {
            startActivity(Intent(this, AiChatActivity::class.java))
        }

        binding.safePlacesCard.setOnClickListener {
            startActivity(Intent(this, SafePlacesSelectionActivity::class.java))
        }

        binding.guardiansCard.setOnClickListener {
            startActivity(Intent(this, AddGuardianActivity::class.java))
        }
        
        binding.historyCard.setOnClickListener {
            startActivity(Intent(this, AlertHistoryActivity::class.java))
        }
        
        binding.myAlertsCard.setOnClickListener {
            startActivity(Intent(this, MyAlertsActivity::class.java))
        }
        
        binding.settingsCard.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        
        binding.logoutButton.setOnClickListener {
            showLogoutDialog()
        }
    }
    
    private fun showLogoutDialog() {
        AlertDialog.Builder(this)
            .setTitle("Logout")
            .setMessage("Are you sure you want to logout?")
            .setPositiveButton("Yes") { _, _ ->
                logout()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun setupBottomNavigation() {
        val navGuardians = findViewById<LinearLayout>(R.id.navGuardians)
        val navHistory = findViewById<LinearLayout>(R.id.navHistory)
        val sosButton = findViewById<MaterialCardView>(R.id.sosButton)
        val navGhistory = findViewById<LinearLayout>(R.id.navGhistory)
        val navProfile = findViewById<LinearLayout>(R.id.navProfile)
        
        navGuardians?.setOnClickListener {
            val intent = Intent(this, AddGuardianActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
        }
        
        navHistory?.setOnClickListener {
            val intent = Intent(this, AlertHistoryActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
        }
        
        sosButton?.setOnClickListener {
            SOSHelper.showSOSConfirmation(this)
        }
        
        navGhistory?.setOnClickListener {
            val intent = Intent(this, GuardianMapActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
        }
        
        navProfile?.setOnClickListener {
            val intent = Intent(this, EditProfileActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
        }
    }
    
    private fun saveVoiceAlertState(isEnabled: Boolean) {
        getSharedPreferences("vasatey_prefs", MODE_PRIVATE).edit()
            .putBoolean("voice_alert_enabled", isEnabled)
            .apply()
    }
    
    private fun restoreVoiceAlertState() {
        val isServiceRunning = isServiceRunning(VoskWakeWordService::class.java)
        val savedState = getSharedPreferences("vasatey_prefs", MODE_PRIVATE)
            .getBoolean("voice_alert_enabled", false)
        val actualState = isServiceRunning && savedState
        
        binding.voiceAlertSwitch.setOnCheckedChangeListener(null)
        binding.voiceAlertSwitch.isChecked = actualState
        
        binding.voiceAlertSwitch.setOnCheckedChangeListener { _, checked ->
            saveVoiceAlertState(checked)
            if (checked) {
                requestAudioPermission()
            } else {
                stopVoiceService()
            }
        }
    }
    
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }
    
    private fun stopVoiceService() {
        val serviceIntent = Intent(this, VoskWakeWordService::class.java)
        stopService(serviceIntent)
        Toast.makeText(this, "Voice alert stopped", Toast.LENGTH_SHORT).show()
    }

    private fun loadUserProfile() {
        lifecycleScope.launch {
            try {
                val currentUser = SupabaseClient.client.auth.currentUserOrNull()
                if (currentUser != null) {
                    val userProfile = try {
                        SupabaseClient.client.from("users")
                            .select { filter { eq("id", currentUser.id) } }
                            .decodeSingle<UserProfile>()
                    } catch (e: Exception) {
                        UserProfile(id = currentUser.id, name = currentUser.email?.substringBefore("@") ?: "User", email = currentUser.email ?: "")
                    }

                    val userName = userProfile.name ?: "User"
                    val wakeWord = userProfile.wake_word ?: "help me"
                    
                    runOnUiThread {
                        binding.voiceStatusText.text = "Say '$wakeWord' to alert"
                    }
                    
                    getSharedPreferences("vasatey_prefs", MODE_PRIVATE).edit()
                        .putString("wake_word", wakeWord.lowercase())
                        .apply()
                }
            } catch (e: Exception) {
                Toast.makeText(this@HomeActivity, "Failed to load profile", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_PERMISSION_CODE)
        } else {
            startListeningService()
        }
    }

    private fun startListeningService() {
        val serviceIntent = Intent(this, VoskWakeWordService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Toast.makeText(this, "Voice listening service started", Toast.LENGTH_SHORT).show()
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        return true
    }

    private fun logout() {
        lifecycleScope.launch {
            try {
                FCMTokenManager.deactivateFCMToken(this@HomeActivity)
                SupabaseClient.client.auth.signOut()
                SessionManager.clearSession()
                val intent = Intent(this@HomeActivity, LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            } catch (e: Exception) {
                Toast.makeText(this@HomeActivity, "Logout failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestAllPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) permissionsToRequest.add(Manifest.permission.CAMERA)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), ALL_PERMISSIONS_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_AUDIO_PERMISSION_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startListeningService()
        }
    }

    override fun onBackPressed() {
        super.onBackPressed()
    }

    class HelplineAdapter(private var list: MutableList<Helpline>, private val onCallClick: (Helpline) -> Unit) :
        RecyclerView.Adapter<HelplineAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.tvHelplineName)
            val number: TextView = view.findViewById(R.id.tvHelplineNumber)
            val callBtn: ImageView = view.findViewById(R.id.btnCallHelpline)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_helpline, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            holder.name.text = item.name
            holder.number.text = item.number
            holder.callBtn.setOnClickListener { onCallClick(item) }
        }

        override fun getItemCount() = list.size

        fun updateData(newList: List<Helpline>) {
            list.clear()
            list.addAll(newList)
            notifyDataSetChanged()
        }
    }
}
