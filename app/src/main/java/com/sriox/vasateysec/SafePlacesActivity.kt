package com.sriox.vasateysec

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
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
import com.sriox.vasateysec.databinding.ActivitySafePlacesBinding
import com.sriox.vasateysec.databinding.LayoutSafePlaceBottomSheetBinding
import com.sriox.vasateysec.models.SafePlace
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.launch

class SafePlacesActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivitySafePlacesBinding
    private lateinit var googleMap: GoogleMap
    private var currentLocation: LatLng? = null
    private var selectedLatLng: LatLng? = null
    private val markerPlaceMap = mutableMapOf<String, SafePlace>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySafePlacesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupMap()
        setupWebView()
        setupBottomNavigation()
        setupFab()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = "Near Safe Places"
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupMap() {
        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.safePlacesMap) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    private fun setupWebView() {
        binding.streetViewWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
        }
        binding.streetViewWebView.webViewClient = WebViewClient()
        binding.streetViewWebView.webChromeClient = WebChromeClient()

        binding.btnCloseStreetView.setOnClickListener {
            binding.streetViewCard.visibility = View.GONE
            binding.streetViewWebView.loadUrl("about:blank")
        }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        googleMap.uiSettings.isZoomControlsEnabled = true
        googleMap.uiSettings.isMyLocationButtonEnabled = true
        googleMap.mapType = GoogleMap.MAP_TYPE_HYBRID

        googleMap.setOnMarkerClickListener { marker ->
            showPlaceDetail(marker)
            true
        }

        // Allow user to select a place by clicking on the map
        googleMap.setOnMapClickListener { latLng ->
            selectedLatLng = latLng
            showAddPlaceForm(latLng)
        }

        enableMyLocation()
        loadSafePlaces()
    }

    private fun showAddPlaceForm(latLng: LatLng) {
        binding.tvLocationInfo.text = "Selected: ${String.format("%.4f", latLng.latitude)}, ${String.format("%.4f", latLng.longitude)}"
        binding.addPlaceFormCard.visibility = View.VISIBLE
        binding.addSafePlaceFab.hide()
        
        // Auto-scroll or zoom to selected point if needed
        googleMap.animateCamera(CameraUpdateFactory.newLatLng(latLng))
    }

    private fun showPlaceDetail(marker: Marker) {
        val place = markerPlaceMap[marker.id] ?: return

        val dialog = BottomSheetDialog(this, R.style.BottomSheetDialogTheme)
        val sheetBinding = LayoutSafePlaceBottomSheetBinding.inflate(layoutInflater)
        dialog.setContentView(sheetBinding.root)

        sheetBinding.tvSheetPlaceName.text = place.name
        sheetBinding.tvSheetDescription.text = place.description ?: "No description available"

        sheetBinding.btnStreetView.setOnClickListener {
            showWebStreetView(place.latitude, place.longitude)
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showWebStreetView(lat: Double, lng: Double) {
        binding.streetViewCard.visibility = View.VISIBLE
        
        val url = "https://maps.google.com/maps?q=&layer=c&cbll=$lat,$lng&cbp=11,0,0,0,0&output=svembed"
        
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <style>
                    body, html { margin: 0; padding: 0; height: 100%; width: 100%; overflow: hidden; background-color: #000; }
                    iframe { border: none; height: 100%; width: 100%; }
                </style>
            </head>
            <body>
                <iframe src="$url" allowfullscreen></iframe>
            </body>
            </html>
        """.trimIndent()
        
        binding.streetViewWebView.loadDataWithBaseURL("https://maps.google.com", html, "text/html", "UTF-8", null)
    }

    private fun enableMyLocation() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            googleMap.isMyLocationEnabled = true
            
            val fusedLocationClient = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this)
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    currentLocation = LatLng(location.latitude, location.longitude)
                    googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(currentLocation!!, 15f))
                }
            }
        }
    }

    private fun bitmapDescriptorFromVector(context: Context, vectorResId: Int, scaleFactor: Float = 1.5f): BitmapDescriptor? {
        return ContextCompat.getDrawable(context, vectorResId)?.run {
            val width = (intrinsicWidth * scaleFactor).toInt()
            val height = (intrinsicHeight * scaleFactor).toInt()
            setBounds(0, 0, width, height)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            draw(Canvas(bitmap))
            BitmapDescriptorFactory.fromBitmap(bitmap)
        }
    }

    private fun loadSafePlaces() {
        lifecycleScope.launch {
            try {
                val places = SupabaseClient.client.from("safe_places")
                    .select()
                    .decodeList<SafePlace>()

                markerPlaceMap.clear()
                val safePlaceIcon = bitmapDescriptorFromVector(this@SafePlacesActivity, R.drawable.ic_safe_place, 2.0f)
                
                places.forEach { place ->
                    val pos = LatLng(place.latitude, place.longitude)
                    
                    val markerOptions = MarkerOptions()
                        .position(pos)
                        .title(place.name)
                    
                    if (safePlaceIcon != null) {
                        markerOptions.icon(safePlaceIcon)
                    } else {
                        markerOptions.icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                    }
                    
                    val marker = googleMap.addMarker(markerOptions)
                    marker?.let {
                        markerPlaceMap[it.id] = place
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("SafePlaces", "Error loading places", e)
            }
        }
    }

    private fun setupFab() {
        // FAB now uses current location by default if clicked
        binding.addSafePlaceFab.setOnClickListener {
            if (currentLocation == null) {
                Toast.makeText(this, "Waiting for current location...", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            selectedLatLng = currentLocation
            showAddPlaceForm(currentLocation!!)
        }

        binding.btnCancelAdd.setOnClickListener {
            binding.addPlaceFormCard.visibility = View.GONE
            binding.addSafePlaceFab.show()
            selectedLatLng = null
        }

        binding.btnSavePlace.setOnClickListener {
            saveSafePlace()
        }
    }

    private fun saveSafePlace() {
        val name = binding.etPlaceName.text.toString().trim()
        val description = binding.etPlaceDescription.text.toString().trim()
        val location = selectedLatLng

        if (name.isEmpty() || location == null) {
            Toast.makeText(this, "Please enter a name and select a location on the map", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                val currentUser = SupabaseClient.client.auth.currentUserOrNull()
                val newPlace = SafePlace(
                    name = name,
                    description = description,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    created_by = currentUser?.id,
                    place_type = "safe_house"
                )

                SupabaseClient.client.from("safe_places").insert(newPlace)
                
                Toast.makeText(this@SafePlacesActivity, "Safe place added successfully!", Toast.LENGTH_SHORT).show()
                
                // Reset UI
                binding.etPlaceName.text?.clear()
                binding.etPlaceDescription.text?.clear()
                binding.addPlaceFormCard.visibility = View.GONE
                binding.addSafePlaceFab.show()
                selectedLatLng = null
                
                // Refresh markers
                googleMap.clear()
                loadSafePlaces()
            } catch (e: Exception) {
                android.util.Log.e("SafePlaces", "Error saving place", e)
                Toast.makeText(this@SafePlacesActivity, "Failed to save place: ${e.message}", Toast.LENGTH_SHORT).show()
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
            val intent = android.content.Intent(this, AddGuardianActivity::class.java)
            intent.flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
        }

        navHistory?.setOnClickListener {
            val intent = android.content.Intent(this, AlertHistoryActivity::class.java)
            intent.flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
        }

        sosButton?.setOnClickListener {
            com.sriox.vasateysec.utils.SOSHelper.showSOSConfirmation(this)
        }

        navGhistory?.setOnClickListener {
            val intent = android.content.Intent(this, GuardianMapActivity::class.java)
            intent.flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
        }

        navProfile?.setOnClickListener {
            val intent = android.content.Intent(this, EditProfileActivity::class.java)
            intent.flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
        }
    }

    override fun onDestroy() {
        binding.streetViewWebView.destroy()
        super.onDestroy()
    }
}
