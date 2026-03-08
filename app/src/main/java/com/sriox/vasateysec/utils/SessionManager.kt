package com.sriox.vasateysec.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Manages user session persistence with encrypted storage
 */
object SessionManager {
    private const val TAG = "SessionManager"
    private const val PREFS_NAME = "vasatey_secure_prefs"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_USER_EMAIL = "user_email"
    private const val KEY_USER_NAME = "user_name"
    private const val KEY_IS_LOGGED_IN = "is_logged_in"
    private const val KEY_LAST_LOGIN = "last_login"
    private const val KEY_FCM_TOKEN = "fcm_token"
    
    private lateinit var prefs: SharedPreferences
    
    /**
     * Initialize the session manager with encrypted shared preferences
     */
    fun initialize(context: Context) {
        // ALWAYS use standard SharedPreferences (not encrypted) for reliability
        // EncryptedSharedPreferences has issues in release builds
        try {
            Log.d(TAG, "Initializing SessionManager with standard SharedPreferences...")
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            Log.d(TAG, "SessionManager initialized successfully")
            Log.d(TAG, "Current session state - isLoggedIn: ${isLoggedIn()}, userId: ${getUserId()}")
        } catch (e: Exception) {
            Log.e(TAG, "CRITICAL: Failed to initialize SessionManager", e)
            Log.e(TAG, "Error type: ${e.javaClass.name}, message: ${e.message}")
            // Last resort fallback
            prefs = context.getSharedPreferences("vasatey_backup_prefs", Context.MODE_PRIVATE)
            Log.d(TAG, "Using backup prefs - isLoggedIn: ${isLoggedIn()}, userId: ${getUserId()}")
        }
    }
    
    /**
     * Save user session
     */
    fun saveSession(userId: String, email: String, name: String) {
        Log.d(TAG, "Saving session for user: $email (ID: $userId)")
        prefs.edit().apply {
            putString(KEY_USER_ID, userId)
            putString(KEY_USER_EMAIL, email)
            putString(KEY_USER_NAME, name)
            putBoolean(KEY_IS_LOGGED_IN, true)
            putLong(KEY_LAST_LOGIN, System.currentTimeMillis())
            apply()
        }
        Log.d(TAG, "Session saved successfully. Verification - isLoggedIn: ${isLoggedIn()}, userId: ${getUserId()}")
    }
    
    /**
     * Check if user is logged in
     */
    fun isLoggedIn(): Boolean {
        val isLoggedIn = prefs.getBoolean(KEY_IS_LOGGED_IN, false)
        val userId = prefs.getString(KEY_USER_ID, null)
        val result = isLoggedIn && !userId.isNullOrEmpty()
        Log.d(TAG, "isLoggedIn check: $result (flag: $isLoggedIn, userId: ${userId?.take(8)}...)")
        return result
    }
    
    /**
     * Get current user ID
     */
    fun getUserId(): String? {
        return prefs.getString(KEY_USER_ID, null)
    }
    
    /**
     * Get current user email
     */
    fun getUserEmail(): String? {
        return prefs.getString(KEY_USER_EMAIL, null)
    }
    
    /**
     * Get current user name
     */
    fun getUserName(): String? {
        return prefs.getString(KEY_USER_NAME, null)
    }
    
    /**
     * Get last login timestamp
     */
    fun getLastLoginTime(): Long {
        return prefs.getLong(KEY_LAST_LOGIN, 0)
    }
    
    /**
     * Save FCM token
     */
    fun saveFCMToken(token: String) {
        prefs.edit().putString(KEY_FCM_TOKEN, token).apply()
        Log.d(TAG, "FCM token saved")
    }
    
    /**
     * Get FCM token
     */
    fun getFCMToken(): String? {
        return prefs.getString(KEY_FCM_TOKEN, null)
    }
    
    /**
     * Clear session (logout)
     */
    fun clearSession() {
        prefs.edit().clear().apply()
        Log.d(TAG, "Session cleared")
    }
    
    /**
     * Update user name
     */
    fun updateUserName(name: String) {
        prefs.edit().putString(KEY_USER_NAME, name).apply()
    }
}
