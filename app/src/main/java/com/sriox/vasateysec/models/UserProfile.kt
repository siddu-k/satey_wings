package com.sriox.vasateysec.models

import kotlinx.serialization.Serializable

@Serializable
data class UserProfile(
    val id: String,
    val name: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val wake_word: String? = null,
    val cancel_password: String? = null,
    val last_latitude: Double? = null,
    val last_longitude: Double? = null,
    val last_location_updated_at: String? = null,
    val is_auto_location_enabled: Boolean = false,
    val gemini_api_key: String? = null
)
