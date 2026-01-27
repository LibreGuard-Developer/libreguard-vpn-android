package net.libreguard.vpn.util

import android.content.Context
import android.provider.Settings
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest

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

    /**
     * Returns the stable raw device identifier (Android ID). Cached after first read.
     */
    fun getRawDeviceId(): String {
        val cached = prefs.getString(KEY_DEVICE_ID_RAW, null)
        if (!cached.isNullOrBlank()) return cached
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown-device"
        prefs.edit().putString(KEY_DEVICE_ID_RAW, androidId).apply()
        return androidId
    }

    /**
     * Returns the SHA-256 lowercase hex of the raw device ID. Cached once computed.
     */
    fun getDeviceIdHash(): String {
        val cachedHash = prefs.getString(KEY_DEVICE_ID_HASH, null)
        if (!cachedHash.isNullOrBlank()) return cachedHash
        val rawId = getRawDeviceId()
        val hashed = sha256Hex(rawId)
        prefs.edit().putString(KEY_DEVICE_ID_HASH, hashed).apply()
        return hashed
    }

    // Backwards-compatible helper: previously returned raw ID; now returns hash for callers still using this name.
    fun getDeviceId(): String = getDeviceIdHash()

    private fun sha256Hex(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString(separator = "") { "%02x".format(it) }
    }

    companion object {
        private const val KEY_DEVICE_ID_RAW = "device_id_raw"
        private const val KEY_DEVICE_ID_HASH = "device_id_hash"
    }
}
