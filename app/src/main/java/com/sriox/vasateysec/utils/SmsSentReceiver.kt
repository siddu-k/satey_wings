package com.sriox.vasateysec.utils

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.util.Log

class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val phone = intent?.getStringExtra("phone") ?: "unknown"
        val resultCode = resultCode
        
        when (resultCode) {
            Activity.RESULT_OK -> {
                Log.d("SmsSentReceiver", "✅ SUCCESS: SMS actually sent by system to $phone")
            }
            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> {
                Log.e("SmsSentReceiver", "❌ FAILURE: Generic failure for $phone. (Check balance/SIM)")
            }
            SmsManager.RESULT_ERROR_NO_SERVICE -> {
                Log.e("SmsSentReceiver", "❌ FAILURE: No service for $phone")
            }
            SmsManager.RESULT_ERROR_NULL_PDU -> {
                Log.e("SmsSentReceiver", "❌ FAILURE: Null PDU for $phone")
            }
            SmsManager.RESULT_ERROR_RADIO_OFF -> {
                Log.e("SmsSentReceiver", "❌ FAILURE: Radio off for $phone")
            }
            else -> {
                Log.e("SmsSentReceiver", "❌ FAILURE: Error code $resultCode for $phone")
            }
        }
    }
}
