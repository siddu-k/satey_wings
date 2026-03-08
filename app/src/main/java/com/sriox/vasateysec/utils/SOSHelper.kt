package com.sriox.vasateysec.utils

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.sriox.vasateysec.SupabaseClient
import kotlinx.coroutines.launch

object SOSHelper {
    
    fun showSOSConfirmation(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle("🚨 Emergency SOS Alert")
            .setMessage("Are you sure you want to send an emergency alert to all your guardians?\n\nThis will:\n• Send your location\n• Capture photos\n• Notify all guardians immediately")
            .setPositiveButton("Send Alert") { dialog, _ ->
                dialog.dismiss()
                triggerEmergencyAlert(activity)
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(true)
            .show()
    }
    
    private fun triggerEmergencyAlert(activity: Activity) {
        if (activity !is LifecycleOwner) {
            Toast.makeText(activity, "Unable to trigger alert", Toast.LENGTH_SHORT).show()
            return
        }
        
        (activity as LifecycleOwner).lifecycleScope.launch {
            try {
                // Get current location
                val locationManager = activity.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
                
                if (ActivityCompat.checkSelfPermission(
                        activity,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    Toast.makeText(activity, "Location permission required", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                
                // Try to get last known location
                val location = locationManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                    ?: locationManager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                
                val latitude = location?.latitude
                val longitude = location?.longitude
                val accuracy = location?.accuracy
                
                android.util.Log.d("SOSHelper", "Manual SOS: lat=$latitude, lon=$longitude, accuracy=$accuracy")
                
                // Show progress
                Toast.makeText(activity, "📸 Capturing photos and sending alert...", Toast.LENGTH_SHORT).show()
                
                // Send emergency alert using AlertManager
                val result = AlertManager.sendEmergencyAlert(
                    context = activity,
                    latitude = latitude,
                    longitude = longitude,
                    locationAccuracy = accuracy
                )
                
                if (result.isSuccess) {
                    Toast.makeText(
                        activity,
                        "✅ Emergency alert sent to all guardians!",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        activity,
                        "❌ Failed to send alert: ${result.exceptionOrNull()?.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("SOSHelper", "Manual SOS failed", e)
                Toast.makeText(
                    activity,
                    "❌ Error: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
