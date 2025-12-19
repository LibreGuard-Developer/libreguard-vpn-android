package net.libreguard.vpn.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class TokenManager(context: Context) {
    private val sharedPreferences: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        sharedPreferences = EncryptedSharedPreferences.create(
            context,
            "secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun saveTokens(accessToken: String, refreshToken: String) {
        sharedPreferences.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .apply()
    }

    fun getAccessToken(): String? {
        return sharedPreferences.getString(KEY_ACCESS_TOKEN, null)
    }

    fun getRefreshToken(): String? {
        return sharedPreferences.getString(KEY_REFRESH_TOKEN, null)
    }

    fun clearTokens() {
        sharedPreferences.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .apply()
    }

    fun saveLastTokenCheckTime(time: Long) {
        sharedPreferences.edit()
            .putLong(KEY_LAST_TOKEN_CHECK, time)
            .apply()
    }

    fun getLastTokenCheckTime(): Long {
        return sharedPreferences.getLong(KEY_LAST_TOKEN_CHECK, 0)
    }

    fun shouldCheckTokenValidity(): Boolean {
        val lastCheck = getLastTokenCheckTime()
        val now = System.currentTimeMillis()
        // Check token every 5 minutes (300000 ms)
        return (now - lastCheck) >= TOKEN_CHECK_INTERVAL_MS
    }

    companion object {
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_LAST_TOKEN_CHECK = "last_token_check"
        private const val TOKEN_CHECK_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes
    }
}
