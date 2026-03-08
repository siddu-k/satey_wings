package com.sriox.vasateysec

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sriox.vasateysec.databinding.ActivityLoginBinding
import com.sriox.vasateysec.utils.SessionManager
import com.sriox.vasateysec.utils.FCMTokenManager
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.providers.builtin.Email
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val TAG = "LoginActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Don't auto-check here - SplashActivity handles that
        // This prevents the flashing login screen issue

        binding.loginButton.setOnClickListener {
            val email = binding.emailInput.text.toString().trim()
            val password = binding.passwordInput.text.toString()

            if (validateInputs(email, password)) {
                login(email, password)
            }
        }

        binding.signupPrompt.setOnClickListener {
            startActivity(Intent(this, SignupActivity::class.java))
            finish()
        }
    }

    private fun validateInputs(email: String, password: String): Boolean {
        if (email.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.emailInputLayout.error = "Valid email is required"
            return false
        }
        binding.emailInputLayout.error = null

        if (password.isEmpty()) {
            binding.passwordInputLayout.error = "Password is required"
            return false
        }
        binding.passwordInputLayout.error = null

        return true
    }

    private fun login(email: String, password: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.loginButton.isEnabled = false

        lifecycleScope.launch {
            try {
                // Sign in with Supabase
                SupabaseClient.client.auth.signInWith(Email) {
                    this.email = email
                    this.password = password
                }

                // Get user info
                val currentUser = SupabaseClient.client.auth.currentUserOrNull()
                    ?: throw Exception("Login succeeded but no user found")
                
                Log.d(TAG, "User logged in: ${currentUser.id}")
                
                // Fetch user profile to get name
                val userProfile = try {
                    SupabaseClient.client.from("users")
                        .select {
                            filter {
                                eq("id", currentUser.id)
                            }
                        }
                        .decodeSingle<com.sriox.vasateysec.models.UserProfile>()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to fetch user profile: ${e.message}")
                    null
                }
                
                val userName = userProfile?.name ?: email.substringBefore("@")
                
                // Save session to SessionManager
                SessionManager.saveSession(
                    userId = currentUser.id,
                    email = email,
                    name = userName
                )
                
                Log.d(TAG, "Session saved successfully")
                
                // CRITICAL: Update FCM token immediately after login
                // This ensures the latest token is saved when switching between debug/release APKs
                Log.d(TAG, "Initializing FCM token after login...")
                FCMTokenManager.initializeFCM(this@LoginActivity)

                Toast.makeText(
                    this@LoginActivity,
                    "Welcome back, $userName!",
                    Toast.LENGTH_SHORT
                ).show()

                navigateToMain()

            } catch (e: Exception) {
                Log.e(TAG, "Login failed", e)
                Toast.makeText(
                    this@LoginActivity,
                    "Login failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                binding.progressBar.visibility = View.GONE
                binding.loginButton.isEnabled = true
            }
        }
    }

    private fun navigateToMain() {
        val intent = Intent(this, HomeActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}
