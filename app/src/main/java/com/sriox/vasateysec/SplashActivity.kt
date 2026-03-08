package com.sriox.vasateysec

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sriox.vasateysec.utils.SessionManager
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.user.UserSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {
    
    private val TAG = "SplashActivity"
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        
        // Validate session and navigate
        validateSessionAndNavigate()
    }
    
    private fun validateSessionAndNavigate() {
        lifecycleScope.launch {
            try {
                // Show splash for minimum time for better UX
                delay(1000)
                
                Log.d(TAG, "Checking session...")
                
                // Check if we have a saved session
                val hasLocalSession = SessionManager.isLoggedIn()
                Log.d(TAG, "Local session exists: $hasLocalSession")
                
                if (hasLocalSession) {
                    // We have a local session - TRUST IT and proceed to home
                    // Let Supabase restore session in background
                    Log.d(TAG, "Local session found, navigating to home")
                    
                    // Try to restore Supabase session in background (non-blocking)
                    launch {
                        try {
                            delay(500) // Give Supabase time to auto-load
                            val currentUser = SupabaseClient.client.auth.currentUserOrNull()
                            if (currentUser != null) {
                                Log.d(TAG, "Supabase session restored for: ${currentUser.id}")
                            } else {
                                Log.w(TAG, "Supabase session not restored yet, will retry later")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Background session restore failed: ${e.message}")
                        }
                    }
                    
                    navigateToHome()
                } else {
                    Log.d(TAG, "No local session, navigating to login")
                    navigateToLogin()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error during session validation", e)
                // Even on error, check if we have local session
                if (SessionManager.isLoggedIn()) {
                    navigateToHome()
                } else {
                    navigateToLogin()
                }
            }
        }
    }
    
    
    private fun navigateToHome() {
        val intent = Intent(this, HomeActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
    
    private fun navigateToLogin() {
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}
