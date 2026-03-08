package com.sriox.vasateysec

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sriox.vasateysec.databinding.ActivityAddGuardianBinding
import com.sriox.vasateysec.models.Guardian
import com.sriox.vasateysec.models.User
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.launch

class AddGuardianActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddGuardianBinding
    private lateinit var guardianAdapter: GuardianAdapter
    private val guardians = mutableListOf<Guardian>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddGuardianBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupBottomNavigation()
        loadGuardians()
        
        // Highlight Guardians nav item
        com.sriox.vasateysec.utils.BottomNavHelper.highlightActiveItem(
            this,
            com.sriox.vasateysec.utils.BottomNavHelper.NavItem.GUARDIANS
        )

        // Back button
        findViewById<android.widget.ImageView>(R.id.backButton)?.setOnClickListener {
            finish()
        }

        binding.addButton.setOnClickListener {
            val email = binding.emailInput.text.toString().trim()
            if (validateEmail(email)) {
                addGuardian(email)
            }
        }
    }
    
    private fun setupBottomNavigation() {
        val navGuardians = findViewById<android.widget.LinearLayout>(R.id.navGuardians)
        val navHistory = findViewById<android.widget.LinearLayout>(R.id.navHistory)
        val sosButton = findViewById<com.google.android.material.card.MaterialCardView>(R.id.sosButton)
        val navGhistory = findViewById<android.widget.LinearLayout>(R.id.navGhistory)
        val navProfile = findViewById<android.widget.LinearLayout>(R.id.navProfile)
        
        navGuardians?.setOnClickListener { /* Already here */ }
        navHistory?.setOnClickListener { 
            val intent = android.content.Intent(this, AlertHistoryActivity::class.java)
            intent.flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
        }
        sosButton?.setOnClickListener {
            com.sriox.vasateysec.utils.SOSHelper.showSOSConfirmation(this)
        }
        navGhistory?.setOnClickListener {
            val intent = android.content.Intent(this, GuardianMapActivity::class.java)
            intent.flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
        }
        navProfile?.setOnClickListener {
            val intent = android.content.Intent(this, EditProfileActivity::class.java)
            intent.flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
        }
    }

    private fun setupRecyclerView() {
        guardianAdapter = GuardianAdapter(guardians) { guardian ->
            removeGuardian(guardian)
        }
        binding.guardiansRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@AddGuardianActivity)
            adapter = guardianAdapter
        }
    }

    private fun validateEmail(email: String): Boolean {
        if (email.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.emailInputLayout.error = "Valid email is required"
            return false
        }
        binding.emailInputLayout.error = null
        return true
    }

    private fun addGuardian(email: String) {
        // binding.progressBar.visibility = View.VISIBLE // Removed from new layout
        binding.addButton.isEnabled = false

        lifecycleScope.launch {
            try {
                val currentUser = SupabaseClient.client.auth.currentUserOrNull()
                if (currentUser == null) {
                    Toast.makeText(this@AddGuardianActivity, "User not logged in", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // Check if guardian already exists
                val existing = try {
                    SupabaseClient.client.from("guardians")
                        .select {
                            filter {
                                eq("user_id", currentUser.id)
                                eq("guardian_email", email)
                            }
                        }
                        .decodeList<Guardian>()
                } catch (e: Exception) {
                    Log.e("AddGuardian", "Error checking existing guardians: ${e.message}", e)
                    emptyList()
                }

                if (existing.isNotEmpty()) {
                    Toast.makeText(this@AddGuardianActivity, "Guardian already added", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // Check if guardian email exists in users table
                val guardianUser = try {
                    SupabaseClient.client.from("users")
                        .select {
                            filter {
                                eq("email", email)
                            }
                        }
                        .decodeSingleOrNull<User>()
                } catch (e: Exception) {
                    Log.d("AddGuardian", "Guardian not found in users table: ${e.message}")
                    null
                }

                // Add new guardian with guardian_user_id if they exist
                val guardian = Guardian(
                    user_id = currentUser.id,
                    guardian_email = email,
                    guardian_user_id = guardianUser?.id,  // Set if guardian already has account
                    status = "active"
                )

                SupabaseClient.client.from("guardians").insert(guardian)
                
                if (guardianUser != null) {
                    Log.d("AddGuardian", "Guardian already has account, linked automatically")
                } else {
                    Log.d("AddGuardian", "Guardian will be linked when they sign up")
                }

                Toast.makeText(this@AddGuardianActivity, "Guardian added successfully", Toast.LENGTH_SHORT).show()
                binding.emailInput.text?.clear()
                loadGuardians()

            } catch (e: Exception) {
                Toast.makeText(this@AddGuardianActivity, "Failed to add guardian: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                // binding.progressBar.visibility = View.GONE // Removed from new layout
                binding.addButton.isEnabled = true
            }
        }
    }
    
    private fun updateEmptyState() {
        if (guardians.isEmpty()) {
            binding.emptyState.visibility = View.VISIBLE
            binding.guardiansRecyclerView.visibility = View.GONE
        } else {
            binding.emptyState.visibility = View.GONE
            binding.guardiansRecyclerView.visibility = View.VISIBLE
        }
    }

    private fun loadGuardians() {
        lifecycleScope.launch {
            try {
                val currentUser = SupabaseClient.client.auth.currentUserOrNull()
                if (currentUser == null) return@launch

                val guardiansList = try {
                    SupabaseClient.client.from("guardians")
                        .select {
                            filter {
                                eq("user_id", currentUser.id)
                            }
                        }
                        .decodeList<Guardian>()
                } catch (e: Exception) {
                    Log.e("AddGuardian", "Error loading guardians: ${e.message}", e)
                    emptyList()
                }

                guardians.clear()
                guardians.addAll(guardiansList)
                guardianAdapter.notifyDataSetChanged()
                updateEmptyState()

            } catch (e: Exception) {
                Toast.makeText(this@AddGuardianActivity, "Failed to load guardians", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun removeGuardian(guardian: Guardian) {
        lifecycleScope.launch {
            try {
                SupabaseClient.client.from("guardians").delete {
                    filter {
                        eq("id", guardian.id ?: "")
                    }
                }

                Toast.makeText(this@AddGuardianActivity, "Guardian removed", Toast.LENGTH_SHORT).show()
                loadGuardians()

            } catch (e: Exception) {
                Toast.makeText(this@AddGuardianActivity, "Failed to remove guardian", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

// Guardian Adapter
class GuardianAdapter(
    private val guardians: List<Guardian>,
    private val onDelete: (Guardian) -> Unit
) : RecyclerView.Adapter<GuardianAdapter.GuardianViewHolder>() {

    class GuardianViewHolder(val binding: com.sriox.vasateysec.databinding.ItemGuardianBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): GuardianViewHolder {
        val binding = com.sriox.vasateysec.databinding.ItemGuardianBinding.inflate(
            android.view.LayoutInflater.from(parent.context), parent, false
        )
        return GuardianViewHolder(binding)
    }

    override fun onBindViewHolder(holder: GuardianViewHolder, position: Int) {
        val guardian = guardians[position]
        // Extract name from email (before @)
        val name = guardian.guardian_email.substringBefore("@").replace(".", " ").split(" ")
            .joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
        holder.binding.guardianName.text = name
        holder.binding.guardianEmail.text = guardian.guardian_email
        holder.binding.deleteButton.setOnClickListener {
            onDelete(guardian)
        }
    }

    override fun getItemCount() = guardians.size
}
