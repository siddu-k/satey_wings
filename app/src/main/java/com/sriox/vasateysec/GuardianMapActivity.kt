package com.sriox.vasateysec

import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.sriox.vasateysec.databinding.ActivityGuardianMapBinding
import com.sriox.vasateysec.utils.LiveLocationHelper
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.realtime.*

import kotlinx.coroutines.launch

class GuardianMapActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityGuardianMapBinding
    private lateinit var googleMap: GoogleMap
    private val markers = mutableListOf<Marker>()
    private var realtimeChannel: RealtimeChannel? = null
    private val trackedUserIds = mutableSetOf<String>()

    
    companion object {
        private const val LOCATION_PERMISSION_CODE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGuardianMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupMap()
        setupBottomNavigation()
        
        // Get Location button
        binding.getLocationButton.setOnClickListener {
            // Initiate the request via FCM
            requestLocationsOnly()
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = "Track"
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupMap() {
        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.mapView) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map

        // Enable zoom controls
        googleMap.uiSettings.isZoomControlsEnabled = true
        googleMap.uiSettings.isCompassEnabled = true
        googleMap.uiSettings.isMyLocationButtonEnabled = true

        // Set hybrid view as default (satellite + labels/place names)
        googleMap.mapType = GoogleMap.MAP_TYPE_HYBRID

        // Enable My Location layer if permission is granted
        enableMyLocation()
        
        // Don't load automatically - wait for button click
        // loadTrackedUsersLocations()
    }

    private fun enableMyLocation() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                googleMap.isMyLocationEnabled = true
                googleMap.uiSettings.isMyLocationButtonEnabled = true
                
                // Set up location button click listener
                googleMap.setOnMyLocationButtonClickListener {
                    getCurrentLocationAndZoom()
                    false // Return false to use default behavior (center on location)
                }
                
                // Get current location and zoom to it on first load
                getCurrentLocationAndZoom()
            } else {
                // Request location permission
                requestPermissions(
                    arrayOf(
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION
                    ),
                    LOCATION_PERMISSION_CODE
                )
            }
        } else {
            googleMap.isMyLocationEnabled = true
            googleMap.uiSettings.isMyLocationButtonEnabled = true
            googleMap.setOnMyLocationButtonClickListener {
                getCurrentLocationAndZoom()
                false
            }
            getCurrentLocationAndZoom()
        }
    }

    private fun getCurrentLocationAndZoom() {
        try {
            if (androidx.core.app.ActivityCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(this, "Location permission not granted", Toast.LENGTH_SHORT).show()
                return
            }

            // Use FusedLocationProviderClient for better accuracy
            val fusedLocationClient = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this)
            
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    val myLocation = LatLng(location.latitude, location.longitude)
                    // Zoom to current location with suitable zoom level (15 = street level)
                    googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(myLocation, 15f))
                    android.util.Log.d("GuardianMap", "Zoomed to current location: ${location.latitude}, ${location.longitude}")
                    Toast.makeText(this, "Location found!", Toast.LENGTH_SHORT).show()
                } else {
                    // If last location is null, request fresh location
                    requestFreshLocation()
                }
            }.addOnFailureListener { e ->
                android.util.Log.e("GuardianMap", "Error getting location", e)
                Toast.makeText(this, "Failed to get location: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            android.util.Log.e("GuardianMap", "Error getting current location", e)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestFreshLocation() {
        try {
            if (androidx.core.app.ActivityCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                return
            }

            val fusedLocationClient = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this)
            val locationRequest = com.google.android.gms.location.LocationRequest.create().apply {
                priority = com.google.android.gms.location.LocationRequest.PRIORITY_HIGH_ACCURACY
                interval = 5000
                fastestInterval = 2000
                numUpdates = 1
            }

            val locationCallback = object : com.google.android.gms.location.LocationCallback() {
                override fun onLocationResult(locationResult: com.google.android.gms.location.LocationResult) {
                    locationResult.lastLocation?.let { location ->
                        val myLocation = LatLng(location.latitude, location.longitude)
                        googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(myLocation, 15f))
                        Toast.makeText(this@GuardianMapActivity, "Location updated!", Toast.LENGTH_SHORT).show()
                        fusedLocationClient.removeLocationUpdates(this)
                    }
                }
            }

            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, null)
            Toast.makeText(this, "Getting fresh location...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.util.Log.e("GuardianMap", "Error requesting fresh location", e)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == LOCATION_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                enableMyLocation()
            } else {
                Toast.makeText(this, "Location permission required to show your location", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun addMapBorder() {
        // Get the visible region bounds
        val projection = googleMap.projection
        val visibleRegion = projection.visibleRegion

        // Create a border rectangle
        val rectOptions = PolylineOptions()
            .add(visibleRegion.nearLeft)
            .add(visibleRegion.nearRight)
            .add(visibleRegion.farRight)
            .add(visibleRegion.farLeft)
            .add(visibleRegion.nearLeft)
            .width(10f)
            .color(Color.parseColor("#FF6B35"))
            .pattern(listOf(Dash(30f), Gap(20f)))

        googleMap.addPolyline(rectOptions)
    }

    private fun loadTrackedUsersLocations() {
        lifecycleScope.launch {
            try {
                val currentUser = SupabaseClient.client.auth.currentUserOrNull()
                if (currentUser == null) {
                    Toast.makeText(
                        this@GuardianMapActivity,
                        "Please login to view guardian locations",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }

                // Get current user's email
                val currentUserEmail = currentUser.email
                
                // Find all users who added ME as their guardian (reverse lookup)
                val usersWhoAddedMe = SupabaseClient.client.from("guardians")
                    .select {
                        filter {
                            eq("guardian_email", currentUserEmail ?: "")
                        }
                    }
                    .decodeList<com.sriox.vasateysec.models.Guardian>()

                android.util.Log.d("GuardianMap", "Found ${usersWhoAddedMe.size} users who added me as guardian")

                // Get user IDs
                val userIds = usersWhoAddedMe.map { it.user_id }
                
                // Verify FCM tokens first (for debugging)
                android.util.Log.d("GuardianMap", "🔍 Verifying FCM tokens...")
                val tokenCounts = LiveLocationHelper.verifyFCMTokens(userIds)
                val usersWithoutTokens = tokenCounts.filter { it.value == 0 }
                
                if (usersWithoutTokens.isNotEmpty()) {
                    android.util.Log.w("GuardianMap", "⚠️ ${usersWithoutTokens.size} users have no FCM tokens!")
                    Toast.makeText(
                        this@GuardianMapActivity,
                        "⚠️ ${usersWithoutTokens.size} users may not receive location requests (no FCM token)",
                        Toast.LENGTH_LONG
                    ).show()
                }

                // Request live locations via Supabase function
                android.util.Log.d("GuardianMap", "📍 Requesting live locations...")
                val requestedUserIds = LiveLocationHelper.requestLiveLocations(this@GuardianMapActivity)
                Toast.makeText(this@GuardianMapActivity, "Requesting live locations... Please wait 8 seconds", Toast.LENGTH_LONG).show()

                // Wait small delay for initial setup
                kotlinx.coroutines.delay(1000)

                // Fetch live locations from database
                val liveLocations = LiveLocationHelper.fetchLiveLocations(requestedUserIds)
                android.util.Log.d("GuardianMap", "📍 Received ${liveLocations.size} live locations")
                
                // Show result toast
                if (liveLocations.isNotEmpty()) {
                    Toast.makeText(this@GuardianMapActivity, "✅ Received ${liveLocations.size} live locations", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@GuardianMapActivity, "⚠️ No live locations received, showing last known", Toast.LENGTH_SHORT).show()
                }

                // For each user who added me, fetch their location
                usersWhoAddedMe.forEach { guardianRelation ->
                    val userId = guardianRelation.user_id
                    
                    try {
                        // Get user details
                        val users = SupabaseClient.client.from("users")
                            .select {
                                filter {
                                    eq("id", userId)
                                }
                            }
                            .decodeList<com.sriox.vasateysec.models.User>()

                        if (users.isNotEmpty()) {
                            val user = users[0]
                            val userName = user.name
                            
                            // Check if we have live location first
                            val liveLocation = liveLocations[userId]
                            
                            if (liveLocation != null) {
                                // Use LIVE location (green marker)
                                addUserMarker(userName, liveLocation.latitude, liveLocation.longitude, isLive = true)
                                android.util.Log.d("GuardianMap", "🟢 Added LIVE marker for $userName at ${liveLocation.latitude}, ${liveLocation.longitude}")
                            } else {
                                // Fallback to alert history (orange marker)
                                val alerts = SupabaseClient.client.from("alert_history")
                                    .select {
                                        filter {
                                            eq("user_id", userId)
                                        }
                                    }
                                    .decodeList<com.sriox.vasateysec.models.AlertHistory>()

                                if (alerts.isNotEmpty()) {
                                    // Get the most recent alert with location
                                    val recentAlert = alerts
                                        .sortedByDescending { it.created_at }
                                        .firstOrNull { it.latitude != null && it.longitude != null }

                                    recentAlert?.let { alert ->
                                        val lat = alert.latitude
                                        val lng = alert.longitude

                                        if (lat != null && lng != null) {
                                            addUserMarker(userName, lat, lng, isLive = false)
                                            android.util.Log.d("GuardianMap", "🟠 Added old marker for $userName at $lat, $lng")
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("GuardianMap", "Error loading location for user $userId", e)
                    }
                }

                // If we have markers, adjust camera to show all
                if (markers.isNotEmpty()) {
                    val boundsBuilder = LatLngBounds.Builder()
                    markers.forEach { marker ->
                        boundsBuilder.include(marker.position)
                    }
                    val bounds = boundsBuilder.build()
                    googleMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 100))
                } else {
                    Toast.makeText(
                        this@GuardianMapActivity,
                        "No tracked users found. Users need to add you as their guardian.",
                        Toast.LENGTH_LONG
                    ).show()
                }

            } catch (e: Exception) {
                android.util.Log.e("GuardianMap", "Error loading guardians", e)
                Toast.makeText(
                    this@GuardianMapActivity,
                    "Failed to load guardian locations: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun addUserMarker(name: String, latitude: Double, longitude: Double, isLive: Boolean = false) {
        val position = LatLng(latitude, longitude)
        
        // Green for live location, orange for old location
        val markerColor = if (isLive) {
            BitmapDescriptorFactory.HUE_GREEN
        } else {
            BitmapDescriptorFactory.HUE_ORANGE
        }
        
        val snippet = if (isLive) {
            "Live location (just now)"
        } else {
            "Last known location"
        }
        
        val marker = googleMap.addMarker(
            MarkerOptions()
                .position(position)
                .title(name)
                .snippet(snippet)
                .icon(BitmapDescriptorFactory.defaultMarker(markerColor))
        )
        
        marker?.let { markers.add(it) }
        
        // Add a circle around the marker
        val circleColor = if (isLive) "#00FF00" else "#FF6B35"
        val circleFillColor = if (isLive) "#2200FF00" else "#33FF6B35"
        
        googleMap.addCircle(
            CircleOptions()
                .center(position)
                .radius(500.0) // 500 meters
                .strokeColor(Color.parseColor(circleColor))
                .strokeWidth(3f)
                .fillColor(Color.parseColor(circleFillColor))
        )
    }

    private fun setupBottomNavigation() {
        // Access bottom nav views
        val navGuardians = findViewById<android.widget.LinearLayout>(R.id.navGuardians)
        val navHistory = findViewById<android.widget.LinearLayout>(R.id.navHistory)
        val sosButton = findViewById<com.google.android.material.card.MaterialCardView>(R.id.sosButton)
        val navGhistory = findViewById<android.widget.LinearLayout>(R.id.navGhistory)
        val navProfile = findViewById<android.widget.LinearLayout>(R.id.navProfile)

        // Highlight current item (Track)
        com.sriox.vasateysec.utils.BottomNavHelper.highlightActiveItem(
            this,
            com.sriox.vasateysec.utils.BottomNavHelper.NavItem.GHISTORY
        )

        // Guardians
        navGuardians?.setOnClickListener {
            val intent = android.content.Intent(this, AddGuardianActivity::class.java)
            intent.flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
        }

        // History
        navHistory?.setOnClickListener {
            val intent = android.content.Intent(this, AlertHistoryActivity::class.java)
            intent.flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
        }

        // SOS Button
        sosButton?.setOnClickListener {
            com.sriox.vasateysec.utils.SOSHelper.showSOSConfirmation(this)
        }

        // Track - Already on this screen
        navGhistory?.setOnClickListener {
            // Already here, do nothing
        }

        // Profile
        navProfile?.setOnClickListener {
            val intent = android.content.Intent(this, EditProfileActivity::class.java)
            intent.flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    private fun requestLocationsOnly() {
        lifecycleScope.launch {
            try {
                Toast.makeText(this@GuardianMapActivity, "Requesting live locations...", Toast.LENGTH_SHORT).show()
                
                // 1. Subscribe to Realtime updates first
                 if (realtimeChannel == null) {
                     setupRealtimeSubscription()
                 }
                
                // 2. Identify who we are tracking
                val currentUser = SupabaseClient.client.auth.currentUserOrNull() ?: return@launch
                val usersWhoAddedMe = SupabaseClient.client.from("guardians")
                    .select { filter { eq("guardian_email", currentUser.email ?: "") } }
                    .decodeList<com.sriox.vasateysec.models.Guardian>()
                
                 trackedUserIds.clear()
                 trackedUserIds.addAll(usersWhoAddedMe.map { it.user_id })
                 
                 // 3. Send the FCM request
                 val requestedIds = LiveLocationHelper.requestLiveLocations(this@GuardianMapActivity)
                 
                 // 4. Also fetch current state immediately (fallback)
                 loadTrackedUsersLocations()
                 
            } catch (e: Exception) {
                android.util.Log.e("GuardianMap", "Error requesting locations", e)
            }
        }
    }

    private suspend fun setupRealtimeSubscription() {
        try {
            val channel = SupabaseClient.client.realtime.channel("live-locations")
            
            // Listen for INSERT and UPDATE on live_locations table
            val changeFlow = channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
                table = "live_locations"
            }
            val updateFlow = channel.postgresChangeFlow<PostgresAction.Update>(schema = "public") {
                table = "live_locations"
            }

            // Join the channel
            SupabaseClient.client.realtime.connect()
            channel.subscribe()
            realtimeChannel = channel
            
            android.util.Log.d("GuardianMap", "✅ Subscribed to Realtime location updates")

            // Process Insert events
            lifecycleScope.launch {
                changeFlow.collect { action ->
                    handleRealtimeUpdate(action.record)
                }
            }
            
            // Process Update events
            lifecycleScope.launch {
                updateFlow.collect { action ->
                     handleRealtimeUpdate(action.record)
                }
            }

        } catch (e: Exception) {
            android.util.Log.e("GuardianMap", "Failed to setup Realtime", e)
        }
    }
    
    private fun handleRealtimeUpdate(record: Map<String, Any>) {
         try {
             val userId = record["user_id"] as? String
             val lat = (record["latitude"] as? Number)?.toDouble()
             val lon = (record["longitude"] as? Number)?.toDouble()
             
             if (userId != null && lat != null && lon != null) {
                 if (trackedUserIds.contains(userId) || trackedUserIds.isEmpty()) {
                      runOnUiThread {
                          android.util.Log.d("GuardianMap", "⚡ REALTIME UPDATE: $userId moved to $lat, $lon")
                          loadTrackedUsersLocations() 
                          Toast.makeText(this@GuardianMapActivity, "⚡ Location updated!", Toast.LENGTH_SHORT).show()
                      }
                 }
             }
         } catch (e: Exception) {
             android.util.Log.e("GuardianMap", "Error parsing realtime update", e)
         }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        lifecycleScope.launch {
            try {
                realtimeChannel?.unsubscribe()
                SupabaseClient.client.realtime.disconnect()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }
}
