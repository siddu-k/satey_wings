package com.sriox.vasateysec

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.sriox.vasateysec.databinding.ActivityHelpRequestBinding
import com.sriox.vasateysec.models.EmergencyProfile
import com.sriox.vasateysec.utils.AlertConfirmationManager
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

class EmergencyAlertViewerActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityHelpRequestBinding
    private var googleMap: GoogleMap? = null
    private var latitude: Double? = null
    private var longitude: Double? = null
    private var phoneNumber: String? = null
    private var alertId: String? = null
    private var isQrMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHelpRequestBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        isQrMode = intent.getBooleanExtra("isQrMode", false)
        alertId = intent.getStringExtra("alertId") ?: savedInstanceState?.getString("alertId")

        if (isQrMode) {
            val targetUserId = intent.getStringExtra("targetUserId")
            if (targetUserId != null) {
                loadEmergencyProfile(targetUserId)
            }
        } else {
            setupStandardAlertUI()
        }
    }
    
    private fun setupStandardAlertUI() {
        val fullName = intent.getStringExtra("fullName") ?: "Unknown"
        val email = intent.getStringExtra("email") ?: "Unknown"
        phoneNumber = intent.getStringExtra("phoneNumber") ?: ""
        
        val latStr = intent.getStringExtra("latitude") ?: ""
        val lonStr = intent.getStringExtra("longitude") ?: ""
        latitude = latStr.toDoubleOrNull()
        longitude = lonStr.toDoubleOrNull()
        
        val timestamp = intent.getStringExtra("timestamp") ?: ""

        binding.userName.text = fullName
        binding.userEmail.text = email
        binding.userPhone.text = phoneNumber
        binding.alertTime.text = formatTimestamp(timestamp)

        setupCommonFeatures()
        loadPhotos()
    }

    private fun loadEmergencyProfile(userId: String) {
        binding.headerTitle.text = "EMERGENCY PROFILE"
        binding.confirmAlertButton.visibility = View.GONE
        binding.medicalInfoLayout.visibility = View.VISIBLE
        binding.extraContactsCard.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val profile = SupabaseClient.client.from("emergency_profiles")
                    .select { filter { eq("user_id", userId) } }
                    .decodeSingleOrNull<EmergencyProfile>()

                if (profile != null) {
                    binding.userName.text = profile.full_name
                    binding.userEmail.text = "Medical Profile Details"
                    binding.userPhone.text = profile.contact_1
                    binding.tvBloodGroup.text = profile.blood_group ?: "Not specified"
                    binding.tvMedicalNotes.text = profile.medical_notes ?: "No notes"
                    
                    latitude = profile.home_latitude
                    longitude = profile.home_longitude
                    
                    setupQrContacts(profile)
                    setupCommonFeatures()
                } else {
                    Toast.makeText(this@EmergencyAlertViewerActivity, "Profile not found", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                Log.e("Viewer", "Error: ${e.message}")
            }
        }
    }

    private fun setupQrContacts(profile: EmergencyProfile) {
        binding.btnCall1.text = "Call Contact 1: ${profile.contact_1}"
        binding.btnCall1.setOnClickListener { makeCall(profile.contact_1 ?: "") }

        if (!profile.contact_2.isNullOrEmpty()) {
            binding.btnCall2.visibility = View.VISIBLE
            binding.btnCall2.text = "Call Contact 2: ${profile.contact_2}"
            binding.btnCall2.setOnClickListener { makeCall(profile.contact_2!!) }
        }

        if (!profile.contact_3.isNullOrEmpty()) {
            binding.btnCall3.visibility = View.VISIBLE
            binding.btnCall3.text = "Call Contact 3: ${profile.contact_3}"
            binding.btnCall3.setOnClickListener { makeCall(profile.contact_3!!) }
        }
    }

    private fun setupCommonFeatures() {
        val mapFragment = supportFragmentManager.findFragmentById(R.id.mapFragment) as? SupportMapFragment
        mapFragment?.getMapAsync(this)

        binding.callButton.setOnClickListener { makeCall(phoneNumber ?: "") }
        binding.navigateButton.setOnClickListener { navigateTo() }
        
        if (!alertId.isNullOrEmpty()) {
            binding.confirmAlertButton.setOnClickListener { confirmAlert() }
        } else {
            binding.confirmAlertButton.visibility = View.GONE
        }
    }

    private fun makeCall(phone: String) {
        if (phone.isNotEmpty()) {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
        }
    }

    private fun navigateTo() {
        latitude?.let { lat ->
            longitude?.let { lon ->
                val uri = Uri.parse("google.navigation:q=$lat,$lon")
                val intent = Intent(Intent.ACTION_VIEW, uri).apply { setPackage("com.google.android.apps.maps") }
                startActivity(intent)
            }
        }
    }

    private fun formatTimestamp(ts: String): String {
        return try {
            val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).parse(ts)
            SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault()).format(date!!)
        } catch (e: Exception) { "Just now" }
    }

    private fun loadPhotos() {
        val front = intent.getStringExtra("frontPhotoUrl")
        val back = intent.getStringExtra("backPhotoUrl")
        if (!front.isNullOrEmpty() || !back.isNullOrEmpty()) {
            binding.photosContainer.visibility = View.VISIBLE
            binding.photosHeader.visibility = View.VISIBLE
            if (!front.isNullOrEmpty()) {
                binding.frontPhotoCard.visibility = View.VISIBLE
                Glide.with(this).load(front).into(binding.frontPhoto)
            }
            if (!back.isNullOrEmpty()) {
                binding.backPhotoCard.visibility = View.VISIBLE
                Glide.with(this).load(back).into(binding.backPhoto)
            }
        }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        googleMap?.mapType = GoogleMap.MAP_TYPE_SATELLITE
        latitude?.let { lat ->
            longitude?.let { lon ->
                val pos = LatLng(lat, lon)
                googleMap?.addMarker(MarkerOptions().position(pos).title(if (isQrMode) "Home Location" else "Emergency Location"))
                googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(pos, 15f))
            }
        }
    }

    private fun confirmAlert() {
        val currentUser = SupabaseClient.client.auth.currentUserOrNull() ?: return
        lifecycleScope.launch {
            binding.confirmAlertButton.isEnabled = false
            AlertConfirmationManager.confirmAlert(this@EmergencyAlertViewerActivity, alertId!!, currentUser.email!!, currentUser.id)
                .onSuccess { finish() }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("alertId", alertId)
    }
}
