package com.sriox.vasateysec

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
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
import com.sriox.vasateysec.utils.SessionManager
import io.github.jan.supabase.gotrue.auth
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HelpRequestActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityHelpRequestBinding
    private var googleMap: GoogleMap? = null
    private var latitude: Double? = null
    private var longitude: Double? = null
    private var phoneNumber: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHelpRequestBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Check if user is logged in using SessionManager (works when app is closed)
        // or Supabase auth (when app is open)
        lifecycleScope.launch {
            val currentUser = SupabaseClient.client.auth.currentUserOrNull()
            val hasLocalSession = SessionManager.isLoggedIn()
            
            if (currentUser != null) {
                // Supabase session is available
                Log.d("HelpRequest", "User logged in via Supabase, showing alert details")
                setupUI()
            } else if (hasLocalSession) {
                // Fallback to SessionManager (when app is closed but user is logged in)
                Log.d("HelpRequest", "User logged in via SessionManager, showing alert details")
                setupUI()
            } else {
                // User not logged in at all, redirect to login
                Log.d("HelpRequest", "User not logged in, redirecting to login")
                val loginIntent = Intent(this@HelpRequestActivity, LoginActivity::class.java)
                startActivity(loginIntent)
                finish()
            }
        }
    }
    
    private fun setupUI() {
        // Get data from intent
        val fullName = intent.getStringExtra("fullName") ?: "Unknown"
        val email = intent.getStringExtra("email") ?: "Unknown"
        phoneNumber = intent.getStringExtra("phoneNumber") ?: ""
        
        // Get location as strings and convert to Double
        val latStr = intent.getStringExtra("latitude") ?: ""
        val lonStr = intent.getStringExtra("longitude") ?: ""
        
        latitude = latStr.toDoubleOrNull()
        longitude = lonStr.toDoubleOrNull()
        
        val timestamp = intent.getStringExtra("timestamp") ?: ""
        
        Log.d("HelpRequest", "Received location strings: lat='$latStr', lon='$lonStr'")
        Log.d("HelpRequest", "Converted to: lat=$latitude, lon=$longitude")

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

        // Set location coordinates - show raw values from database
        if (latitude != null && longitude != null) {
            binding.locationCoordinates.text = "Lat: $latitude, Long: $longitude"
            Log.d("HelpRequest", "Displaying location: $latitude, $longitude")
        } else {
            binding.locationCoordinates.text = "Location not available"
            Log.w("HelpRequest", "No location to display")
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
        
        // Load emergency photos if available
        val frontPhotoUrl = intent.getStringExtra("frontPhotoUrl") ?: ""
        val backPhotoUrl = intent.getStringExtra("backPhotoUrl") ?: ""
        
        Log.d("HelpRequest", "Photo URLs: front=$frontPhotoUrl, back=$backPhotoUrl")
        
        var hasPhotos = false
        
        if (frontPhotoUrl.isNotEmpty() && frontPhotoUrl != "null") {
            Log.d("HelpRequest", "Loading front camera photo...")
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
            Log.d("HelpRequest", "Loading back camera photo...")
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
            Log.d("HelpRequest", "Emergency photos displayed")
        } else {
            Log.d("HelpRequest", "No emergency photos available")
        }
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Update the intent and reload UI when notification is clicked again
        setIntent(intent)
        Log.d("HelpRequest", "onNewIntent called, reloading UI")
        setupUI()
    }

    override fun onMapReady(map: GoogleMap) {
        Log.d("HelpRequest", "onMapReady called")
        googleMap = map

        if (latitude != null && longitude != null) {
            Log.d("HelpRequest", "Setting map location: $latitude, $longitude")
            val location = LatLng(latitude!!, longitude!!)
            
            // Add marker
            googleMap?.addMarker(
                MarkerOptions()
                    .position(location)
                    .title("Help Request Location")
                    .snippet("User needs assistance here")
            )

            // Move camera
            googleMap?.animateCamera(
                CameraUpdateFactory.newLatLngZoom(location, 15f)
            )

            // Enable zoom controls
            googleMap?.uiSettings?.isZoomControlsEnabled = true
            googleMap?.uiSettings?.isMyLocationButtonEnabled = false
            
            Log.d("HelpRequest", "Map marker and camera set successfully")
        } else {
            Log.w("HelpRequest", "Cannot show map: latitude or longitude is null")
        }
    }
    
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // When back button is pressed, go to HomeActivity
        val intent = Intent(this, HomeActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}
