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
import com.sriox.vasateysec.utils.AlertConfirmationManager
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Standalone activity to view emergency alerts from notifications
 * Does NOT require login - works independently
 */
class EmergencyAlertViewerActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityHelpRequestBinding
    private var googleMap: GoogleMap? = null
    private var latitude: Double? = null
    private var longitude: Double? = null
    private var phoneNumber: String? = null
    private var alertId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHelpRequestBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        Log.d("EmergencyViewer", "========================================")
        Log.d("EmergencyViewer", "EmergencyAlertViewerActivity CREATED!")
        Log.d("EmergencyViewer", "Intent action: ${intent.action}")
        Log.d("EmergencyViewer", "Intent flags: ${intent.flags}")
        Log.d("EmergencyViewer", "========================================")
        
        // Restore alertId from savedInstanceState if available
        if (savedInstanceState != null) {
            alertId = savedInstanceState.getString("alertId")
            Log.d("EmergencyViewer", "Restored alertId from savedInstanceState: $alertId")
        }
        
        // Load alert data directly - no login check
        setupUI()
    }
    
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("alertId", alertId)
        Log.d("EmergencyViewer", "Saved alertId to savedInstanceState: $alertId")
    }
    
    private fun setupUI() {
        // Get data from intent (only if alertId not already set from savedInstanceState)
        val fullName = intent.getStringExtra("fullName") ?: "Unknown"
        val email = intent.getStringExtra("email") ?: "Unknown"
        phoneNumber = intent.getStringExtra("phoneNumber") ?: ""
        
        // Only update alertId from intent if it's not already set
        if (alertId.isNullOrEmpty()) {
            alertId = intent.getStringExtra("alertId") ?: ""
            Log.d("EmergencyViewer", "Got alertId from intent: '$alertId'")
        } else {
            Log.d("EmergencyViewer", "Using existing alertId: '$alertId'")
        }
        
        // Get location as strings and convert to Double
        val latStr = intent.getStringExtra("latitude") ?: ""
        val lonStr = intent.getStringExtra("longitude") ?: ""
        
        latitude = latStr.toDoubleOrNull()
        longitude = lonStr.toDoubleOrNull()
        
        val timestamp = intent.getStringExtra("timestamp") ?: ""
        
        Log.d("EmergencyViewer", "Alert data: name=$fullName, alertId=$alertId, lat=$latStr, lon=$lonStr")

        // Set data to views
        binding.userName.text = fullName
        binding.userEmail.text = email
        binding.userPhone.text = phoneNumber

        // Format timestamp
        if (timestamp.isNotEmpty()) {
            try {
                val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
                val outputFormat = SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.getDefault())
                val date = inputFormat.parse(timestamp)
                binding.alertTime.text = date?.let { outputFormat.format(it) } ?: "Just now"
            } catch (e: Exception) {
                binding.alertTime.text = "Just now"
            }
        } else {
            binding.alertTime.text = "Just now"
        }

        // Set location coordinates
        if (latitude != null && longitude != null) {
            binding.locationCoordinates.text = "Lat: $latitude, Long: $longitude"
            Log.d("EmergencyViewer", "Displaying location: $latitude, $longitude")
        } else {
            binding.locationCoordinates.text = "Location not available"
            Log.w("EmergencyViewer", "No location to display")
        }

        // Setup map
        val mapFragment = supportFragmentManager.findFragmentById(R.id.mapFragment) as? SupportMapFragment
        mapFragment?.getMapAsync(this)

        // Setup buttons
        binding.callButton.setOnClickListener {
            if (!phoneNumber.isNullOrEmpty()) {
                val intent = Intent(Intent.ACTION_DIAL).apply {
                    data = Uri.parse("tel:$phoneNumber")
                }
                startActivity(intent)
            }
        }

        binding.navigateButton.setOnClickListener {
            if (latitude != null && longitude != null) {
                val uri = Uri.parse("google.navigation:q=$latitude,$longitude")
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage("com.google.android.apps.maps")
                }
                if (intent.resolveActivity(packageManager) != null) {
                    startActivity(intent)
                } else {
                    // Fallback to browser
                    val browserIntent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$latitude,$longitude")
                    )
                    startActivity(browserIntent)
                }
            }
        }

        // Confirm Alert Button - show only if we have alertId
        if (!alertId.isNullOrEmpty()) {
            binding.confirmAlertButton.visibility = View.VISIBLE
            binding.confirmAlertButton.setOnClickListener {
                confirmAlert()
            }
            Log.d("EmergencyViewer", "Confirm button enabled with alertId: $alertId")
        } else {
            binding.confirmAlertButton.visibility = View.GONE
            Log.w("EmergencyViewer", "Confirm button hidden - no alertId available")
        }
        
        // Load emergency photos if available
        val frontPhotoUrl = intent.getStringExtra("frontPhotoUrl") ?: ""
        val backPhotoUrl = intent.getStringExtra("backPhotoUrl") ?: ""
        
        Log.d("EmergencyViewer", "Photo URLs: front=$frontPhotoUrl, back=$backPhotoUrl")
        
        var hasPhotos = false
        
        if (frontPhotoUrl.isNotEmpty() && frontPhotoUrl != "null") {
            Log.d("EmergencyViewer", "Loading front camera photo...")
            binding.frontPhotoCard.visibility = View.VISIBLE
            Glide.with(this)
                .load(frontPhotoUrl)
                .into(binding.frontPhoto)
            
            // Click to view full image
            binding.frontPhoto.setOnClickListener {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(frontPhotoUrl))
                startActivity(intent)
            }
            hasPhotos = true
        }
        
        if (backPhotoUrl.isNotEmpty() && backPhotoUrl != "null") {
            Log.d("EmergencyViewer", "Loading back camera photo...")
            binding.backPhotoCard.visibility = View.VISIBLE
            Glide.with(this)
                .load(backPhotoUrl)
                .into(binding.backPhoto)
            
            // Click to view full image
            binding.backPhoto.setOnClickListener {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(backPhotoUrl))
                startActivity(intent)
            }
            hasPhotos = true
        }
        
        if (hasPhotos) {
            binding.photosHeader.visibility = View.VISIBLE
            binding.photosContainer.visibility = View.VISIBLE
            Log.d("EmergencyViewer", "Emergency photos displayed")
        } else {
            Log.d("EmergencyViewer", "No emergency photos available")
        }
    }

    override fun onMapReady(map: GoogleMap) {
        Log.d("EmergencyViewer", "Map ready")
        googleMap = map
        
        // Set satellite map type
        googleMap?.mapType = GoogleMap.MAP_TYPE_SATELLITE

        if (latitude != null && longitude != null) {
            Log.d("EmergencyViewer", "Setting map location: $latitude, $longitude")
            val location = LatLng(latitude!!, longitude!!)
            
            // Add marker
            googleMap?.addMarker(
                MarkerOptions()
                    .position(location)
                    .title("Emergency Location")
                    .snippet("Person needs help here")
            )

            // Move camera
            googleMap?.animateCamera(
                CameraUpdateFactory.newLatLngZoom(location, 15f)
            )

            // Enable zoom controls
            googleMap?.uiSettings?.isZoomControlsEnabled = true
            googleMap?.uiSettings?.isMyLocationButtonEnabled = false
            
            Log.d("EmergencyViewer", "Map configured successfully")
        } else {
            Log.w("EmergencyViewer", "Cannot show map: latitude or longitude is null")
        }
    }
    
    private fun confirmAlert() {
        Log.d("EmergencyViewer", "Confirm Alert button clicked")
        Log.d("EmergencyViewer", "Current alertId value: '$alertId'")
        Log.d("EmergencyViewer", "Intent extras: ${intent.extras?.keySet()?.joinToString()}")

        if (alertId.isNullOrEmpty()) {
            Toast.makeText(this, "Alert ID not found. Please reopen the alert from notification.", Toast.LENGTH_LONG).show()
            Log.e("EmergencyViewer", "Alert ID is null or empty!")
            return
        }

        // Check if user is logged in
        val currentUser = SupabaseClient.client.auth.currentUserOrNull()
        if (currentUser == null) {
            Toast.makeText(this, "Please log in to confirm alerts", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                binding.confirmAlertButton.isEnabled = false
                binding.confirmAlertButton.text = "Confirming..."

                // Get guardian email from current user session
                val guardianEmail = currentUser.email ?: ""
                
                if (guardianEmail.isEmpty()) {
                    Toast.makeText(this@EmergencyAlertViewerActivity, "Could not get your email", Toast.LENGTH_SHORT).show()
                    binding.confirmAlertButton.isEnabled = true
                    binding.confirmAlertButton.text = "✅ Confirm Alert"
                    return@launch
                }

                val result = AlertConfirmationManager.confirmAlert(
                    context = this@EmergencyAlertViewerActivity,
                    alertId = alertId!!,
                    guardianEmail = guardianEmail,
                    guardianUserId = currentUser.id
                )

                result.onSuccess { message ->
                    Toast.makeText(this@EmergencyAlertViewerActivity, message, Toast.LENGTH_LONG).show()
                    binding.confirmAlertButton.text = "✅ Confirmed"
                    binding.confirmAlertButton.isEnabled = false
                }.onFailure { error ->
                    Toast.makeText(this@EmergencyAlertViewerActivity, "Error: ${error.message}", Toast.LENGTH_LONG).show()
                    binding.confirmAlertButton.isEnabled = true
                    binding.confirmAlertButton.text = "✅ Confirm Alert"
                }

            } catch (e: Exception) {
                Log.e("EmergencyViewer", "Error confirming alert", e)
                Toast.makeText(this@EmergencyAlertViewerActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                binding.confirmAlertButton.isEnabled = true
                binding.confirmAlertButton.text = "✅ Confirm Alert"
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Just finish this activity
        finish()
    }
}
