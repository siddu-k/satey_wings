package com.sriox.vasateysec

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.core.app.ActivityCompat
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
import com.sriox.vasateysec.models.UserProfile
import com.sriox.vasateysec.utils.LocationManager
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.launch

class SafePlacesActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivitySafePlacesBinding
    private lateinit var googleMap: GoogleMap
    private var currentLocation: LatLng? = null
    private var selectedLatLng: LatLng? = null
    private val markerPlaceMap = mutableMapOf<String, SafePlace>()

    companion object {
        private const val TAG = "SafePlacesActivity"
    }

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

        googleMap.setOnMapClickListener { latLng ->
            selectedLatLng = latLng
            showAddPlaceForm(latLng)
        }

        googleMap.setOnMyLocationButtonClickListener {
            zoomToMyLocation()
            true
        }

        enableMyLocation()
        loadSafePlaces()
    }

    private fun zoomToMyLocation() {
        lifecycleScope.launch {
            val loc = LocationManager.getCurrentLocation(this@SafePlacesActivity)
            if (loc != null) {
                googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), 15f))
                return@launch
            }

            try {
                val currentUser = SupabaseClient.client.auth.currentUserOrNull()
                if (currentUser != null) {
                    val profile = SupabaseClient.client.from("users")
                        .select { filter { eq("id", currentUser.id) } }
                        .decodeSingle<UserProfile>()
                    
                    if (profile.last_latitude != null && profile.last_longitude != null) {
                        googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(
                            LatLng(profile.last_latitude, profile.last_longitude), 15f
                        ))
                        return@launch
                    }
                }
            } catch (e: Exception) { }

            Toast.makeText(this@SafePlacesActivity, "Location unavailable", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAddPlaceForm(latLng: LatLng) {
        binding.tvLocationInfo.text = "Selected: ${String.format("%.4f", latLng.latitude)}, ${String.format("%.4f", latLng.longitude)}"
        binding.addPlaceFormCard.visibility = View.VISIBLE
        binding.addSafePlaceFab.hide()
        googleMap.animateCamera(CameraUpdateFactory.newLatLng(latLng))
    }

    private fun showPlaceDetail(marker: Marker) {
        val place = markerPlaceMap[marker.id] ?: return

        val dialog = BottomSheetDialog(this, R.style.BottomSheetDialogTheme)
        val sheetBinding = LayoutSafePlaceBottomSheetBinding.inflate(layoutInflater)
        dialog.setContentView(sheetBinding.root)

        sheetBinding.tvSheetPlaceName.text = place.name
        sheetBinding.tvSheetDescription.text = place.description ?: "No description available"

        val currentUser = SupabaseClient.client.auth.currentUserOrNull()
        if (currentUser != null && place.created_by == currentUser.id) {
            sheetBinding.btnDeleteSafePlace.visibility = View.VISIBLE
            sheetBinding.btnDeleteSafePlace.setOnClickListener {
                deleteSafePlace(place, dialog)
            }
        } else {
            sheetBinding.btnDeleteSafePlace.visibility = View.GONE
        }

        sheetBinding.btnStreetView.setOnClickListener {
            showWebStreetView(place.latitude, place.longitude)
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun deleteSafePlace(place: SafePlace, dialog: BottomSheetDialog) {
        lifecycleScope.launch {
            try {
                SupabaseClient.client.from("safe_places").delete {
                    filter { eq("id", place.id ?: "") }
                }
                Toast.makeText(this@SafePlacesActivity, "Safe place removed", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                loadSafePlaces()
            } catch (e: Exception) {
                Toast.makeText(this@SafePlacesActivity, "Delete failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showWebStreetView(lat: Double, lng: Double) {
        binding.streetViewCard.visibility = View.VISIBLE
        val url = "https://maps.google.com/maps?q=&layer=c&cbll=$lat,$lng&cbp=11,0,0,0,0&output=svembed"
        val html = "<html><head><style>body,html{margin:0;padding:0;height:100%;width:100%;overflow:hidden;background:#000;}iframe{border:none;height:100%;width:100%;}</style></head><body><iframe src=\"$url\" allowfullscreen></iframe></body></html>"
        binding.streetViewWebView.loadDataWithBaseURL("https://maps.google.com", html, "text/html", "UTF-8", null)
    }

    private fun enableMyLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            googleMap.isMyLocationEnabled = true
            zoomToMyLocation()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1001)
        }
    }

    private fun bitmapDescriptorFromVector(context: Context, vectorResId: Int, scaleFactor: Float = 2.0f): BitmapDescriptor? {
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
                googleMap.clear()
                val places = SupabaseClient.client.from("safe_places").select().decodeList<SafePlace>()
                markerPlaceMap.clear()
                val safePlaceIcon = bitmapDescriptorFromVector(this@SafePlacesActivity, R.drawable.ic_safe_place, 2.0f)
                places.forEach { place ->
                    val pos = LatLng(place.latitude, place.longitude)
                    val marker = googleMap.addMarker(MarkerOptions().position(pos).title(place.name).icon(safePlaceIcon ?: BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)))
                    marker?.let { markerPlaceMap[it.id] = place }
                }
            } catch (e: Exception) { }
        }
    }

    private fun setupFab() {
        binding.addSafePlaceFab.setOnClickListener {
            if (currentLocation == null) {
                Toast.makeText(this, "Waiting for location...", Toast.LENGTH_SHORT).show()
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
        binding.btnSavePlace.setOnClickListener { saveSafePlace() }
    }

    private fun saveSafePlace() {
        val name = binding.etPlaceName.text.toString().trim()
        val description = binding.etPlaceDescription.text.toString().trim()
        val location = selectedLatLng
        if (name.isEmpty() || location == null) return

        lifecycleScope.launch {
            try {
                val currentUser = SupabaseClient.client.auth.currentUserOrNull()
                val newPlace = SafePlace(name = name, description = description, latitude = location.latitude, longitude = location.longitude, created_by = currentUser?.id)
                SupabaseClient.client.from("safe_places").insert(newPlace)
                binding.etPlaceName.text?.clear()
                binding.etPlaceDescription.text?.clear()
                binding.addPlaceFormCard.visibility = View.GONE
                binding.addSafePlaceFab.show()
                loadSafePlaces()
            } catch (e: Exception) { }
        }
    }

    private fun setupBottomNavigation() {
        val sosButton = findViewById<com.google.android.material.card.MaterialCardView>(R.id.sosButton)
        sosButton?.setOnClickListener { com.sriox.vasateysec.utils.SOSHelper.showSOSConfirmation(this) }
    }

    override fun onDestroy() {
        binding.streetViewWebView.destroy()
        super.onDestroy()
    }
}
