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
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
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
    
    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            updateTimerDisplay()
            timerHandler.postDelayed(this, 1000)
        }
    }

    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            val content = result.contents
            if (content.startsWith("vasatey_sos:")) {
                val userId = content.substringAfter("vasatey_sos:")
                showEmergencyDetails(userId)
            } else {
                Toast.makeText(this, "Not a valid Vasatey QR Code", Toast.LENGTH_SHORT).show()
            }
        }
    }

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
        private const val OVERLAY_PERMISSION_REQ_CODE = 127
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences("vasatey_settings", MODE_PRIVATE)
        isSosActive = prefs.getBoolean(PREF_SOS_ACTIVE, false)

        setupQuickActions()
        setupBottomNavigation()
        setupHelplineSection()
        setupRealtimeContactListener()
        setupQrListeners()
        
        BottomNavHelper.highlightActiveItem(this, BottomNavHelper.NavItem.NONE)
        
        ensureSessionValid()
        loadUserProfile()
        loadSmsContactsForSpinner()
        FCMTokenManager.initializeFCM(this)
        restoreVoiceAlertState()
        requestAllPermissions()
        checkOverlayPermission()
        
        if (isSosActive) timerHandler.post(timerRunnable)
    }

    private fun setupQrListeners() {
        binding.btnQrScanner.setOnClickListener {
            val options = ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt("Scan Emergency QR")
                setCameraId(0)
                setBeepEnabled(true)
                setBarcodeImageEnabled(true)
                setOrientationLocked(false)
            }
            qrScanLauncher.launch(options)
        }

        binding.emergencyQrCard.setOnClickListener {
            startActivity(Intent(this, EmergencyProfileActivity::class.java))
        }
    }

    private fun showEmergencyDetails(userId: String) {
        // Here we could open a new activity to show the details from Supabase
        // For now, let's just toast
        Toast.makeText(this, "Loading Emergency Profile...", Toast.LENGTH_SHORT).show()
        val intent = Intent(this, EmergencyAlertViewerActivity::class.java).apply {
            putExtra("targetUserId", userId)
            putExtra("isQrMode", true)
        }
        startActivity(intent)
    }

    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                AlertDialog.Builder(this)
                    .setTitle("Emergency Feature Required")
                    .setMessage("To allow the app to automatically call guardians when closed or locked, please enable 'Appear on top' permission.")
                    .setPositiveButton("Settings") { _, _ ->
                        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                        startActivityForResult(intent, OVERLAY_PERMISSION_REQ_CODE)
                    }
                    .setNegativeButton("Later", null)
                    .show()
            }
        }
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
                if (smsContacts.isEmpty()) smsContacts = SmsHelper.getFromLocalStorage(this@HomeActivity)
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
        if (prefs.getBoolean("hardware_sos_enabled", false)) {
            binding.hardwareStatusLayout.visibility = View.VISIBLE
            updateHardwareStatusUi(if (isSosActive) BleGuardianService.STATUS_SOS_ACTIVE else BleGuardianService.STATUS_DISCONNECTED)
        } else {
            binding.hardwareStatusLayout.visibility = View.GONE
        }
    }

    private fun updateHardwareStatusUi(status: String?) {
        binding.hardwareStatusLayout.visibility = View.VISIBLE
        val prefs = getSharedPreferences("vasatey_settings", MODE_PRIVATE)
        if (isSosActive && status == BleGuardianService.STATUS_CONNECTED) {
            isSosActive = false
            prefs.edit().putBoolean(PREF_SOS_ACTIVE, false).apply()
            timerHandler.removeCallbacks(timerRunnable)
            binding.tvSmsTimer.visibility = View.GONE
        }
        when (status) {
            BleGuardianService.STATUS_CONNECTING -> {
                binding.hardwareStatusDot.backgroundTintList = ColorStateList.valueOf(if (isSosActive) Color.RED else Color.YELLOW)
                binding.hardwareStatusText.text = if (isSosActive) "SOS ACTIVE (Syncing...)" else "Syncing..."
            }
            BleGuardianService.STATUS_CONNECTED -> {
                if (!isSosActive) {
                    binding.hardwareStatusDot.backgroundTintList = ColorStateList.valueOf(Color.GREEN)
                    binding.hardwareStatusText.text = "Watch Online"
                }
            }
            BleGuardianService.STATUS_SOS_ACTIVE -> {
                isSosActive = true
                prefs.edit().putBoolean(PREF_SOS_ACTIVE, true).apply()
                binding.hardwareStatusDot.backgroundTintList = ColorStateList.valueOf(Color.RED)
                binding.hardwareStatusText.text = "SOS ACTIVE"
                timerHandler.post(timerRunnable)
            }
            BleGuardianService.STATUS_GPS_RECEIVED -> {
                if (!isSosActive) {
                    binding.hardwareStatusDot.backgroundTintList = ColorStateList.valueOf(Color.BLUE)
                    binding.hardwareStatusText.text = "Location Fix"
                    binding.hardwareStatusDot.postDelayed({ if (!isSosActive) updateHardwareStatusUi(BleGuardianService.STATUS_CONNECTED) }, 1500)
                }
            }
            BleGuardianService.STATUS_DISCONNECTED -> {
                binding.hardwareStatusDot.backgroundTintList = ColorStateList.valueOf(if (isSosActive) Color.RED else Color.GRAY)
                binding.hardwareStatusText.text = if (isSosActive) "SOS ACTIVE (Offline)" else "Watch Offline"
            }
        }
    }
    
    private fun updateTimerDisplay() {
        val remainingMs = SmsHelper.getRemainingCooldownMs(this)
        if (remainingMs > 0 && isSosActive) {
            binding.tvSmsTimer.visibility = View.VISIBLE
            val secondsTotal = remainingMs / 1000
            binding.tvSmsTimer.text = String.format("Next alert in: %02d:%02d", secondsTotal / 60, secondsTotal % 60)
        } else if (isSosActive) {
            binding.tvSmsTimer.visibility = View.VISIBLE
            binding.tvSmsTimer.text = "Triggering next alert..."
        } else {
            binding.tvSmsTimer.visibility = View.GONE
        }
    }

    private fun setupRealtimeContactListener() {
        lifecycleScope.launch {
            try {
                val currentUser = SupabaseClient.client.auth.currentUserOrNull() ?: return@launch
                val channel = SupabaseClient.client.realtime.channel("contact-requests")
                val changeFlow = channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") { table = "contact_requests" }
                channel.subscribe()
                changeFlow.collect { action ->
                    if (action.record["to_user_id"]?.toString() == currentUser.id) {
                        runOnUiThread { showInAppNotification(action.record["from_user_name"]?.toString() ?: "Someone", action.record["id"]?.toString() ?: "") }
                    }
                }
            } catch (e: Exception) { }
        }
    }

    private fun showInAppNotification(name: String, requestId: String) {
        AlertDialog.Builder(this)
            .setTitle("📞 Contact Request")
            .setMessage("$name wants to contact you. Open details?")
            .setPositiveButton("View") { _, _ -> startActivity(Intent(this, ContactDetailActivity::class.java).apply { putExtra("request_id", requestId) }) }
            .setNegativeButton("Dismiss", null)
            .show().getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(getColor(R.color.golden))
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
        helplineAdapter = HelplineAdapter(mutableListOf()) { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${it.number}"))) }
        binding.rvHelplines.apply {
            layoutManager = LinearLayoutManager(this@HomeActivity)
            adapter = helplineAdapter
        }
        binding.etHelplineSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { filterHelplines(s.toString()) }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun loadHelplines() {
        lifecycleScope.launch {
            try {
                allHelplines = SupabaseClient.client.from("helplines").select().decodeList<Helpline>()
                if (allHelplines.isNotEmpty()) {
                    binding.rvHelplines.visibility = View.VISIBLE
                    binding.tvHelplineNoResult.visibility = View.GONE
                    helplineAdapter.updateData(allHelplines)
                }
            } catch (e: Exception) { }
        }
    }

    private fun filterHelplines(query: String) {
        val filteredList = allHelplines.filter { it.name.contains(query, ignoreCase = true) || it.number.contains(query) }
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
            startActivity(Intent(this, LoginActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK })
            finish()
        }
    }

    private fun setupQuickActions() {
        binding.voiceAlertCard.setOnClickListener { binding.voiceAlertSwitch.isChecked = !binding.voiceAlertSwitch.isChecked }
        binding.voiceAlertSwitch.setOnCheckedChangeListener { _, isChecked ->
            saveVoiceAlertState(isChecked)
            if (isChecked) requestAudioPermission() else stopVoiceService()
        }
        val alertPrefs = getSharedPreferences("alert_settings", MODE_PRIVATE)
        binding.switchNetworkAlert.isChecked = alertPrefs.getBoolean("network_alert_enabled", true)
        binding.switchSmsAlert.isChecked = alertPrefs.getBoolean("sms_alert_enabled", false)
        binding.switchAutoCall.isChecked = alertPrefs.getBoolean("auto_call_enabled", false)
        if (binding.switchAutoCall.isChecked) binding.callRecipientLayout.visibility = View.VISIBLE
        binding.switchNetworkAlert.setOnCheckedChangeListener { _, isChecked -> alertPrefs.edit().putBoolean("network_alert_enabled", isChecked).apply() }
        binding.switchSmsAlert.setOnCheckedChangeListener { _, isChecked ->
            alertPrefs.edit().putBoolean("sms_alert_enabled", isChecked).apply()
            if (isChecked && ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.SEND_SMS), 101)
        }
        binding.switchAutoCall.setOnCheckedChangeListener { _, isChecked ->
            alertPrefs.edit().putBoolean("auto_call_enabled", isChecked).apply()
            binding.callRecipientLayout.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked && ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CALL_PHONE), 104)
        }
        binding.spinnerCallRecipient.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { alertPrefs.edit().putString("auto_call_recipient", smsContacts[position].phone).apply() }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        binding.cardAISafetyHome.setOnClickListener { startActivity(Intent(this, AiChatActivity::class.java)) }
        binding.safePlacesCard.setOnClickListener { startActivity(Intent(this, SafePlacesSelectionActivity::class.java)) }
        binding.guardiansCard.setOnClickListener { startActivity(Intent(this, AddGuardianActivity::class.java)) }
        binding.historyCard.setOnClickListener { startActivity(Intent(this, AlertHistoryActivity::class.java)) }
        binding.myAlertsCard.setOnClickListener { startActivity(Intent(this, MyAlertsActivity::class.java)) }
        binding.settingsCard.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.logoutButton.setOnClickListener { showLogoutDialog() }
    }
    
    private fun showLogoutDialog() {
        AlertDialog.Builder(this).setTitle("Logout").setMessage("Are you sure you want to logout?").setPositiveButton("Yes") { _, _ -> logout() }.setNegativeButton("Cancel", null).show()
    }
    
    private fun setupBottomNavigation() {
        findViewById<LinearLayout>(R.id.navGuardians)?.setOnClickListener { startActivity(Intent(this, AddGuardianActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP }) }
        findViewById<LinearLayout>(R.id.navHistory)?.setOnClickListener { startActivity(Intent(this, AlertHistoryActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP }) }
        findViewById<MaterialCardView>(R.id.sosButton)?.setOnClickListener { SOSHelper.showSOSConfirmation(this) }
        findViewById<LinearLayout>(R.id.navGhistory)?.setOnClickListener { startActivity(Intent(this, GuardianMapActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP }) }
        findViewById<LinearLayout>(R.id.navProfile)?.setOnClickListener { startActivity(Intent(this, EditProfileActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP }) }
    }
    
    private fun saveVoiceAlertState(isEnabled: Boolean) { getSharedPreferences("vasatey_prefs", MODE_PRIVATE).edit().putBoolean("voice_alert_enabled", isEnabled).apply() }
    
    private fun restoreVoiceAlertState() {
        val isServiceRunning = isServiceRunning(VoskWakeWordService::class.java)
        val savedState = getSharedPreferences("vasatey_prefs", MODE_PRIVATE).getBoolean("voice_alert_enabled", false)
        binding.voiceAlertSwitch.setOnCheckedChangeListener(null)
        binding.voiceAlertSwitch.isChecked = isServiceRunning && savedState
        binding.voiceAlertSwitch.setOnCheckedChangeListener { _, checked ->
            saveVoiceAlertState(checked)
            if (checked) requestAudioPermission() else stopVoiceService()
        }
    }
    
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        return manager.getRunningServices(Int.MAX_VALUE).any { serviceClass.name == it.service.className }
    }
    
    private fun stopVoiceService() {
        stopService(Intent(this, VoskWakeWordService::class.java))
        Toast.makeText(this, "Voice alert stopped", Toast.LENGTH_SHORT).show()
    }

    private fun loadUserProfile() {
        lifecycleScope.launch {
            try {
                val currentUser = SupabaseClient.client.auth.currentUserOrNull() ?: return@launch
                val userProfile = try { SupabaseClient.client.from("users").select { filter { eq("id", currentUser.id) } }.decodeSingle<UserProfile>() } catch (e: Exception) { UserProfile(id = currentUser.id, name = currentUser.email?.substringBefore("@") ?: "User", email = currentUser.email ?: "") }
                runOnUiThread { binding.voiceStatusText.text = "Say '${userProfile.wake_word ?: "help me"}' to alert" }
                getSharedPreferences("vasatey_prefs", MODE_PRIVATE).edit().putString("wake_word", (userProfile.wake_word ?: "help me").lowercase()).apply()
            } catch (e: Exception) { }
        }
    }

    private fun requestAudioPermission() { if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_PERMISSION_CODE) else startListeningService() }

    private fun startListeningService() {
        val intent = Intent(this, VoskWakeWordService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        Toast.makeText(this, "Voice listening service started", Toast.LENGTH_SHORT).show()
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean = true

    private fun logout() {
        lifecycleScope.launch {
            try {
                FCMTokenManager.deactivateFCMToken(this@HomeActivity)
                SupabaseClient.client.auth.signOut()
                SessionManager.clearSession()
                startActivity(Intent(this@HomeActivity, LoginActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK })
                finish()
            } catch (e: Exception) { }
        }
    }

    private fun requestAllPermissions() {
        val perms = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.RECORD_AUDIO)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.CAMERA)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        if (perms.isNotEmpty()) ActivityCompat.requestPermissions(this, perms.toTypedArray(), ALL_PERMISSIONS_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_AUDIO_PERMISSION_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) startListeningService()
    }

    override fun onDestroy() {
        super.onDestroy()
        timerHandler.removeCallbacks(timerRunnable)
    }

    class HelplineAdapter(private var list: MutableList<Helpline>, private val onCallClick: (Helpline) -> Unit) : RecyclerView.Adapter<HelplineAdapter.ViewHolder>() {
        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.tvHelplineName)
            val number: TextView = view.findViewById(R.id.tvHelplineNumber)
            val callBtn: ImageView = view.findViewById(R.id.btnCallHelpline)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_helpline, parent, false))
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            holder.name.text = item.name
            holder.number.text = item.number
            holder.callBtn.setOnClickListener { onCallClick(item) }
        }
        override fun getItemCount() = list.size
        fun updateData(newList: List<Helpline>) { list.clear(); list.addAll(newList); notifyDataSetChanged() }
    }
}
