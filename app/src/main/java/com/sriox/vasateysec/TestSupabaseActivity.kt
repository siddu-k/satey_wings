package com.sriox.vasateysec

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.launch

class TestSupabaseActivity : AppCompatActivity() {
    
    private lateinit var resultText: TextView
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Create simple UI
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
        }
        
        val button = Button(this).apply {
            text = "Test Supabase Connection"
            setOnClickListener { testSupabase() }
        }
        
        resultText = TextView(this).apply {
            text = "Click button to test"
            textSize = 16f
        }
        
        layout.addView(button)
        layout.addView(resultText)
        setContentView(layout)
    }
    
    private fun testSupabase() {
        lifecycleScope.launch {
            try {
                resultText.text = "Testing...\n"
                Log.d("TEST", "=== SUPABASE TEST START ===")
                
                // Test 1: Check client
                resultText.append("Supabase URL: ${SupabaseClient.client.supabaseUrl}\n")
                Log.d("TEST", "Supabase URL: ${SupabaseClient.client.supabaseUrl}")
                
                // Test 2: Try to query guardians table
                resultText.append("Querying guardians table...\n")
                Log.d("TEST", "Querying guardians...")
                
                val response = SupabaseClient.client.from("guardians").select()
                resultText.append("Query executed!\n")
                Log.d("TEST", "Query executed")
                
                // Test 3: Try to decode as raw string first
                try {
                    val rawData = response.data
                    resultText.append("Raw data: $rawData\n")
                    Log.d("TEST", "Raw data: $rawData")
                    
                    // Test 4: Try to decode as list
                    val guardians = response.decodeList<com.sriox.vasateysec.models.Guardian>()
                    resultText.append("SUCCESS! Found ${guardians.size} guardians\n")
                    Log.d("TEST", "SUCCESS! Found ${guardians.size} guardians")
                    
                    guardians.forEach { g ->
                        resultText.append("- ${g.guardian_email}\n")
                        Log.d("TEST", "Guardian: ${g.guardian_email}")
                    }
                    
                } catch (e: Exception) {
                    resultText.append("DECODE ERROR: ${e.message}\n")
                    Log.e("TEST", "Decode error", e)
                }
                
            } catch (e: Exception) {
                resultText.append("ERROR: ${e.message}\n")
                Log.e("TEST", "Test failed", e)
            }
        }
    }
}
