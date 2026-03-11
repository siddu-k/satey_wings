package com.sriox.vasateysec

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.sriox.vasateysec.databinding.ActivityEmergencyProfileBinding
import com.sriox.vasateysec.models.EmergencyProfile
import com.sriox.vasateysec.utils.LocationManager
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream

class EmergencyProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEmergencyProfileBinding
    private var homeLatLng: LatLng? = null
    private var qrBitmap: Bitmap? = null
    private var currentUserId: String? = null
    private var existingProfileId: String? = null // Store the DB ID to prevent duplicates

    companion object {
        private const val LOCATION_REQ_CODE = 201
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEmergencyProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadExistingProfile()
        setupListeners()
    }

    private fun setupListeners() {
        binding.btnSetHomeLocation.setOnClickListener {
            showMapPickerDialog()
        }

        binding.btnSaveProfile.setOnClickListener {
            saveProfile()
        }

        binding.btnDownloadQr.setOnClickListener {
            downloadQrCode()
        }
    }

    private fun loadExistingProfile() {
        lifecycleScope.launch {
            try {
                val user = SupabaseClient.client.auth.currentUserOrNull() ?: return@launch
                currentUserId = user.id
                
                generateQr(user.id)

                val profile = SupabaseClient.client.from("emergency_profiles")
                    .select { filter { eq("user_id", user.id) } }
                    .decodeSingleOrNull<EmergencyProfile>()

                profile?.let {
                    existingProfileId = it.id // Capture the ID for future updates
                    binding.etFullName.setText(it.full_name)
                    binding.etBloodGroup.setText(it.blood_group)
                    binding.etContact1.setText(it.contact_1)
                    binding.etContact2.setText(it.contact_2)
                    binding.etContact3.setText(it.contact_3)
                    binding.etMedicalNotes.setText(it.medical_notes)
                    
                    if (it.home_latitude != null && it.home_longitude != null) {
                        homeLatLng = LatLng(it.home_latitude, it.home_longitude)
                        binding.tvHomeCoords.text = "Home set: ${it.home_latitude}, ${it.home_longitude}"
                    }
                }
            } catch (e: Exception) {
                Log.e("EmergencyProfile", "Error loading profile", e)
            }
        }
    }

    private fun showMapPickerDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_map_picker, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        var selectedLatLng = homeLatLng ?: LatLng(17.3850, 78.4867) 

        dialog.setOnShowListener {
            val mapFrag = supportFragmentManager.findFragmentById(R.id.map_picker_fragment) as? SupportMapFragment
            mapFrag?.getMapAsync { googleMap ->
                googleMap.mapType = GoogleMap.MAP_TYPE_SATELLITE
                
                googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(selectedLatLng, 15f))
                
                googleMap.setOnCameraIdleListener {
                    selectedLatLng = googleMap.cameraPosition.target
                }

                dialogView.findViewById<View>(R.id.btnGetMyLocation).setOnClickListener {
                    lifecycleScope.launch {
                        val myLoc = LocationManager.getCurrentLocation(this@EmergencyProfileActivity)
                        if (myLoc != null) {
                            val latLng = LatLng(myLoc.latitude, myLoc.longitude)
                            googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 18f))
                        } else {
                            Toast.makeText(this@EmergencyProfileActivity, "Could not get current location", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                dialogView.findViewById<View>(R.id.btnConfirmLocation).setOnClickListener {
                    homeLatLng = selectedLatLng
                    binding.tvHomeCoords.text = "Home set: ${homeLatLng?.latitude}, ${homeLatLng?.longitude}"
                    
                    supportFragmentManager.beginTransaction().remove(mapFrag).commit()
                    dialog.dismiss()
                }
            }
        }
        
        dialog.setOnDismissListener {
            val frag = supportFragmentManager.findFragmentById(R.id.map_picker_fragment)
            if (frag != null) supportFragmentManager.beginTransaction().remove(frag).commit()
        }

        dialog.show()
    }

    private fun saveProfile() {
        val fullName = binding.etFullName.text.toString().trim()
        val contact1 = binding.etContact1.text.toString().trim()

        if (fullName.isEmpty() || contact1.isEmpty()) {
            Toast.makeText(this, "Name and Contact 1 are required", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                val userId = currentUserId ?: return@launch
                
                val profile = EmergencyProfile(
                    id = existingProfileId, 
                    user_id = userId,
                    full_name = fullName,
                    blood_group = binding.etBloodGroup.text.toString().trim(),
                    contact_1 = contact1,
                    contact_2 = binding.etContact2.text.toString().trim(),
                    contact_3 = binding.etContact3.text.toString().trim(),
                    home_latitude = homeLatLng?.latitude,
                    home_longitude = homeLatLng?.longitude,
                    medical_notes = binding.etMedicalNotes.text.toString().trim()
                )

                // Corrected upsert syntax: onConflict is a named parameter
                SupabaseClient.client.from("emergency_profiles").upsert(profile, onConflict = "user_id")
                
                if (existingProfileId == null) {
                    loadExistingProfile()
                }
                
                Toast.makeText(this@EmergencyProfileActivity, "Profile Updated Successfully!", Toast.LENGTH_SHORT).show()
                
            } catch (e: Exception) {
                Log.e("EmergencyProfile", "Save error: ${e.message}")
                Toast.makeText(this@EmergencyProfileActivity, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun generateQr(userId: String) {
        try {
            val writer = QRCodeWriter()
            val url = "https://siddu-k.github.io/satey_tracker/emergency-profile.html?user_id=$userId"
            val bitMatrix = writer.encode(url, BarcodeFormat.QR_CODE, 512, 512)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            qrBitmap = bitmap
            binding.ivQrCode.setImageBitmap(bitmap)
            binding.qrContainer.visibility = View.VISIBLE
        } catch (e: Exception) {
            Log.e("EmergencyProfile", "QR Generation failed", e)
        }
    }

    private fun downloadQrCode() {
        val bitmap = qrBitmap ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val filename = "Vasatey_Emergency_QR_${currentUserId?.take(8)}.jpg"
                var fos: OutputStream? = null
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentResolver?.also { resolver ->
                        val contentValues = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpg")
                            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Vasatey")
                        }
                        val imageUri: Uri? = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                        fos = imageUri?.let { resolver.openOutputStream(it) }
                    }
                } else {
                    val imagesDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES).toString()
                    val image = java.io.File(imagesDir, filename)
                    fos = java.io.FileOutputStream(image)
                }

                fos?.use {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@EmergencyProfileActivity, "QR Code saved to Gallery", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@EmergencyProfileActivity, "Download failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
