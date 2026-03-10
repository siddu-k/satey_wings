package com.sriox.vasateysec

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sriox.vasateysec.databinding.ActivityDeviceScanBinding
import com.sriox.vasateysec.services.BleGuardianService

class BleDeviceScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeviceScanBinding
    private var bluetoothAdapter: BluetoothAdapter? = null
    private val deviceList = mutableListOf<BluetoothDevice>()
    private lateinit var deviceAdapter: DeviceAdapter
    private var isScanning = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        setupRecyclerView()
        binding.btnBack.setOnClickListener { finish() }
        binding.btnRescan.setOnClickListener { startScan() }

        startScan()
    }

    private fun setupRecyclerView() {
        deviceAdapter = DeviceAdapter(deviceList) { device ->
            connectToDevice(device)
        }
        binding.rvDevices.layoutManager = LinearLayoutManager(this)
        binding.rvDevices.adapter = deviceAdapter
    }

    private fun startScan() {
        if (isScanning) return
        
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Permission required", Toast.LENGTH_SHORT).show()
            return
        }

        deviceList.clear()
        deviceAdapter.notifyDataSetChanged()
        
        isScanning = true
        binding.pbScanning.visibility = View.VISIBLE
        binding.tvScanStatus.text = "Scanning..."
        
        bluetoothAdapter?.bluetoothLeScanner?.startScan(scanCallback)
        
        handler.postDelayed({
            stopScan()
        }, 10000)
    }

    private fun stopScan() {
        if (!isScanning) return
        isScanning = false
        binding.pbScanning.visibility = View.GONE
        binding.tvScanStatus.text = "Scan complete"
        
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            if (!deviceList.any { it.address == device.address }) {
                deviceList.add(device)
                deviceAdapter.notifyItemInserted(deviceList.size - 1)
            }
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        stopScan()
        val prefs = getSharedPreferences("vasatey_settings", MODE_PRIVATE)
        prefs.edit().putString("last_device_address", device.address).apply()
        prefs.edit().putBoolean("hardware_sos_enabled", true).apply()

        val serviceIntent = Intent(this, BleGuardianService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        
        Toast.makeText(this, "Connecting to ${device.name ?: "Unknown"}", Toast.LENGTH_SHORT).show()
        finish()
    }

    inner class DeviceAdapter(private val devices: List<BluetoothDevice>, private val onClick: (BluetoothDevice) -> Unit) :
        RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(android.R.id.text1)
            val address: TextView = view.findViewById(android.R.id.text2)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_2, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val device = devices[position]
            if (ActivityCompat.checkSelfPermission(this@BleDeviceScanActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                holder.name.text = device.name ?: "Unknown Device"
            } else {
                holder.name.text = "Unknown Device"
            }
            holder.address.text = device.address
            holder.itemView.setOnClickListener { onClick(device) }
        }

        override fun getItemCount() = devices.size
    }

    override fun onDestroy() {
        stopScan()
        super.onDestroy()
    }
}
