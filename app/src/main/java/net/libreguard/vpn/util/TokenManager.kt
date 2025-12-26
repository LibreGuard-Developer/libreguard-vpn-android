package net.libreguard.vpn.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class TokenManager(context: Context) {
    private val sharedPreferences: SharedPreferences
    private val _deviceMetadataFlow = MutableStateFlow(Pair(0, 0))
    val deviceMetadataFlow: StateFlow<Pair<Int, Int>> = _deviceMetadataFlow

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

        _deviceMetadataFlow.value = getDeviceMetadata()
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

    // ===== DEVICE BINDING METHODS =====
    /**
     * Saves the device ID associated with current authentication.
     * Used for enforcing device limits and preventing token reuse.
     */
    fun saveDeviceId(deviceId: String) {
        sharedPreferences.edit()
            .putString(KEY_DEVICE_ID, deviceId)
            .apply()
    }

    /**
     * Retrieves the device ID bound to current authentication.
     * Returns null if no device has been bound (should not happen in normal flow).
     */
    fun getDeviceId(): String? {
        return sharedPreferences.getString(KEY_DEVICE_ID, null)
    }

    /**
     * Saves device metadata (active/max device counts) from auth response.
     * Useful for displaying device limit status to user in UI.
     */
    fun saveDeviceMetadata(activeDevices: Int, maxDevices: Int) {
        sharedPreferences.edit()
            .putInt(KEY_ACTIVE_DEVICES, activeDevices)
            .putInt(KEY_MAX_DEVICES, maxDevices)
            .apply()

        _deviceMetadataFlow.value = Pair(activeDevices, maxDevices)
    }

    /**
     * Retrieves cached device metadata as Pair<activeDevices, maxDevices>.
     * Returns Pair(0, 0) if not set.
     */
    fun getDeviceMetadata(): Pair<Int, Int> {
        val activeDevices = sharedPreferences.getInt(KEY_ACTIVE_DEVICES, 0)
        val maxDevices = sharedPreferences.getInt(KEY_MAX_DEVICES, 0)
        return Pair(activeDevices, maxDevices)
    }

    /**
     * Clears all device-related data during logout.
     * Called when device is unregistered or token is revoked.
     */
    fun clearDeviceData() {
        sharedPreferences.edit()
            .remove(KEY_DEVICE_ID)
            .remove(KEY_ACTIVE_DEVICES)
            .remove(KEY_MAX_DEVICES)
            .apply()

        _deviceMetadataFlow.value = Pair(0, 0)
    }

    companion object {
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_LAST_TOKEN_CHECK = "last_token_check"
        private const val TOKEN_CHECK_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_ACTIVE_DEVICES = "active_devices"
        private const val KEY_MAX_DEVICES = "max_devices"
    }
}
