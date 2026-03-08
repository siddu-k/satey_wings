package com.sriox.vasateysec.models

import kotlinx.serialization.Serializable

@Serializable
data class AlertConfirmation(
    val id: String? = null,
    val alert_id: String,
    val guardian_email: String,
    val guardian_user_id: String? = null,
    val confirmation_status: String = "pending", // pending, confirmed, cancelled, expired
    val confirmed_at: String? = null,
    val cancelled_at: String? = null,
    val expires_at: String? = null,
    val created_at: String? = null
)

@Serializable
data class AlertWithConfirmation(
    val alert: AlertHistory,
    val confirmations: List<AlertConfirmation>
)
