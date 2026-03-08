package com.sriox.vasateysec

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.sriox.vasateysec.databinding.ActivitySafePlacesSelectionBinding

class SafePlacesSelectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySafePlacesSelectionBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySafePlacesSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.cardSafePlaces.setOnClickListener {
            startActivity(Intent(this, SafePlacesActivity::class.java))
        }

        binding.cardPeopleNearMe.setOnClickListener {
            startActivity(Intent(this, NearbyPeopleActivity::class.java))
        }

        binding.cardPlaceReviews.setOnClickListener {
            startActivity(Intent(this, PlaceReviewsActivity::class.java))
        }

        binding.cardAISafety.setOnClickListener {
            startActivity(Intent(this, AiChatActivity::class.java))
        }

        setupBottomNavigation()
    }

    private fun setupBottomNavigation() {
        val navGuardians = findViewById<android.widget.LinearLayout>(R.id.navGuardians)
        val navHistory = findViewById<android.widget.LinearLayout>(R.id.navHistory)
        val sosButton = findViewById<com.google.android.material.card.MaterialCardView>(R.id.sosButton)
        val navGhistory = findViewById<android.widget.LinearLayout>(R.id.navGhistory)
        val navProfile = findViewById<android.widget.LinearLayout>(R.id.navProfile)

        navGuardians?.setOnClickListener {
            startActivity(Intent(this, AddGuardianActivity::class.java))
            finish()
        }

        navHistory?.setOnClickListener {
            startActivity(Intent(this, AlertHistoryActivity::class.java))
            finish()
        }

        sosButton?.setOnClickListener {
            com.sriox.vasateysec.utils.SOSHelper.showSOSConfirmation(this)
        }

        navGhistory?.setOnClickListener {
            startActivity(Intent(this, GuardianMapActivity::class.java))
            finish()
        }

        navProfile?.setOnClickListener {
            startActivity(Intent(this, EditProfileActivity::class.java))
            finish()
        }
    }
}
