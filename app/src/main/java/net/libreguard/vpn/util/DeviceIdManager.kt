package net.libreguard.vpn.util

import android.content.Context
import android.provider.Settings
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Generates and caches a consistent device identifier for device limit enforcement.
 */
class DeviceIdManager(private val context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "device_prefs",
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun getDeviceId(): String {
        val cached = prefs.getString(KEY_DEVICE_ID, null)
        if (!cached.isNullOrBlank()) {
            return cached
        }
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown-device"
        prefs.edit().putString(KEY_DEVICE_ID, androidId).apply()
        return androidId
    }

    companion object {
        private const val KEY_DEVICE_ID = "device_id"
    }
}
