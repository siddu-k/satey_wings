package com.sriox.vasateysec

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.sriox.vasateysec.databinding.ActivityContactDetailBinding
import com.sriox.vasateysec.models.ContactRequest
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.launch

class ContactDetailActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityContactDetailBinding
    private var googleMap: GoogleMap? = null
    private var requestId: String? = null
    private var requesterLat: Double = 0.0
    private var requesterLng: Double = 0.0

    companion object {
        private const val TAG = "ContactDetail"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityContactDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        handleIntent(intent)
        
        setupMap()
        binding.btnBack.setOnClickListener { finish() }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
        loadRequestDetails()
    }

    private fun handleIntent(intent: Intent?) {
        requestId = intent?.getStringExtra("request_id")
        Log.d(TAG, "Handling intent with Request ID: $requestId")
        if (requestId == null) {
            Toast.makeText(this, "No request data found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupMap() {
        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.contactMap) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        googleMap?.uiSettings?.isZoomControlsEnabled = true
        googleMap?.mapType = GoogleMap.MAP_TYPE_HYBRID
        loadRequestDetails()
    }

    private fun loadRequestDetails() {
        lifecycleScope.launch {
            try {
                if (requestId == null) return@launch
                Log.d(TAG, "Loading details for ID: $requestId")

                val request = SupabaseClient.client.from("contact_requests")
                    .select { filter { eq("id", requestId!!) } }
                    .decodeSingle<ContactRequest>()

                binding.tvRequesterName.text = request.from_user_name
                binding.tvPhoneNumber.text = request.from_user_phone
                
                requesterLat = request.latitude ?: 0.0
                requesterLng = request.longitude ?: 0.0
                
                updateMapLocation()

                binding.btnCallNow.setOnClickListener {
                    val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${request.from_user_phone}"))
                    startActivity(dialIntent)
                    markRequestAsCompleted()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error loading details: ${e.message}")
                Toast.makeText(this@ContactDetailActivity, "Error loading details", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateMapLocation() {
        if (googleMap != null && requesterLat != 0.0) {
            val pos = LatLng(requesterLat, requesterLng)
            googleMap?.clear()
            googleMap?.addMarker(MarkerOptions().position(pos).title(binding.tvRequesterName.text.toString()))
            googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(pos, 15f))
        }
    }

    private fun markRequestAsCompleted() {
        lifecycleScope.launch {
            try {
                if (requestId == null) return@launch
                SupabaseClient.client.from("contact_requests").update({
                    set("status", "completed")
                }) { filter { eq("id", requestId!!) } }
            } catch (e: Exception) { }
        }
    }
}
