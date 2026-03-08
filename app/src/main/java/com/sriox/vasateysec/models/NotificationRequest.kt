package com.sriox.vasateysec.models

import kotlinx.serialization.Serializable

@Serializable
data class NotificationRequest(
    val token: String,
    val title: String,
    val body: String,
    val fullName: String,
    val email: String,
    val phoneNumber: String,
    val lastKnownLatitude: Double? = null,
    val lastKnownLongitude: Double? = null,
    val isSelfAlert: Boolean = false
) {
    @Serializable
    data class TokenInfo(
        val token: String,
        val email: String,
        val name: String
    )
}
