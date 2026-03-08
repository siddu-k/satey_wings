package com.sriox.vasateysec

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.navigation.NavigationView
import com.sriox.vasateysec.databinding.ActivityHomeBinding
import com.sriox.vasateysec.models.Helpline
import com.sriox.vasateysec.models.UserProfile
import com.sriox.vasateysec.utils.FCMTokenManager
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

    companion object {
        private const val RECORD_AUDIO_PERMISSION_CODE = 123
        private const val LOCATION_PERMISSION_CODE = 124
        private const val NOTIFICATION_PERMISSION_CODE = 125
        private const val ALL_PERMISSIONS_CODE = 126
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupQuickActions()
        setupBottomNavigation()
        setupHelplineSection()
        setupRealtimeContactListener()
        
        com.sriox.vasateysec.utils.BottomNavHelper.highlightActiveItem(
            this,
            com.sriox.vasateysec.utils.BottomNavHelper.NavItem.NONE
        )
        
        ensureSessionValid()
        loadUserProfile()
        FCMTokenManager.initializeFCM(this)
        restoreVoiceAlertState()
        requestAllPermissions()
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
                android.util.Log.e("HomeActivity", "Realtime error: ${e.message}")
            }
        }
    }

    private fun showInAppNotification(name: String, requestId: String) {
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
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
            
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setTextColor(getColor(R.color.golden))
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
                android.util.Log.e("HomeActivity", "Error loading helplines", e)
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
        if (!com.sriox.vasateysec.utils.SessionManager.isLoggedIn()) {
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
            return
        }
        
        lifecycleScope.launch {
            try {
                val currentUser = SupabaseClient.client.auth.currentUserOrNull()
                if (currentUser != null) {
                    android.util.Log.d("HomeActivity", "Supabase session active for: ${currentUser.id}")
                }
            } catch (e: Exception) {
                android.util.Log.w("HomeActivity", "Session check failed: ${e.message}")
            }
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
        
        // Link AI assistant button
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
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Logout")
            .setMessage("Are you sure you want to logout?")
            .setPositiveButton("Yes") { _, _ ->
                logout()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        }
        
        navHistory?.setOnClickListener {
            val intent = Intent(this, AlertHistoryActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
        }
        
        sosButton?.setOnClickListener {
            com.sriox.vasateysec.utils.SOSHelper.showSOSConfirmation(this)
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
                    val wakeWord = (userProfile as? Map<*, *>)?.get("wake_word") as? String ?: "help me"
                    
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
                com.sriox.vasateysec.utils.SessionManager.clearSession()
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

    // Helpline RecyclerView Adapter
    class HelplineAdapter(private var list: MutableList<Helpline>, private val onCallClick: (Helpline) -> Unit) :
        RecyclerView.Adapter<HelplineAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.tvHelplineName)
            val number: TextView = view.findViewById(R.id.tvHelplineNumber)
            val callBtn: android.widget.ImageView = view.findViewById(R.id.btnCallHelpline)
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
