package com.sriox.vasateysec

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.sriox.vasateysec.databinding.ActivityNearbyPeopleBinding
import com.sriox.vasateysec.databinding.LayoutUserDetailBottomSheetBinding
import com.sriox.vasateysec.models.ContactRequest
import com.sriox.vasateysec.models.User
import com.sriox.vasateysec.utils.FCMTokenManager
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.*

class NearbyPeopleActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityNearbyPeopleBinding
    private lateinit var googleMap: GoogleMap
    private val markers = mutableListOf<Marker>()
    private val markerUserMap = mutableMapOf<String, User>()
    private val markerLocationMap = mutableMapOf<String, com.sriox.vasateysec.models.LiveLocation>()

    companion object {
        private const val TAG = "NearbyPeople"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNearbyPeopleBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupMap()
        setupBottomNavigation()

        binding.refreshButton.setOnClickListener {
            loadNearbyPeople()
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = "People Near Me"
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupMap() {
        val mapFragment = supportFragmentManager.findFragmentById(R.id.mapView) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        googleMap.uiSettings.isZoomControlsEnabled = true
        googleMap.mapType = GoogleMap.MAP_TYPE_HYBRID
        
        googleMap.setOnMarkerClickListener { marker ->
            showUserDetail(marker)
            true
        }
        
        enableMyLocation()
        loadNearbyPeople()
    }

    private fun showUserDetail(marker: Marker) {
        val user = markerUserMap[marker.id] ?: return
        val loc = markerLocationMap[marker.id] ?: return

        val dialog = BottomSheetDialog(this, R.style.BottomSheetDialogTheme)
        val sheetBinding = LayoutUserDetailBottomSheetBinding.inflate(layoutInflater)
        dialog.setContentView(sheetBinding.root)

        sheetBinding.tvSheetUserName.text = user.name
        
        // Added coordinates to the popup for easy verification
        val timeString = loc.updated_at?.let { formatTimestamp(it) } ?: "Just now"
        sheetBinding.tvSheetLastUpdate.text = "Last updated: $timeString\nCoords: ${String.format("%.5f, %.5f", loc.latitude, loc.longitude)}"

        sheetBinding.btnContactUser.setOnClickListener {
            sendContactRequest(user, loc, dialog)
        }

        dialog.show()
    }

    private fun sendContactRequest(targetUser: User, location: com.sriox.vasateysec.models.LiveLocation, dialog: BottomSheetDialog) {
        lifecycleScope.launch {
            try {
                val currentUser = SupabaseClient.client.auth.currentUserOrNull() ?: return@launch
                val me = SupabaseClient.client.from("users")
                    .select { filter { eq("id", currentUser.id) } }
                    .decodeSingle<User>()

                val request = ContactRequest(
                    from_user_id = me.id,
                    from_user_name = me.name,
                    from_user_phone = me.phone,
                    to_user_id = targetUser.id,
                    to_user_name = targetUser.name,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    status = "pending"
                )

                val response = SupabaseClient.client.from("contact_requests").insert(request) { select() }.decodeSingle<ContactRequest>()
                
                val targetTokens = FCMTokenManager.getGuardianTokens(listOf(targetUser.email))
                if (targetTokens.isNotEmpty()) {
                    sendFCMNotification(targetTokens.first().second, me.name, me.phone, response.id ?: "")
                }

                Toast.makeText(this@NearbyPeopleActivity, "Contact request sent!", Toast.LENGTH_LONG).show()
                dialog.dismiss()
            } catch (e: Exception) {
                Toast.makeText(this@NearbyPeopleActivity, "Failed to send request", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun sendFCMNotification(token: String, fromName: String, fromPhone: String, requestId: String) {
        withContext(Dispatchers.IO) {
            try {
                val jsonBody = """{"token": "$token", "type": "contact_request", "title": "📞 Contact Request", "body": "$fromName needs to contact you.", "requestId": "$requestId", "fromName": "$fromName", "fromPhone": "$fromPhone", "email": "contact@satey.app"}""".trimIndent()
                val requestBody = jsonBody.toRequestBody("application/json".toMediaTypeOrNull())
                val request = okhttp3.Request.Builder().url("https://vasatey-notify-msg.vercel.app/api/sendNotification").post(requestBody).build()
                okhttp3.OkHttpClient().newCall(request).execute().close()
            } catch (e: Exception) { }
        }
    }

    private fun enableMyLocation() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            googleMap.isMyLocationEnabled = true
            val fusedLocationClient = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this)
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(location.latitude, location.longitude), 14f))
                }
            }
        }
    }

    private fun bitmapDescriptorFromVector(context: Context, vectorResId: Int, scale: Float = 2.0f): BitmapDescriptor? {
        return ContextCompat.getDrawable(context, vectorResId)?.run {
            val width = (intrinsicWidth * scale).toInt()
            val height = (intrinsicHeight * scale).toInt()
            setBounds(0, 0, width, height)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            draw(Canvas(bitmap))
            BitmapDescriptorFactory.fromBitmap(bitmap)
        }
    }

    private fun loadNearbyPeople() {
        lifecycleScope.launch {
            try {
                // IMPORTANT: Clear the map completely before loading fresh data
                googleMap.clear()
                markers.forEach { it.remove() }
                markers.clear()
                markerUserMap.clear()
                markerLocationMap.clear()

                // Fetch all locations
                val allLocations = SupabaseClient.client.from("live_locations")
                    .select()
                    .decodeList<com.sriox.vasateysec.models.LiveLocation>()

                if (allLocations.isEmpty()) return@launch

                // --- CRITICAL FIX: Only take the LATEST location for each unique user ---
                val latestLocations = allLocations.groupBy { it.user_id }
                    .map { entry -> 
                        entry.value.maxByOrNull { it.updated_at ?: "" } ?: entry.value.first() 
                    }

                // Fetch profiles for these users
                val userIds = latestLocations.map { it.user_id }.distinct()
                val userProfiles = SupabaseClient.client.from("users")
                    .select { filter { isIn("id", userIds) } }
                    .decodeList<User>()
                
                val userMap = userProfiles.associateBy { it.id }
                val userIcon = bitmapDescriptorFromVector(this@NearbyPeopleActivity, R.drawable.ic_profile)

                latestLocations.forEach { loc ->
                    val userData = userMap[loc.user_id]
                    if (userData != null) {
                        Log.d(TAG, "Plotting ${userData.name} at ${loc.latitude}, ${loc.longitude}")
                        val marker = googleMap.addMarker(
                            MarkerOptions()
                                .position(LatLng(loc.latitude, loc.longitude))
                                .title(userData.name)
                                .icon(userIcon ?: BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
                        )
                        marker?.let { 
                            markers.add(it)
                            markerUserMap[it.id] = userData
                            markerLocationMap[it.id] = loc
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading nearby people: ${e.message}")
            }
        }
    }

    private fun formatTimestamp(isoTimestamp: String): String {
        return try {
            val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSSZ", Locale.getDefault())
            val outputFormat = SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault())
            val date = inputFormat.parse(isoTimestamp.replace("Z", "+0000"))
            date?.let { outputFormat.format(it) } ?: isoTimestamp
        } catch (e: Exception) { isoTimestamp }
    }

    private fun setupBottomNavigation() {
        val navGuardians = findViewById<android.widget.LinearLayout>(R.id.navGuardians)
        val navHistory = findViewById<android.widget.LinearLayout>(R.id.navHistory)
        val sosButton = findViewById<com.google.android.material.card.MaterialCardView>(R.id.sosButton)
        val navGhistory = findViewById<android.widget.LinearLayout>(R.id.navGhistory)
        val navProfile = findViewById<android.widget.LinearLayout>(R.id.navProfile)

        navGuardians?.setOnClickListener { startActivity(android.content.Intent(this, AddGuardianActivity::class.java)); finish() }
        navHistory?.setOnClickListener { startActivity(android.content.Intent(this, AlertHistoryActivity::class.java)); finish() }
        sosButton?.setOnClickListener { com.sriox.vasateysec.utils.SOSHelper.showSOSConfirmation(this) }
        navGhistory?.setOnClickListener { startActivity(android.content.Intent(this, GuardianMapActivity::class.java)); finish() }
        navProfile?.setOnClickListener { startActivity(android.content.Intent(this, EditProfileActivity::class.java)); finish() }
    }
}
