package com.sriox.vasateysec

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sriox.vasateysec.databinding.ActivitySettingsBinding
import com.sriox.vasateysec.VoskWakeWordService
import com.sriox.vasateysec.SupabaseClient
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: android.content.SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("vasatey_settings", MODE_PRIVATE)

        setupToolbar()
        loadSettings()
        setupSwitches()
        setupWakeWordCard()
        setupApiKeyHandling()
        setupBottomNavigation()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = "Settings"
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun loadSettings() {
        binding.switchPhotoCapture.isChecked = prefs.getBoolean("photo_capture_enabled", true)
        binding.switchLocationTracking.isChecked = prefs.getBoolean("location_tracking_enabled", true)
        binding.switchVoiceAlert.isChecked = prefs.getBoolean("voice_alert_enabled", false)
        binding.switchAutoCall.isChecked = prefs.getBoolean("auto_call_enabled", false)
        binding.switchVibration.isChecked = prefs.getBoolean("vibration_enabled", true)
        binding.switchSound.isChecked = prefs.getBoolean("sound_enabled", true)
        
        // Load API Key from DB (fallback to prefs)
        lifecycleScope.launch {
            try {
                val currentUser = SupabaseClient.client.auth.currentUserOrNull()
                if (currentUser != null) {
                    val userProfile = SupabaseClient.client.from("users")
                        .select { filter { eq("id", currentUser.id) } }
                        .decodeSingle<com.sriox.vasateysec.models.UserProfile>()
                    
                    binding.etGeminiApiKey.setText(userProfile.gemini_api_key ?: prefs.getString("gemini_api_key", ""))
                }
            } catch (e: Exception) {
                binding.etGeminiApiKey.setText(prefs.getString("gemini_api_key", ""))
            }
        }
    }

    private fun setupSwitches() {
        binding.switchPhotoCapture.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("photo_capture_enabled", isChecked).apply()
        }
        binding.switchLocationTracking.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("location_tracking_enabled", isChecked).apply()
        }
        binding.switchVoiceAlert.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("voice_alert_enabled", isChecked).apply()
            getSharedPreferences("vasatey_prefs", MODE_PRIVATE).edit()
                .putBoolean("voice_alert_enabled", isChecked)
                .apply()
        }
        binding.switchAutoCall.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_call_enabled", isChecked).apply()
        }
        binding.switchVibration.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("vibration_enabled", isChecked).apply()
        }
        binding.switchSound.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("sound_enabled", isChecked).apply()
        }
    }
    
    private fun setupApiKeyHandling() {
        binding.btnSaveApiKey.setOnClickListener {
            val key = binding.etGeminiApiKey.text.toString().trim()
            
            // 1. Save locally
            prefs.edit().putString("gemini_api_key", key).apply()
            
            // 2. Save to DB
            lifecycleScope.launch {
                try {
                    val currentUser = SupabaseClient.client.auth.currentUserOrNull()
                    if (currentUser != null) {
                        SupabaseClient.client.from("users").update({
                            set("gemini_api_key", key)
                        }) {
                            filter { eq("id", currentUser.id) }
                        }
                        showToast("API Key synced to cloud")
                    }
                } catch (e: Exception) {
                    showToast("API Key updated locally (Cloud sync failed)")
                }
            }
        }
    }
    
    private fun setupWakeWordCard() {
        lifecycleScope.launch {
            try {
                val currentUser = SupabaseClient.client.auth.currentUserOrNull()
                if (currentUser != null) {
                    val userProfile = SupabaseClient.client.from("users")
                        .select { filter { eq("id", currentUser.id) } }
                        .decodeSingle<com.sriox.vasateysec.models.UserProfile>()
                    
                    binding.currentWakeWord.text = "Current: ${userProfile.wake_word ?: "help me"}"
                }
            } catch (e: Exception) { }
        }
        
        binding.wakeWordCard.setOnClickListener {
            showWakeWordDialog()
        }
    }
    
    private fun showWakeWordDialog() {
        val input = android.widget.EditText(this)
        input.hint = "Enter new wake word"
        android.app.AlertDialog.Builder(this)
            .setTitle("Change Wake Word")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newWakeWord = input.text.toString().trim()
                if (newWakeWord.isNotEmpty()) saveWakeWord(newWakeWord)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun saveWakeWord(wakeWord: String) {
        lifecycleScope.launch {
            try {
                val currentUser = SupabaseClient.client.auth.currentUserOrNull()
                if (currentUser != null) {
                    SupabaseClient.client.from("users").update({ set("wake_word", wakeWord) }) { filter { eq("id", currentUser.id) } }
                    binding.currentWakeWord.text = "Current: $wakeWord"
                    showToast("Wake word updated")
                    val serviceIntent = Intent(this@SettingsActivity, VoskWakeWordService::class.java)
                    stopService(serviceIntent)
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ startService(serviceIntent) }, 500)
                }
            } catch (e: Exception) { }
        }
    }

    private fun setupBottomNavigation() {
        val navGuardians = findViewById<android.widget.LinearLayout>(R.id.navGuardians)
        val navHistory = findViewById<android.widget.LinearLayout>(R.id.navHistory)
        val sosButton = findViewById<com.google.android.material.card.MaterialCardView>(R.id.sosButton)
        val navGhistory = findViewById<android.widget.LinearLayout>(R.id.navGhistory)
        val navProfile = findViewById<android.widget.LinearLayout>(R.id.navProfile)

        navGuardians?.setOnClickListener { startActivity(Intent(this, AddGuardianActivity::class.java)); finish() }
        navHistory?.setOnClickListener { startActivity(Intent(this, AlertHistoryActivity::class.java)); finish() }
        sosButton?.setOnClickListener { com.sriox.vasateysec.utils.SOSHelper.showSOSConfirmation(this) }
        navGhistory?.setOnClickListener { startActivity(Intent(this, GuardianMapActivity::class.java)); finish() }
        navProfile?.setOnClickListener { startActivity(Intent(this, EditProfileActivity::class.java)); finish() }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}
