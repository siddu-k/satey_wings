package com.sriox.vasateysec

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.CompoundButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.*
import com.google.android.gms.location.*
import com.sriox.vasateysec.databinding.ActivityEditProfileBinding
import com.sriox.vasateysec.models.LiveLocation
import com.sriox.vasateysec.models.UserProfile
import com.sriox.vasateysec.workers.LocationUpdateWorker
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class EditProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditProfileBinding
    private var userId: String? = null
    private lateinit var prefs: android.content.SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("vasatey_settings", MODE_PRIVATE)

        setupHeader()
        loadProfile()
        setupSwitches()
        setupBottomNavigation()

        binding.updateLocationButton.setOnClickListener {
            updateCurrentLocation()
        }

        binding.saveButton.setOnClickListener {
            val name = binding.nameInput.text.toString().trim()
            val phone = binding.phoneInput.text.toString().trim()
            val wakeWord = binding.wakeWordInput.text.toString().trim()
            val cancelPassword = binding.cancelPasswordInput.text.toString().trim()
            val isAutoLocationEnabled = binding.autoLocationSwitch.isChecked

            if (validateInputs(name, phone, wakeWord)) {
                updateProfile(name, phone, wakeWord, cancelPassword, isAutoLocationEnabled)
            }
        }

        binding.logoutButton.setOnClickListener {
            logoutUser()
        }
    }

    private fun logoutUser() {
        lifecycleScope.launch {
            try {
                SupabaseClient.client.auth.signOut()
                com.sriox.vasateysec.utils.SessionManager.clearSession()
                val intent = Intent(this@EditProfileActivity, LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            } catch (e: Exception) {
                Toast.makeText(this@EditProfileActivity, "Logout failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupHeader() {
        binding.backButton.setOnClickListener {
            finish()
        }
    }

    private fun loadProfile() {
        lifecycleScope.launch {
            try {
                val currentUser = SupabaseClient.client.auth.currentUserOrNull() ?: return@launch
                userId = currentUser.id

                val userProfile = SupabaseClient.client.from("users").select { filter { eq("id", userId!!) } }.decodeSingle<UserProfile>()

                binding.nameInput.setText(userProfile.name)
                binding.emailInput.setText(userProfile.email)
                binding.phoneInput.setText(userProfile.phone)
                binding.wakeWordInput.setText(userProfile.wake_word)
                binding.cancelPasswordInput.setText(userProfile.cancel_password)
                binding.autoLocationSwitch.isChecked = userProfile.is_auto_location_enabled

                loadLastLocationTime(userProfile.last_location_updated_at)
            } catch (e: Exception) {
                Log.e("EditProfile", "Error loading profile: ${e.message}")
            }
        }
    }

    private fun setupSwitches() {
        binding.autoLocationSwitch.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            if (isChecked) {
                checkLocationPermissionsAndStartWorker()
            } else {
                cancelLocationWorker()
            }
        }
    }

    private fun checkLocationPermissionsAndStartWorker() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1001)
            binding.autoLocationSwitch.isChecked = false
        } else {
            startLocationWorker()
        }
    }

    private fun startLocationWorker() {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val locationRequest = PeriodicWorkRequestBuilder<LocationUpdateWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("location_update_worker", ExistingPeriodicWorkPolicy.KEEP, locationRequest)
        Toast.makeText(this, "Live Tracking Enabled", Toast.LENGTH_SHORT).show()
    }

    private fun cancelLocationWorker() {
        WorkManager.getInstance(this).cancelUniqueWork("location_update_worker")
        Toast.makeText(this, "Live Tracking Disabled", Toast.LENGTH_SHORT).show()
    }

    private fun loadLastLocationTime(lastUpdated: String?) {
        if (lastUpdated.isNullOrEmpty()) {
            binding.lastLocationUpdateText.text = "Location not updated yet"
            return
        }
        try {
            val instant = java.time.Instant.parse(lastUpdated)
            val now = java.time.Instant.now()
            val duration = java.time.Duration.between(instant, now)
            val timeAgo = when {
                duration.toMinutes() < 1 -> "Just now"
                duration.toMinutes() < 60 -> "${duration.toMinutes()} mins ago"
                duration.toHours() < 24 -> "${duration.toHours()} hours ago"
                else -> "${duration.toDays()} days ago"
            }
            binding.lastLocationUpdateText.text = "Last updated: $timeAgo"
        } catch (e: Exception) {
            binding.lastLocationUpdateText.text = "Location not updated yet"
        }
    }

    private fun updateCurrentLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1001)
            return
        }

        binding.updateLocationButton.isEnabled = false
        binding.updateLocationButton.text = "Detecting GPS..."

        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        
        // Request a FRESH location instead of using lastLocation
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setMaxUpdates(1)
            .build()

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation
                if (location != null) {
                    syncLocationToDatabase(location)
                } else {
                    Toast.makeText(this@EditProfileActivity, "GPS timeout. Try again.", Toast.LENGTH_SHORT).show()
                    binding.updateLocationButton.isEnabled = true
                    binding.updateLocationButton.text = "Update Now"
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, mainLooper)
    }

    private fun syncLocationToDatabase(location: android.location.Location) {
        lifecycleScope.launch {
            try {
                val currentUser = SupabaseClient.client.auth.currentUserOrNull() ?: return@launch
                val timestamp = java.time.Instant.now().toString()

                // 1. Update users table
                SupabaseClient.client.from("users").update({
                    set("last_latitude", location.latitude)
                    set("last_longitude", location.longitude)
                    set("last_location_updated_at", timestamp)
                }) { filter { eq("id", currentUser.id) } }

                // 2. Update live_locations table (CLEAN SYNC)
                // Delete first to avoid unique constraint (duplicate key) errors
                try {
                    SupabaseClient.client.from("live_locations").delete {
                        filter { eq("user_id", currentUser.id) }
                    }
                } catch (e: Exception) { /* Might not exist */ }

                val liveLocation = LiveLocation(
                    user_id = currentUser.id,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracy = location.accuracy,
                    updated_at = timestamp
                )
                SupabaseClient.client.from("live_locations").insert(liveLocation)

                Toast.makeText(this@EditProfileActivity, "✅ Location updated!", Toast.LENGTH_SHORT).show()
                loadLastLocationTime(timestamp)
            } catch (e: Exception) {
                Log.e("EditProfile", "Sync failed: ${e.message}")
                Toast.makeText(this@EditProfileActivity, "Sync failed: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.updateLocationButton.isEnabled = true
                binding.updateLocationButton.text = "Update Now"
            }
        }
    }

    private fun validateInputs(name: String, phone: String, wakeWord: String): Boolean {
        if (name.isEmpty()) {
            binding.nameInputLayout.error = "Name is required"
            return false
        }
        if (phone.isEmpty()) {
            binding.phoneInputLayout.error = "Phone number is required"
            return false
        }
        if (wakeWord.isEmpty() || wakeWord.length < 3) {
            binding.wakeWordInputLayout.error = "Wake word must be at least 3 characters"
            return false
        }
        return true
    }

    private fun updateProfile(name: String, phone: String, wakeWord: String, cancelPassword: String, isAutoLocationEnabled: Boolean) {
        binding.saveButton.isEnabled = false
        lifecycleScope.launch {
            try {
                if (userId == null) return@launch
                SupabaseClient.client.from("users").update({
                    set("name", name)
                    set("phone", phone)
                    set("wake_word", wakeWord.lowercase())
                    set("is_auto_location_enabled", isAutoLocationEnabled)
                    if (cancelPassword.isNotEmpty()) set("cancel_password", cancelPassword)
                }) { filter { eq("id", userId!!) } }

                getSharedPreferences("vasatey_prefs", MODE_PRIVATE).edit()
                    .putString("wake_word", wakeWord.lowercase())
                    .apply()

                Toast.makeText(this@EditProfileActivity, "Profile updated successfully!", Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) {
                Toast.makeText(this@EditProfileActivity, "Failed to update profile: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.saveButton.isEnabled = true
            }
        }
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
        navHistory?.setOnClickListener {
            val intent = Intent(this, AlertHistoryActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
        }
        sosButton?.setOnClickListener {
            com.sriox.vasateysec.utils.SOSHelper.showSOSConfirmation(this)
        }
        navGhistory?.setOnClickListener {
            val intent = Intent(this, GuardianMapActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
        }
        navProfile?.setOnClickListener { /* Already here */ }
    }
}
