package com.sriox.vasateysec

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sriox.vasateysec.databinding.ActivityAddSmsContactBinding
import com.sriox.vasateysec.models.SmsContact
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.launch

class AddSmsContactActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddSmsContactBinding
    private lateinit var smsAdapter: SmsContactAdapter
    private val contactList = mutableListOf<SmsContact>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddSmsContactBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        loadSmsContacts()

        binding.btnBack.setOnClickListener { finish() }

        binding.btnAddSmsContact.setOnClickListener {
            val name = binding.etSmsName.text.toString().trim()
            val phone = binding.etSmsPhone.text.toString().trim()

            if (name.isNotEmpty() && phone.isNotEmpty()) {
                saveSmsContact(name, phone)
            } else {
                Toast.makeText(this, "Please enter name and phone", Toast.LENGTH_SHORT).show()
            }
        }

        setupBottomNavigation()
    }

    private fun setupRecyclerView() {
        smsAdapter = SmsContactAdapter(contactList) { contact ->
            deleteSmsContact(contact)
        }
        binding.rvSmsContacts.apply {
            layoutManager = LinearLayoutManager(this@AddSmsContactActivity)
            adapter = smsAdapter
        }
    }

    private fun loadSmsContacts() {
        lifecycleScope.launch {
            try {
                val currentUser = SupabaseClient.client.auth.currentUserOrNull() ?: return@launch
                val contacts = SupabaseClient.client.from("sms_contacts")
                    .select { filter { eq("user_id", currentUser.id) } }
                    .decodeList<SmsContact>()

                contactList.clear()
                contactList.addAll(contacts)
                smsAdapter.notifyDataSetChanged()
            } catch (e: Exception) {
                android.util.Log.e("SmsContact", "Load error: ${e.message}")
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
                Toast.makeText(this@AddSmsContactActivity, "Contact Added", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@AddSmsContactActivity, "Failed to save", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun deleteSmsContact(contact: SmsContact) {
        lifecycleScope.launch {
            try {
                SupabaseClient.client.from("sms_contacts").delete {
                    filter { eq("id", contact.id ?: "") }
                }
                loadSmsContacts()
            } catch (e: Exception) { }
        }
    }

    private fun setupBottomNavigation() {
        val navGuardians = findViewById<android.widget.LinearLayout>(R.id.navGuardians)
        val navHistory = findViewById<android.widget.LinearLayout>(R.id.navHistory)
        val sosButton = findViewById<com.google.android.material.card.MaterialCardView>(R.id.sosButton)
        val navGhistory = findViewById<android.widget.LinearLayout>(R.id.navGhistory)
        val navProfile = findViewById<android.widget.LinearLayout>(R.id.navProfile)

        navGuardians?.setOnClickListener {
            val intent = android.content.Intent(this, AddGuardianActivity::class.java)
            startActivity(intent)
            finish()
        }
        navHistory?.setOnClickListener {
            val intent = android.content.Intent(this, AlertHistoryActivity::class.java)
            startActivity(intent)
            finish()
        }
        sosButton?.setOnClickListener {
            com.sriox.vasateysec.utils.SOSHelper.showSOSConfirmation(this)
        }
        navGhistory?.setOnClickListener {
            val intent = android.content.Intent(this, GuardianMapActivity::class.java)
            startActivity(intent)
            finish()
        }
        navProfile?.setOnClickListener {
            val intent = android.content.Intent(this, EditProfileActivity::class.java)
            startActivity(intent)
            finish()
        }
    }

    class SmsContactAdapter(private val list: List<SmsContact>, private val onDelete: (SmsContact) -> Unit) :
        RecyclerView.Adapter<SmsContactAdapter.ViewHolder>() {
        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(android.R.id.text1)
            val phone: TextView = view.findViewById(android.R.id.text2)
            val deleteBtn: android.widget.ImageView = android.widget.ImageView(view.context).apply {
                setImageResource(android.R.drawable.ic_menu_delete)
            }
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_2, parent, false)
            return ViewHolder(view)
        }
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            holder.name.text = item.name
            holder.phone.text = item.phone
            holder.itemView.setOnLongClickListener { onDelete(item); true }
        }
        override fun getItemCount() = list.size
    }
}
