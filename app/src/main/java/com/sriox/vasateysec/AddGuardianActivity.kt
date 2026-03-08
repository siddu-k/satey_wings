package com.sriox.vasateysec

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sriox.vasateysec.databinding.ActivityAddGuardianBinding
import com.sriox.vasateysec.databinding.ItemGuardianBinding
import com.sriox.vasateysec.databinding.ItemSmsContactBinding
import com.sriox.vasateysec.models.Guardian
import com.sriox.vasateysec.models.SmsContact
import com.sriox.vasateysec.models.User
import com.sriox.vasateysec.utils.SmsHelper
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.launch

class AddGuardianActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddGuardianBinding
    private lateinit var guardianAdapter: GuardianAdapter
    private lateinit var smsAdapter: SmsContactAdapter
    private val guardians = mutableListOf<Guardian>()
    private val smsContacts = mutableListOf<SmsContact>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddGuardianBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerViews()
        setupBottomNavigation()
        setupTabs()
        
        loadGuardians()
        loadSmsContacts()

        binding.backButton.setOnClickListener { finish() }

        // Add Guardian Logic
        binding.addButton.setOnClickListener {
            val email = binding.emailInput.text.toString().trim()
            if (validateEmail(email)) addGuardian(email)
        }

        // Add SMS Contact Logic
        binding.btnAddSmsContact.setOnClickListener {
            val name = binding.etSmsName.text.toString().trim()
            val phone = binding.etSmsPhone.text.toString().trim()
            if (name.isNotEmpty() && phone.isNotEmpty()) saveSmsContact(name, phone)
        }
    }

    private fun setupTabs() {
        binding.btnGuardianTab.setOnClickListener {
            showTab(isGuardian = true)
        }
        binding.btnSmsTab.setOnClickListener {
            showTab(isGuardian = false)
        }
    }

    private fun showTab(isGuardian: Boolean) {
        binding.layoutGuardians.visibility = if (isGuardian) View.VISIBLE else View.GONE
        binding.layoutSmsContacts.visibility = if (isGuardian) View.GONE else View.VISIBLE
        
        if (isGuardian) {
            binding.btnGuardianTab.setBackgroundColor(getColor(R.color.violet))
            binding.btnGuardianTab.setTextColor(getColor(android.R.color.white))
            binding.btnSmsTab.setBackgroundColor(getColor(android.R.color.transparent))
            binding.btnSmsTab.setTextColor(getColor(R.color.text_secondary))
            updateEmptyState(guardians.isEmpty(), "No email alerts yet")
        } else {
            binding.btnSmsTab.setBackgroundColor(getColor(R.color.golden))
            binding.btnSmsTab.setTextColor(getColor(R.color.background_dark))
            binding.btnGuardianTab.setBackgroundColor(getColor(android.R.color.transparent))
            binding.btnGuardianTab.setTextColor(getColor(R.color.text_secondary))
            updateEmptyState(smsContacts.isEmpty(), "No SMS contacts yet")
        }
    }

    private fun setupRecyclerViews() {
        guardianAdapter = GuardianAdapter(guardians) { removeGuardian(it) }
        binding.guardiansRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.guardiansRecyclerView.adapter = guardianAdapter

        smsAdapter = SmsContactAdapter(smsContacts) { removeSmsContact(it) }
        binding.rvSmsContacts.layoutManager = LinearLayoutManager(this)
        binding.rvSmsContacts.adapter = smsAdapter
    }

    private fun updateEmptyState(isEmpty: Boolean, title: String) {
        binding.emptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.tvEmptyTitle.text = title
    }

    // --- GUARDIAN LOGIC ---
    private fun loadGuardians() {
        lifecycleScope.launch {
            try {
                val currentUser = SupabaseClient.client.auth.currentUserOrNull() ?: return@launch
                val list = SupabaseClient.client.from("guardians").select { filter { eq("user_id", currentUser.id) } }.decodeList<Guardian>()
                guardians.clear()
                guardians.addAll(list)
                guardianAdapter.notifyDataSetChanged()
                if (binding.layoutGuardians.visibility == View.VISIBLE) updateEmptyState(guardians.isEmpty(), "No email alerts yet")
            } catch (e: Exception) { }
        }
    }

    private fun addGuardian(email: String) {
        lifecycleScope.launch {
            try {
                val currentUser = SupabaseClient.client.auth.currentUserOrNull() ?: return@launch
                val guardian = Guardian(user_id = currentUser.id, guardian_email = email, status = "active")
                SupabaseClient.client.from("guardians").insert(guardian)
                binding.emailInput.text?.clear()
                loadGuardians()
            } catch (e: Exception) { }
        }
    }

    private fun removeGuardian(guardian: Guardian) {
        lifecycleScope.launch {
            try {
                SupabaseClient.client.from("guardians").delete { filter { eq("id", guardian.id ?: "") } }
                loadGuardians()
            } catch (e: Exception) { }
        }
    }

    // --- SMS LOGIC ---
    private fun loadSmsContacts() {
        // Load from local storage for instant display
        val cached = SmsHelper.getFromLocalStorage(this)
        if (cached.isNotEmpty()) {
            smsContacts.clear()
            smsContacts.addAll(cached)
            smsAdapter.notifyDataSetChanged()
            if (binding.layoutSmsContacts.visibility == View.VISIBLE) updateEmptyState(false, "")
        }

        lifecycleScope.launch {
            try {
                val currentUser = SupabaseClient.client.auth.currentUserOrNull() ?: return@launch
                val list = SupabaseClient.client.from("sms_contacts").select { filter { eq("user_id", currentUser.id) } }.decodeList<SmsContact>()
                smsContacts.clear()
                smsContacts.addAll(list)
                smsAdapter.notifyDataSetChanged()
                
                // Sync to local hardware storage
                SmsHelper.saveToLocalStorage(this@AddGuardianActivity, list)
                
                if (binding.layoutSmsContacts.visibility == View.VISIBLE) updateEmptyState(smsContacts.isEmpty(), "No SMS contacts yet")
            } catch (e: Exception) {
                Log.e("AddGuardian", "Offline: Using local storage list")
            }
        }
    }

    private fun saveSmsContact(name: String, phone: String) {
        lifecycleScope.launch {
            try {
                val currentUser = SupabaseClient.client.auth.currentUserOrNull() ?: return@launch
                val contact = SmsContact(user_id = currentUser.id, name = name, phone = phone)
                SupabaseClient.client.from("sms_contacts").insert(contact)
                binding.etSmsName.text?.clear()
                binding.etSmsPhone.text?.clear()
                loadSmsContacts()
            } catch (e: Exception) {
                Toast.makeText(this@AddGuardianActivity, "Failed to save", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun removeSmsContact(contact: SmsContact) {
        lifecycleScope.launch {
            try {
                SupabaseClient.client.from("sms_contacts").delete { filter { eq("id", contact.id ?: "") } }
                loadSmsContacts()
                Toast.makeText(this@AddGuardianActivity, "Contact removed", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) { }
        }
    }

    private fun validateEmail(email: String): Boolean {
        return if (email.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.emailInputLayout.error = "Valid email required"; false
        } else {
            binding.emailInputLayout.error = null; true
        }
    }

    private fun setupBottomNavigation() {
        val sosButton = findViewById<com.google.android.material.card.MaterialCardView>(R.id.sosButton)
        sosButton?.setOnClickListener { com.sriox.vasateysec.utils.SOSHelper.showSOSConfirmation(this) }
    }

    // --- ADAPTERS ---
    class GuardianAdapter(private val list: List<Guardian>, private val onDelete: (Guardian) -> Unit) : RecyclerView.Adapter<GuardianAdapter.ViewHolder>() {
        class ViewHolder(val binding: ItemGuardianBinding) : RecyclerView.ViewHolder(binding.root)
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = ViewHolder(ItemGuardianBinding.inflate(LayoutInflater.from(p.context), p, false))
        override fun onBindViewHolder(h: ViewHolder, p: Int) {
            val item = list[p]
            h.binding.guardianName.text = item.guardian_email.substringBefore("@")
            h.binding.guardianEmail.text = item.guardian_email
            h.binding.deleteButton.setOnClickListener { onDelete(item) }
        }
        override fun getItemCount() = list.size
    }

    class SmsContactAdapter(private val list: List<SmsContact>, private val onDelete: (SmsContact) -> Unit) : RecyclerView.Adapter<SmsContactAdapter.ViewHolder>() {
        class ViewHolder(val binding: ItemSmsContactBinding) : RecyclerView.ViewHolder(binding.root)
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = ViewHolder(ItemSmsContactBinding.inflate(LayoutInflater.from(p.context), p, false))
        override fun onBindViewHolder(h: ViewHolder, p: Int) {
            val item = list[p]
            h.binding.smsContactName.text = item.name
            h.binding.smsContactPhone.text = item.phone
            h.binding.btnDeleteSms.setOnClickListener { onDelete(item) }
        }
        override fun getItemCount() = list.size
    }
}
