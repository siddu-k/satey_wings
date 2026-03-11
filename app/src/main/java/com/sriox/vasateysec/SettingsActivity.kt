package com.sriox.vasateysec

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.CompoundButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.sriox.vasateysec.databinding.ActivitySettingsBinding
import com.sriox.vasateysec.services.BleGuardianService
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: android.content.SharedPreferences
    private var bluetoothAdapter: BluetoothAdapter? = null

    companion object {
        private const val REQUEST_ENABLE_BT = 103
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        prefs = getSharedPreferences("vasatey_settings", MODE_PRIVATE)

        setupHeader()
        loadSettings()
        setupSwitches()
        setupIntervalSaving()
        setupWakeWordCard()
        setupOpenRouterHandling()
        setupHardwareScan()
        setupBottomNavigation()
    }

    private fun setupHeader() {
        binding.backButton.setOnClickListener {
            finish()
        }
    }

    private fun loadSettings() {
        // Core features from local prefs
        binding.switchPhotoCapture.isChecked = prefs.getBoolean("photo_capture_enabled", true)
        binding.switchLocationTracking.isChecked = prefs.getBoolean("location_tracking_enabled", true)
        binding.switchVoiceAlert.isChecked = prefs.getBoolean("voice_alert_enabled", false)
        binding.switchAutoCall.isChecked = prefs.getBoolean("auto_call_enabled", false)
        binding.switchVibration.isChecked = prefs.getBoolean("vibration_enabled", true)
        binding.switchSound.isChecked = prefs.getBoolean("sound_enabled", true)
        binding.switchHardwareSos.isChecked = prefs.getBoolean("hardware_sos_enabled", false)
        binding.switchHardwareGps.isChecked = prefs.getBoolean("use_hardware_gps", false)
        binding.switchHardwareAutoCall.isChecked = prefs.getBoolean("hardware_auto_call_enabled", false)
        
        binding.switchDoubleWord.isChecked = prefs.getBoolean("double_word_enabled", true)

        // Load interval
        val interval = prefs.getInt("hardware_sms_interval", 2)
        binding.etSmsInterval.setText(interval.toString())

        // Set defaults from local storage first
        binding.etOpenRouterApiKey.setText(prefs.getString("gemini_api_key", ""))
        binding.etOpenRouterModelName.setText(prefs.getString("gemini_model_name", "arcee-ai/trinity-large-preview:free"))

        // --- FETCH SYNCED DATA FROM CLOUD ---
        lifecycleScope.launch {
            try {
                val currentUser = SupabaseClient.client.auth.currentUserOrNull()
                if (currentUser != null) {
                    val userProfile = SupabaseClient.client.from("users")
                        .select { filter { eq("id", currentUser.id) } }
                        .decodeSingle<com.sriox.vasateysec.models.UserProfile>()
                    
                    // Update Wake Word Display
                    binding.currentWakeWord.text = "Current: ${userProfile.wake_word ?: "help me"}"
                    
                    // Sync API Key and Model from Cloud to Local and UI
                    if (!userProfile.gemini_api_key.isNullOrBlank()) {
                        binding.etOpenRouterApiKey.setText(userProfile.gemini_api_key)
                        prefs.edit().putString("gemini_api_key", userProfile.gemini_api_key).apply()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("Settings", "Failed to sync cloud settings: ${e.message}")
            }
        }
    }

    private fun setupSwitches() {
        binding.switchPhotoCapture.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            prefs.edit().putBoolean("photo_capture_enabled", isChecked).apply()
        }
        binding.switchLocationTracking.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            prefs.edit().putBoolean("location_tracking_enabled", isChecked).apply()
        }
        binding.switchVoiceAlert.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            prefs.edit().putBoolean("voice_alert_enabled", isChecked).apply()
            getSharedPreferences("vasatey_prefs", MODE_PRIVATE).edit()
                .putBoolean("voice_alert_enabled", isChecked)
                .apply()
        }
        binding.switchAutoCall.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            prefs.edit().putBoolean("auto_call_enabled", isChecked).apply()
        }
        binding.switchVibration.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            prefs.edit().putBoolean("vibration_enabled", isChecked).apply()
        }
        binding.switchSound.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            prefs.edit().putBoolean("sound_enabled", isChecked).apply()
        }
        binding.switchHardwareSos.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            if (isChecked) {
                checkBluetoothPermissionsAndState()
            } else {
                stopHardwareService()
            }
        }
        binding.switchHardwareGps.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            prefs.edit().putBoolean("use_hardware_gps", isChecked).apply()
            showToast(if (isChecked) "Hardware GPS prioritized" else "Mobile GPS prioritized")
        }
        binding.switchHardwareAutoCall.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            prefs.edit().putBoolean("hardware_auto_call_enabled", isChecked).apply()
            showToast(if (isChecked) "Hardware Auto-Call enabled" else "Hardware Auto-Call disabled")
        }
        binding.switchDoubleWord.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            prefs.edit().putBoolean("double_word_enabled", isChecked).apply()
            showToast(if (isChecked) "Double trigger active" else "Single trigger active")
        }
    }

    private fun setupIntervalSaving() {
        binding.btnSaveInterval.setOnClickListener {
            val intervalStr = binding.etSmsInterval.text.toString()
            if (intervalStr.isNotEmpty()) {
                val interval = intervalStr.toIntOrNull() ?: 2
                if (interval >= 1) {
                    prefs.edit().putInt("hardware_sms_interval", interval).apply()
                    showToast("Alert interval set to $interval minutes")
                } else {
                    showToast("Interval must be at least 1 minute")
                }
            }
        }
    }

    private fun setupHardwareScan() {
        binding.btnScanHardware.setOnClickListener {
            startActivity(Intent(this, BleDeviceScanActivity::class.java))
        }
    }

    private fun checkBluetoothPermissionsAndState() {
        val permissions = mutableListOf<String>()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 102)
            binding.switchHardwareSos.isChecked = false
        } else {
            if (bluetoothAdapter?.isEnabled == false) {
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
                binding.switchHardwareSos.isChecked = false
            } else {
                startHardwareService()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == RESULT_OK) {
                binding.switchHardwareSos.isChecked = true
                startHardwareService()
            } else {
                showToast("Bluetooth is required for Hardware SOS")
            }
        }
    }

    private fun startHardwareService() {
        prefs.edit().putBoolean("hardware_sos_enabled", true).apply()
        val serviceIntent = Intent(this, BleGuardianService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        showToast("Hardware monitoring active")
    }

    private fun stopHardwareService() {
        prefs.edit().putBoolean("hardware_sos_enabled", false).apply()
        stopService(Intent(this, BleGuardianService::class.java))
        showToast("Hardware monitoring stopped")
    }
    
    private fun setupOpenRouterHandling() {
        binding.btnSaveOpenRouterSettings.setOnClickListener {
            val key = binding.etOpenRouterApiKey.text.toString().trim()
            val model = binding.etOpenRouterModelName.text.toString().trim()
            
            // Save locally
            prefs.edit().apply {
                putString("gemini_api_key", key)
                putString("gemini_model_name", model)
                apply()
            }
            
            // Sync to DB
            lifecycleScope.launch {
                try {
                    val currentUser = SupabaseClient.client.auth.currentUserOrNull()
                    if (currentUser != null) {
                        SupabaseClient.client.from("users").update({
                            set("gemini_api_key", key)
                        }) {
                            filter { eq("id", currentUser.id) }
                        }
                        showToast("Settings synced to cloud")
                    }
                } catch (e: Exception) {
                    showToast("Settings updated locally")
                }
            }
        }
    }
    
    private fun setupWakeWordCard() {
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
}
