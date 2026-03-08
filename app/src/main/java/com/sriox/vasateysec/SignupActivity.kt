package com.sriox.vasateysec

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sriox.vasateysec.databinding.ActivitySignupBinding
import com.sriox.vasateysec.utils.SessionManager
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.providers.builtin.Email
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import java.net.SocketTimeoutException
import java.net.UnknownHostException

@Serializable
data class UserProfile(
    val id: String,
    val name: String,
    val email: String,
    val phone: String
)

class SignupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignupBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.signupButton.setOnClickListener {
            val name = binding.nameInput.text.toString().trim()
            val email = binding.emailInput.text.toString().trim()
            val phone = binding.phoneInput.text.toString().trim()
            val password = binding.passwordInput.text.toString()

            if (validateInputs(name, email, phone, password)) {
                signUp(name, email, phone, password)
            }
        }

        binding.loginPrompt.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    private fun validateInputs(name: String, email: String, phone: String, password: String): Boolean {
        if (name.isEmpty()) {
            binding.nameInputLayout.error = "Name is required"
            return false
        }
        binding.nameInputLayout.error = null

        if (email.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.emailInputLayout.error = "Valid email is required"
            return false
        }
        binding.emailInputLayout.error = null

        if (phone.isEmpty()) {
            binding.phoneInputLayout.error = "Phone number is required"
            return false
        }
        binding.phoneInputLayout.error = null

        if (password.length < 6) {
            binding.passwordInputLayout.error = "Password must be at least 6 characters"
            return false
        }
        binding.passwordInputLayout.error = null

        return true
    }

    private fun signUp(name: String, email: String, phone: String, password: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.signupButton.isEnabled = false

        lifecycleScope.launch {
            var signupSuccess = false
            var errorMessage = ""
            
            try {
                // Sign up with Supabase Auth with timeout
                withTimeout(45000L) { // 45 second timeout
                    SupabaseClient.client.auth.signUpWith(Email) {
                        this.email = email
                        this.password = password
                    }
                }

                // Get the user ID
                val currentUser = SupabaseClient.client.auth.currentUserOrNull()
                    ?: throw Exception("Failed to get user ID after signup")

                // Store additional user data in the database with retry
                var retryCount = 0
                val maxRetries = 3
                var dataStored = false
                
                while (!dataStored && retryCount < maxRetries) {
                    try {
                        withTimeout(30000L) { // 30 second timeout for database insert
                            SupabaseClient.client.from("users").upsert(
                                mapOf(
                                    "id" to currentUser.id,
                                    "name" to name,
                                    "email" to email,
                                    "phone" to phone
                                )
                            )
                        }
                        dataStored = true
                    } catch (e: Exception) {
                        retryCount++
                        if (retryCount < maxRetries) {
                            delay(2000L) // Wait 2 seconds before retry
                        } else {
                            throw e
                        }
                    }
                }

                signupSuccess = true
                
                runOnUiThread {
                    Toast.makeText(
                        this@SignupActivity,
                        "Sign up successful! Please check your email to verify your account.",
                        Toast.LENGTH_LONG
                    ).show()
                }

            } catch (e: SocketTimeoutException) {
                errorMessage = "Connection timeout. Please check your internet connection and try again."
            } catch (e: UnknownHostException) {
                errorMessage = "No internet connection. Please check your network and try again."
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                errorMessage = "Request timed out. Please try again with a stable internet connection."
            } catch (e: Exception) {
                errorMessage = when {
                    e.message?.contains("already registered", ignoreCase = true) == true -> 
                        "This email is already registered. Please login instead."
                    e.message?.contains("network", ignoreCase = true) == true -> 
                        "Network error. Please check your connection and try again."
                    else -> "Sign up failed: ${e.message}"
                }
            } finally {
                binding.progressBar.visibility = View.GONE
                binding.signupButton.isEnabled = true
                
                // Navigate to login if signup was successful
                if (signupSuccess) {
                    // Small delay to ensure toast is visible
                    delay(1500L)
                    val intent = Intent(this@SignupActivity, LoginActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                    finish()
                } else if (errorMessage.isNotEmpty()) {
                    runOnUiThread {
                        Toast.makeText(
                            this@SignupActivity,
                            errorMessage,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }
}
