package net.libreguard.vpn.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

sealed class TokenRefreshResult {
    data object Skipped : TokenRefreshResult()
    data class Success(val accessToken: String) : TokenRefreshResult()
    data class RateLimited(val retryAfterSeconds: Int?) : TokenRefreshResult()
    data class Failure(val statusCode: Int, val errorCode: String? = null, val message: String? = null) : TokenRefreshResult()
    data class ExceptionFailure(val throwable: Throwable) : TokenRefreshResult()
}

class TokenManager(context: Context) {
    private val appContext = context.applicationContext
    private val sharedPreferences: SharedPreferences
    private val _deviceMetadataFlow = MutableStateFlow(Pair(0, 0))
    val deviceMetadataFlow: StateFlow<Pair<Int, Int>> = _deviceMetadataFlow

    init {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        sharedPreferences = EncryptedSharedPreferences.create(
            appContext,
            "secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        _deviceMetadataFlow.value = getDeviceMetadata()

        // Ensure device id is available for refresh requests (backend now requires it).
        ensureDeviceIdPersisted()
    }

    fun saveTokens(accessToken: String, refreshToken: String) {
        sharedPreferences.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .apply()
    }

    fun getAccessToken(): String? {
        val token = sharedPreferences.getString(KEY_ACCESS_TOKEN, null)
        android.util.Log.d("TokenManager", "========== GET ACCESS TOKEN ==========")
        android.util.Log.d("TokenManager", "Token exists: ${token != null}")
        android.util.Log.d("TokenManager", "Token is blank: ${token.isNullOrBlank()}")
        if (token == null) {
            android.util.Log.e("TokenManager", "TOKEN IS NULL - User may need to login")
        }
        android.util.Log.d("TokenManager", "======================================")
        return token
    }

    fun getRefreshToken(): String? {
        return sharedPreferences.getString(KEY_REFRESH_TOKEN, null)
    }

    /**
     * Returns a stable deviceId (SHA-256 hash). Ensures it is persisted.
     * Backend requires DeviceId for refresh and 2FA token issuance.
     */
    fun requireDeviceId(): String {
        val existing = getDeviceId()
        if (!existing.isNullOrBlank()) return existing
        ensureDeviceIdPersisted()
        return getDeviceId() ?: DeviceIdManager(appContext).getDeviceIdHash().also { saveDeviceId(it) }
    }

    private fun ensureDeviceIdPersisted() {
        val current = getDeviceId()
        if (!current.isNullOrBlank()) return
        val hashedId = DeviceIdManager(appContext).getDeviceIdHash()
        saveDeviceId(hashedId)
    }

    fun getAppVersion(): String? {
        return try {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Check if the stored access token is expired by examining JWT payload
     * Returns true if token is expired or invalid, false if still valid
     */
    fun isTokenExpired(): Boolean {
        val token = getAccessToken() ?: return true

        return try {
            // JWT tokens have format: header.payload.signature
            val parts = token.split(".")
            if (parts.size != 3) return true

            // Decode the payload (second part)
            val payload = parts[1]
            val decodedBytes = android.util.Base64.decode(
                payload,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING
            )
            val payloadJson = String(decodedBytes)

            // Parse JSON to extract expiry timestamp
            val jsonObj = org.json.JSONObject(payloadJson)
            val exp = jsonObj.optLong("exp", 0)

            if (exp == 0L) {
                // No expiry field - assume token is valid
                return false
            }

            // Check if expired (exp is in seconds, currentTimeMillis is in ms)
            val currentTimeSec = System.currentTimeMillis() / 1000
            val isExpired = currentTimeSec >= exp

            if (isExpired) {
                android.util.Log.d("TokenManager", "Token is expired: exp=$exp, now=$currentTimeSec")
            }

            isExpired
        } catch (e: Exception) {
            android.util.Log.w("TokenManager", "Failed to check token expiry: ${e.message}")
            // If we can't parse, assume token might be invalid but don't block
            false
        }
    }

    /**
     * Check if token will expire within the specified number of seconds
     * Useful for proactive token refresh
     */
    fun isTokenExpiringWithin(seconds: Long): Boolean {
        val token = getAccessToken() ?: return true

        return try {
            val parts = token.split(".")
            if (parts.size != 3) return true

            val payload = parts[1]
            val decodedBytes = android.util.Base64.decode(
                payload,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING
            )
            val payloadJson = String(decodedBytes)
            val jsonObj = org.json.JSONObject(payloadJson)
            val exp = jsonObj.optLong("exp", 0)

            if (exp == 0L) return false

            val currentTimeSec = System.currentTimeMillis() / 1000
            val timeUntilExpiry = exp - currentTimeSec

            timeUntilExpiry <= seconds
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Check if the refresh token is expired by examining JWT payload
     * Returns true if refresh token is expired or invalid, false if still valid
     *
     * IMPORTANT: This is a best-effort check. If we can't parse the token,
     * we assume it's VALID and let the backend decide during refresh attempt.
     */
    fun isRefreshTokenExpired(): Boolean {
        val refreshToken = getRefreshToken() ?: return true

        return try {
            // JWT tokens have format: header.payload.signature
            val parts = refreshToken.split(".")
            if (parts.size != 3) {
                android.util.Log.d("TokenManager", "Refresh token is not JWT format - assuming valid")
                return false // Not JWT format, let backend decide
            }

            // Decode the payload (second part)
            val payload = parts[1]
            val decodedBytes = android.util.Base64.decode(
                payload,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING
            )
            val payloadJson = String(decodedBytes)

            // Parse JSON to extract expiry timestamp
            val jsonObj = org.json.JSONObject(payloadJson)
            val exp = jsonObj.optLong("exp", 0)

            if (exp == 0L) {
                // No expiry field - assume token is valid
                android.util.Log.d("TokenManager", "Refresh token has no 'exp' claim - assuming valid")
                return false
            }

            // Check if expired (exp is in seconds, currentTimeMillis is in ms)
            val currentTimeSec = System.currentTimeMillis() / 1000
            val isExpired = currentTimeSec >= exp

            if (isExpired) {
                android.util.Log.w("TokenManager", "Refresh token is expired: exp=$exp, now=$currentTimeSec")
            } else {
                android.util.Log.d("TokenManager", "Refresh token is valid: exp=$exp, now=$currentTimeSec, remaining=${exp - currentTimeSec}s")
            }

            isExpired
        } catch (e: Exception) {
            android.util.Log.w("TokenManager", "Failed to parse refresh token for expiry check: ${e.message} - assuming valid, let backend decide")
            // If we can't parse, assume VALID and let backend decide
            false
        }
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
        // Persist hashed device id; if legacy raw is passed, migrate by hashing it.
        val normalized = if (deviceId.length == 64 && deviceId.all { it.isDigit() || (it in 'a'..'f') }) {
            deviceId
        } else {
            DeviceIdManager(appContext).getDeviceIdHash()
        }
        sharedPreferences.edit()
            .putString(KEY_DEVICE_ID, normalized)
            .apply()
    }

    /**
     * Retrieves the device ID bound to current authentication.
     * Returns null if no device has been bound.
     */
    fun getDeviceId(): String? {
        val stored = sharedPreferences.getString(KEY_DEVICE_ID, null)
        if (stored.isNullOrBlank()) return null
        // If legacy raw was stored, migrate to hash
        val isHexHash = stored.length == 64 && stored.all { it.isDigit() || (it in 'a'..'f') }
        return if (isHexHash) {
            stored
        } else {
            val hashed = DeviceIdManager(appContext).getDeviceIdHash()
            sharedPreferences.edit().putString(KEY_DEVICE_ID, hashed).apply()
            hashed
        }
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

    fun saveCurrentDeviceKeyId(deviceKeyId: String = DeviceKeyManager.publicKeyId()) {
        sharedPreferences.edit()
            .putString(KEY_DEVICE_KEY_ID, deviceKeyId)
            .apply()
    }

    fun getBoundDeviceKeyId(): String? {
        return sharedPreferences.getString(KEY_DEVICE_KEY_ID, null)
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
     */
    fun clearDeviceData() {
        sharedPreferences.edit()
            .remove(KEY_DEVICE_ID)
            .remove(KEY_DEVICE_KEY_ID)
            .remove(KEY_ACTIVE_DEVICES)
            .remove(KEY_MAX_DEVICES)
            .apply()

        _deviceMetadataFlow.value = Pair(0, 0)
    }

    private fun buildRefreshRequest(refreshToken: String, deviceId: String, appVersion: String?): net.libreguard.vpn.network.RefreshTokenRequest {
        return net.libreguard.vpn.network.RefreshTokenRequest(
            refreshToken = refreshToken,
            deviceId = deviceId,
            appVersion = appVersion,
            devicePublicKey = DeviceKeyManager.exportPublicKeyBase64(),
            devicePublicKeyId = DeviceKeyManager.publicKeyId(),
            devicePublicKeyAlgorithm = DeviceKeyManager.algorithm()
        )
    }

    private fun parseRefreshFailure(response: retrofit2.Response<net.libreguard.vpn.network.AuthResponse>): TokenRefreshResult.Failure {
        val errorBody = runCatching { response.errorBody()?.string() }.getOrNull()
        val json = runCatching { errorBody?.let(::JSONObject) }.getOrNull()
        return TokenRefreshResult.Failure(
            statusCode = response.code(),
            errorCode = json?.optString("errorCode")?.takeIf { it.isNotBlank() },
            message = json?.optString("message")?.takeIf { it.isNotBlank() } ?: errorBody
        )
    }

    suspend fun refreshTokenWithResult(
        authApiService: net.libreguard.vpn.network.ApiService,
        forceRefresh: Boolean = false
    ): TokenRefreshResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val needsRefresh = forceRefresh || isTokenExpired() || isTokenExpiringWithin(300)

            if (!needsRefresh) {
                android.util.Log.d("TokenManager", "Token is still valid, no refresh needed")
                return@withContext TokenRefreshResult.Skipped
            }

            android.util.Log.d("TokenManager", if (forceRefresh) {
                "Forcing token refresh to update device-bound session state"
            } else {
                "Token expired or expiring soon, attempting refresh"
            })

            val refreshToken = getRefreshToken()
            if (refreshToken.isNullOrBlank()) {
                android.util.Log.w("TokenManager", "No refresh token available for refresh")
                return@withContext TokenRefreshResult.Failure(statusCode = 401, message = "Missing refresh token")
            }

            if (isRefreshTokenExpired()) {
                android.util.Log.w("TokenManager", "Refresh token is expired - cannot refresh, user must re-login")
                return@withContext TokenRefreshResult.Failure(statusCode = 401, message = "Refresh token expired")
            }

            val deviceId = requireDeviceId()
            val appVersion = getAppVersion()
            val refreshRequest = buildRefreshRequest(refreshToken, deviceId, appVersion)

            val response = authApiService.refreshToken(refreshRequest).execute()

            if (response.isSuccessful) {
                val authResponse = response.body()
                if (authResponse?.token != null && authResponse.refreshToken != null) {
                    saveTokens(authResponse.token, authResponse.refreshToken)
                    saveDeviceId(authResponse.deviceId ?: deviceId)
                    saveCurrentDeviceKeyId()

                    if (authResponse.activeDevices != null && authResponse.maxDevices != null) {
                        saveDeviceMetadata(authResponse.activeDevices, authResponse.maxDevices)
                    }

                    android.util.Log.d("TokenManager", "Token refreshed successfully")
                    return@withContext TokenRefreshResult.Success(authResponse.token)
                }

                android.util.Log.w("TokenManager", "Refresh response missing tokens")
                return@withContext TokenRefreshResult.Failure(statusCode = response.code(), message = "Refresh response missing tokens")
            }

            if (response.code() == 429) {
                val retryAfter = response.headers()["Retry-After"]?.toIntOrNull()
                android.util.Log.w("TokenManager", "Token refresh rate limited. Retry-After=$retryAfter")
                return@withContext TokenRefreshResult.RateLimited(retryAfter)
            }

            val failure = parseRefreshFailure(response)
            android.util.Log.w("TokenManager", "Token refresh failed: code=${failure.statusCode}, errorCode=${failure.errorCode}, message=${failure.message}")
            return@withContext failure
        } catch (e: Exception) {
            android.util.Log.e("TokenManager", "Error during token refresh", e)
            return@withContext TokenRefreshResult.ExceptionFailure(e)
        }
    }

    /**
     * Proactively refresh access token if expired or expiring soon.
     */
    suspend fun refreshTokenIfNeeded(
        authApiService: net.libreguard.vpn.network.ApiService
    ): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        when (refreshTokenWithResult(authApiService)) {
            is TokenRefreshResult.Skipped, is TokenRefreshResult.Success -> true
            else -> false
        }
    }

    companion object {
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_LAST_TOKEN_CHECK = "last_token_check"
        private const val TOKEN_CHECK_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_KEY_ID = "device_key_id"
        private const val KEY_ACTIVE_DEVICES = "active_devices"
        private const val KEY_MAX_DEVICES = "max_devices"
    }
}
