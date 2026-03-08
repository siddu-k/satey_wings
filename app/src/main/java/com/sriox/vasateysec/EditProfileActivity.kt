package com.sriox.vasateysec

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.work.*
import com.sriox.vasateysec.databinding.ActivityEditProfileBinding
import com.sriox.vasateysec.models.UserProfile
import com.sriox.vasateysec.workers.LocationUpdateWorker
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class EditProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditProfileBinding
    private var userId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupBottomNavigation()
        loadProfile()

        // Highlight Profile nav item
        com.sriox.vasateysec.utils.BottomNavHelper.highlightActiveItem(
            this,
            com.sriox.vasateysec.utils.BottomNavHelper.NavItem.PROFILE
        )

        // Back button
        binding.backButton.setOnClickListener {
            finish()
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
            logout()
        }

        binding.updateLocationButton.setOnClickListener {
            updateCurrentLocation()
        }

        binding.autoLocationSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                checkLocationPermissionsAndStartWorker()
            } else {
                cancelLocationWorker()
            }
        }
    }

    private fun checkLocationPermissionsAndStartWorker() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            androidx.core.app.ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION),
                1001
            )
            binding.autoLocationSwitch.isChecked = false
        } else {
            startLocationWorker()
        }
    }

    private fun startLocationWorker() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val locationRequest = PeriodicWorkRequestBuilder<LocationUpdateWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "location_update_worker",
            ExistingPeriodicWorkPolicy.KEEP,
            locationRequest
        )
        Toast.makeText(this, "Live Tracking Enabled", Toast.LENGTH_SHORT).show()
    }

    private fun cancelLocationWorker() {
        WorkManager.getInstance(this).cancelUniqueWork("location_update_worker")
        Toast.makeText(this, "Live Tracking Disabled", Toast.LENGTH_SHORT).show()
    }

    private fun setupBottomNavigation() {
        val navGuardians = findViewById<android.widget.LinearLayout>(R.id.navGuardians)
        val navHistory = findViewById<android.widget.LinearLayout>(R.id.navHistory)
        val sosButton = findViewById<com.google.android.material.card.MaterialCardView>(R.id.sosButton)
        val navGhistory = findViewById<android.widget.LinearLayout>(R.id.navGhistory)
        val navProfile = findViewById<android.widget.LinearLayout>(R.id.navProfile)

        navGuardians?.setOnClickListener {
            startActivity(android.content.Intent(this, AddGuardianActivity::class.java))
        }
        navHistory?.setOnClickListener {
            startActivity(android.content.Intent(this, AlertHistoryActivity::class.java))
        }
        sosButton?.setOnClickListener {
            com.sriox.vasateysec.utils.SOSHelper.showSOSConfirmation(this)
        }
        navGhistory?.setOnClickListener {
            startActivity(android.content.Intent(this, AlertHistoryActivity::class.java))
        }
        navProfile?.setOnClickListener { /* Already here */ }
    }

    private fun logout() {
        lifecycleScope.launch {
            try {
                SupabaseClient.client.auth.signOut()
                com.sriox.vasateysec.utils.SessionManager.clearSession()
                val intent = android.content.Intent(this@EditProfileActivity, LoginActivity::class.java)
                intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            } catch (e: Exception) {
                Toast.makeText(this@EditProfileActivity, "Logout failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadProfile() {
        lifecycleScope.launch {
            try {
                val currentUser = SupabaseClient.client.auth.currentUserOrNull()
                if (currentUser == null) {
                    Toast.makeText(this@EditProfileActivity, "User not logged in", Toast.LENGTH_SHORT).show()
                    finish()
                    return@launch
                }

                userId = currentUser.id
                val userProfile = try {
                    SupabaseClient.client.from("users")
                        .select { filter { eq("id", currentUser.id) } }
                        .decodeSingle<UserProfile>()
                } catch (e: Exception) {
                    UserProfile(
                        id = currentUser.id,
                        name = currentUser.email?.substringBefore("@") ?: "User",
                        email = currentUser.email ?: "",
                        phone = "",
                        wake_word = "help me",
                        cancel_password = "",
                        is_auto_location_enabled = false
                    )
                }

                binding.nameInput.setText(userProfile.name ?: "")
                binding.emailInput.setText(userProfile.email ?: "")
                binding.phoneInput.setText(userProfile.phone ?: "")
                binding.wakeWordInput.setText(userProfile.wake_word ?: "help me")
                binding.cancelPasswordInput.setText(userProfile.cancel_password ?: "")
                binding.autoLocationSwitch.isChecked = userProfile.is_auto_location_enabled

                loadLastLocationTime(userProfile.last_location_updated_at)

            } catch (e: Exception) {
                Toast.makeText(this@EditProfileActivity, "Failed to load profile", Toast.LENGTH_SHORT).show()
            }
        }
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
        binding.updateLocationButton.isEnabled = false
        binding.updateLocationButton.text = "Updating..."

        lifecycleScope.launch {
            try {
                if (android.content.pm.PackageManager.PERMISSION_GRANTED != 
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        this@EditProfileActivity,
                        android.Manifest.permission.ACCESS_FINE_LOCATION
                    )) {
                    androidx.core.app.ActivityCompat.requestPermissions(
                        this@EditProfileActivity,
                        arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION),
                        1001
                    )
                    binding.updateLocationButton.isEnabled = true
                    binding.updateLocationButton.text = "Update Location"
                    return@launch
                }

                val fusedLocationClient = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this@EditProfileActivity)
                fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                    if (location != null) {
                        lifecycleScope.launch {
                            try {
                                val currentUser = SupabaseClient.client.auth.currentUserOrNull()
                                if (currentUser == null) return@launch

                                SupabaseClient.client.from("users").update({
                                    set("last_latitude", location.latitude)
                                    set("last_longitude", location.longitude)
                                    set("last_location_updated_at", java.time.Instant.now().toString())
                                }) { filter { eq("id", currentUser.id) } }

                                Toast.makeText(this@EditProfileActivity, "✅ Location updated!", Toast.LENGTH_SHORT).show()
                                loadLastLocationTime(java.time.Instant.now().toString())
                            } catch (e: Exception) {
                                Toast.makeText(this@EditProfileActivity, "Failed to update: ${e.message}", Toast.LENGTH_SHORT).show()
                            } finally {
                                binding.updateLocationButton.isEnabled = true
                                binding.updateLocationButton.text = "Update Location"
                            }
                        }
                    } else {
                        Toast.makeText(this@EditProfileActivity, "Unable to get location", Toast.LENGTH_SHORT).show()
                        binding.updateLocationButton.isEnabled = true
                        binding.updateLocationButton.text = "Update Location"
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this@EditProfileActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                binding.updateLocationButton.isEnabled = true
                binding.updateLocationButton.text = "Update Location"
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
}
