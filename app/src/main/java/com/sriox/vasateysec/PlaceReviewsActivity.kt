package com.sriox.vasateysec

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.sriox.vasateysec.databinding.ActivityPlaceReviewsBinding
import com.sriox.vasateysec.databinding.LayoutPlaceReviewBottomSheetBinding
import com.sriox.vasateysec.databinding.LayoutPlaceReviewDetailBottomSheetBinding
import com.sriox.vasateysec.models.PlaceReview
import com.sriox.vasateysec.models.User
import com.sriox.vasateysec.models.UserProfile
import com.sriox.vasateysec.utils.LocationManager
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class PlaceReviewsActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityPlaceReviewsBinding
    private lateinit var googleMap: GoogleMap
    private val reviewMarkers = mutableListOf<Marker>()
    private val markerReviewMap = mutableMapOf<String, PlaceReview>()

    companion object {
        private const val TAG = "PlaceReviews"
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlaceReviewsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupMap()
        setupBottomNavigation()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = "Place Reviews"
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupMap() {
        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.reviewsMap) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        googleMap.uiSettings.isZoomControlsEnabled = true
        googleMap.uiSettings.isMyLocationButtonEnabled = true
        googleMap.mapType = GoogleMap.MAP_TYPE_HYBRID

        googleMap.setOnMapClickListener { latLng ->
            showReviewDialog(latLng)
        }

        googleMap.setOnMarkerClickListener { marker ->
            val review = markerReviewMap[marker.id]
            if (review != null) {
                showReviewDetail(review)
                true
            } else {
                false
            }
        }

        googleMap.setOnMyLocationButtonClickListener {
            zoomToMyLocation()
            true
        }

        enableMyLocation()
        loadAllReviews()
    }

    private fun zoomToMyLocation() {
        lifecycleScope.launch {
            val loc = LocationManager.getCurrentLocation(this@PlaceReviewsActivity)
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

            Toast.makeText(this@PlaceReviewsActivity, "Location unavailable", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showReviewDetail(review: PlaceReview) {
        val dialog = BottomSheetDialog(this, R.style.BottomSheetDialogTheme)
        val sheetBinding = LayoutPlaceReviewDetailBottomSheetBinding.inflate(layoutInflater)
        dialog.setContentView(sheetBinding.root)

        sheetBinding.tvDetailUserName.text = review.user_name
        sheetBinding.rbDetailSafety.rating = review.rating
        sheetBinding.tvDetailDescription.text = review.description
        
        val dateString = review.created_at?.let { formatTimestamp(it) } ?: "Recently"
        sheetBinding.tvDetailDate.text = "Posted: $dateString"

        val currentUser = SupabaseClient.client.auth.currentUserOrNull()
        if (currentUser != null && review.user_id == currentUser.id) {
            sheetBinding.btnDeleteReview.visibility = View.VISIBLE
            sheetBinding.btnDeleteReview.setOnClickListener {
                deleteReview(review, dialog)
            }
        } else {
            sheetBinding.btnDeleteReview.visibility = View.GONE
        }

        sheetBinding.btnCloseDetail.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun deleteReview(review: PlaceReview, dialog: BottomSheetDialog) {
        lifecycleScope.launch {
            try {
                SupabaseClient.client.from("place_reviews").delete {
                    filter {
                        eq("id", review.id ?: "")
                    }
                }
                Toast.makeText(this@PlaceReviewsActivity, "Review deleted", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                loadAllReviews() 
            } catch (e: Exception) {
                Toast.makeText(this@PlaceReviewsActivity, "Delete failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun formatTimestamp(isoTimestamp: String): String {
        return try {
            val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSSZ", Locale.getDefault())
            val outputFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
            val date = inputFormat.parse(isoTimestamp.replace("Z", "+0000"))
            date?.let { outputFormat.format(it) } ?: isoTimestamp
        } catch (e: Exception) {
            isoTimestamp
        }
    }

    private fun enableMyLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            googleMap.isMyLocationEnabled = true
            zoomToMyLocation()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun showReviewDialog(latLng: LatLng) {
        val dialog = BottomSheetDialog(this, R.style.BottomSheetDialogTheme)
        val sheetBinding = LayoutPlaceReviewBottomSheetBinding.inflate(layoutInflater)
        dialog.setContentView(sheetBinding.root)

        sheetBinding.tvCoords.text = "Lat: ${String.format("%.4f", latLng.latitude)}, Lon: ${String.format("%.4f", latLng.longitude)}"

        sheetBinding.btnSubmitReview.setOnClickListener {
            val desc = sheetBinding.etReviewDescription.text.toString().trim()
            val rating = sheetBinding.rbSafety.rating

            if (desc.isEmpty()) {
                Toast.makeText(this, "Please write a short description", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            submitReview(latLng, desc, rating, dialog)
        }

        dialog.show()
    }

    private fun submitReview(latLng: LatLng, desc: String, rating: Float, dialog: BottomSheetDialog) {
        lifecycleScope.launch {
            try {
                val currentUser = SupabaseClient.client.auth.currentUserOrNull() ?: return@launch
                
                val me = SupabaseClient.client.from("users")
                    .select { filter { eq("id", currentUser.id) } }
                    .decodeSingle<User>()

                val review = PlaceReview(
                    user_id = currentUser.id,
                    user_name = me.name,
                    latitude = latLng.latitude,
                    longitude = latLng.longitude,
                    description = desc,
                    rating = rating
                )

                SupabaseClient.client.from("place_reviews").insert(review)
                
                Toast.makeText(this@PlaceReviewsActivity, "Review submitted!", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                
                loadAllReviews() 
            } catch (e: Exception) {
                Toast.makeText(this@PlaceReviewsActivity, "Failed to submit review", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadAllReviews() {
        lifecycleScope.launch {
            try {
                val reviews = SupabaseClient.client.from("place_reviews")
                    .select()
                    .decodeList<PlaceReview>()

                markerReviewMap.clear()
                reviewMarkers.forEach { it.remove() }
                reviewMarkers.clear()

                reviews.forEach { addReviewMarker(it) }
            } catch (e: Exception) {
                Log.e("PlaceReviews", "Load error: ${e.message}")
            }
        }
    }

    private fun addReviewMarker(review: PlaceReview) {
        val pos = LatLng(review.latitude, review.longitude)
        val marker = googleMap.addMarker(
            MarkerOptions()
                .position(pos)
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_YELLOW))
        )
        marker?.let { 
            reviewMarkers.add(it)
            markerReviewMap[it.id] = review
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
            startActivity(intent)
            finish()
        }
        navHistory?.setOnClickListener {
            val intent = android.content.Intent(this, AlertHistoryActivity::class.java)
            startActivity(intent)
            finish()
        }
        sosButton?.setOnClickListener {
            com.sriox.vasateysec.utils.SOSHelper.showSOSConfirmation(this)
        }
        navGhistory?.setOnClickListener {
            val intent = android.content.Intent(this, GuardianMapActivity::class.java)
            startActivity(intent)
            finish()
        }
        navProfile?.setOnClickListener {
            val intent = android.content.Intent(this, EditProfileActivity::class.java)
            startActivity(intent)
            finish()
        }
    }
}
