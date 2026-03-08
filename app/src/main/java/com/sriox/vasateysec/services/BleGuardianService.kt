package com.sriox.vasateysec.services

import android.app.*
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.*
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sriox.vasateysec.R
import com.sriox.vasateysec.utils.AlertManager
import com.sriox.vasateysec.utils.CameraManager
import com.sriox.vasateysec.utils.LocationManager
import kotlinx.coroutines.*
import java.util.*

class BleGuardianService : Service() {

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null
    
    private val serviceUuid = UUID.fromString("00001234-0000-1000-8000-00805f9b34fb")
    private val charUuid = UUID.fromString("00005678-0000-1000-8000-00805f9b34fb")
    
    private var isScanning = false
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    override fun onCreate() {
        super.onCreate()
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bleScanner = bluetoothAdapter?.bluetoothLeScanner
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(101, createNotification("Monitoring safety hardware..."))
        startScanning()
        return START_STICKY
    }

    private fun startScanning() {
        if (isScanning || bleScanner == null) return
        
        val filter = ScanFilter.Builder().setDeviceName("ESP32_Safety_Watch").build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
        
        bleScanner?.startScan(listOf(filter), settings, scanCallback)
        isScanning = true
        Log.d("BleGuardian", "Started scanning for safety watch...")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (isScanning) {
                stopScanning()
                result.device.connectGatt(this@BleGuardianService, true, gattCallback)
            }
        }
    }

    private fun stopScanning() {
        bleScanner?.stopScan(scanCallback)
        isScanning = false
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d("BleGuardian", "Connected to Watch. Discovering services...")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d("BleGuardian", "Disconnected from Watch. Retrying...")
                startScanning()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(serviceUuid)
            val characteristic = service?.getCharacteristic(charUuid)
            if (characteristic != null) {
                gatt.setCharacteristicNotification(characteristic, true)
                val descriptor = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
                Log.d("BleGuardian", "Ready to receive SOS signals")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.getStringValue(0)
            if (value == "SOS") {
                Log.d("BleGuardian", "🚨 HARDWARE SOS RECEIVED!")
                triggerEmergencyAlert()
            }
        }
    }

    private fun triggerEmergencyAlert() {
        scope.launch {
            try {
                val location = LocationManager.getCurrentLocation(this@BleGuardianService)
                val photos = CameraManager.captureEmergencyPhotos(this@BleGuardianService)
                AlertManager.sendEmergencyAlert(
                    context = this@BleGuardianService,
                    latitude = location?.latitude,
                    longitude = location?.longitude,
                    locationAccuracy = location?.accuracy,
                    frontPhotoFile = photos.frontPhoto,
                    backPhotoFile = photos.backPhoto
                )
            } catch (e: Exception) {
                Log.e("BleGuardian", "Hardware Alert Failed: ${e.message}")
            }
        }
    }

    private fun createNotification(text: String): Notification {
        val channelId = "BLE_SERVICE_CHANNEL"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Hardware Safety Service", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Hardware Protection")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScanning()
        bluetoothGatt?.close()
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
