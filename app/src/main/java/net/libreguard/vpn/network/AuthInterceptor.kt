package net.libreguard.vpn.network

import android.content.Context
import android.content.Intent
import android.util.Log
import net.libreguard.vpn.util.TokenManager
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONObject

class AuthInterceptor(
    private val tokenManager: TokenManager,
    private val context: Context
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val url = originalRequest.url.encodedPath

        // Skip authentication for pre-auth endpoints (OAuth and password-based)
        // These endpoints authenticate via the request body, not JWT tokens
        if (url.contains("/pre-auth/")) {
            Log.d(TAG, "Skipping Authorization header for pre-auth endpoint: $url")
            return chain.proceed(originalRequest)
        }

        val token = tokenManager.getAccessToken()

        if (token == null) {
            return chain.proceed(originalRequest)
        }

        val newRequest = originalRequest.newBuilder()
            .header("Authorization", "Bearer $token")
            .build()

        val response = chain.proceed(newRequest)

        // Handle 401: token is invalid or revoked
        if (response.code == 401) {
            val url = newRequest.url.toString()

            // CRITICAL: Do NOT trigger logout for token-related endpoints
            // Let TokenAuthenticator handle refresh naturally without cascade
            if (url.contains("/api/login/refresh") || url.contains("/api/token/check")) {
                Log.d(TAG, "Received 401 for token endpoint - letting authenticator handle refresh")
                return response
            }

            // If TokenAuthenticator already retried this request and we STILL got 401,
            // then we're truly unauthorized and should consider logout.
            val authRetry = newRequest.header("X-LG-Auth-Retry")?.toIntOrNull() ?: 0

            var responseBodyString = ""
            try {
                val peekBody = response.peekBody(4096)
                responseBodyString = try { peekBody.string() } catch (_: Exception) { "" }
                Log.w(TAG, "Received 401 for request. AuthRetry=$authRetry")
            } catch (ex: Exception) {
                Log.w(TAG, "Received 401 - failed to read response body")
            }

            // If backend explicitly tells us to login again, honor it.
            // Otherwise, do NOT logout on first failure: allow authenticator path to recover.
            val requiresLogin = try {
                if (responseBodyString.isBlank()) false else JSONObject(responseBodyString).optBoolean("requiresLogin", false)
            } catch (_: Exception) {
                false
            }

            if (requiresLogin || authRetry > 0) {
                Log.w(TAG, "Received 401 - logout required (authRetry=$authRetry)")
                handleTokenRevocation()
            } else {
                Log.w(TAG, "Received 401 - attempting refresh (authRetry=$authRetry)")
            }

            return response
        }

        // Handle 403: could be subscription/tier issue, device limit exceeded, OR actual auth issue
        if (response.code == 403) {
            // Peek at response body to differentiate between different 403 scenarios
            val peekBody = response.peekBody(Long.MAX_VALUE)
            val responseBodyString = try {
                peekBody.string()
            } catch (e: Exception) {
                ""
            }

            // Attempt to parse structured JSON for explicit keys
            var reason: String = "Access requires higher subscription"
            var resourceType: String? = null
            var resourceId: String? = null
            var requiredTier: String? = null

            try {
                if (!responseBodyString.isNullOrBlank()) {
                    val jo = JSONObject(responseBodyString)
                    if (jo.has("requires_pro") && jo.optBoolean("requires_pro").also { if (it) reason = jo.optString("message", reason) }) {
                        resourceType = jo.optString("resource_type").takeIf { it.isNotBlank() }
                        resourceId = jo.optString("resource_id").takeIf { it.isNotBlank() }
                        requiredTier = jo.optString("required_tier").takeIf { it.isNotBlank() }
                    }
                }
            } catch (e: Exception) {
                // ignore parse errors and fallback to keyword detection
            }

            // Check if this is a DEVICE_LIMIT_EXCEEDED error - DO NOT logout, broadcast device limit event instead
            try {
                if (!responseBodyString.isNullOrBlank()) {
                    val jo = JSONObject(responseBodyString)
                    val errorCode = jo.optString("errorCode", "")
                    if (errorCode.equals("DEVICE_LIMIT_EXCEEDED", ignoreCase = true)) {
                        Log.w(TAG, "Received 403 with DEVICE_LIMIT_EXCEEDED")
                        broadcastDeviceLimitExceeded(responseBodyString, token)
                        return response
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to check for device limit error")
            }

            // Keyword fallback detection
            if (responseBodyString.contains("subscription", ignoreCase = true) ||
                responseBodyString.contains("upgrade", ignoreCase = true) ||
                responseBodyString.contains("tier", ignoreCase = true) ||
                responseBodyString.contains("requires pro", ignoreCase = true) ||
                responseBodyString.contains("requires premium", ignoreCase = true)) {

                // If reason not set from JSON, use the response text
                if (reason == "Access requires higher subscription" && !responseBodyString.isNullOrBlank()) {
                    reason = "Subscription upgrade required"
                }

                Log.w(TAG, "Received 403 response - subscription upgrade required")
                broadcastUpgradeRequired(reason, resourceType, resourceId, requiredTier)
                return response
            }

            // Otherwise, treat as token revocation
            Log.w(TAG, "Received 403 response - revocation likely")
            handleTokenRevocation()
            return response
        }

        return response
    }

    private fun handleTokenRevocation() {
        try {
            // CRITICAL: Prevent logout cascade - check if we already sent logout recently
            val prefs = context.getSharedPreferences("vpn_state_prefs", Context.MODE_PRIVATE)
            val lastLogoutBroadcast = prefs.getLong("last_logout_broadcast", 0L)
            val now = System.currentTimeMillis()

            // If we sent a logout broadcast within last 2 seconds, skip to prevent cascade
            if (now - lastLogoutBroadcast < 2000L) {
                Log.d(TAG, "Skipping duplicate logout broadcast (last sent ${now - lastLogoutBroadcast}ms ago)")
                return
            }

            // Update last broadcast timestamp
            prefs.edit().putLong("last_logout_broadcast", now).apply()

            // Clear tokens immediately
            tokenManager.clearTokens()

            // Broadcast logout event to all components
            val logoutIntent = Intent("net.libreguard.vpn.ACTION_LOGOUT")
            logoutIntent.setPackage(context.packageName)
            context.sendBroadcast(logoutIntent)

            Log.d(TAG, "Token revocation handled")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling token revocation")
        }
    }

    private fun broadcastUpgradeRequired(reason: String, resourceType: String?, resourceId: String?, requiredTier: String?) {
        try {
            val upgradeIntent = Intent("net.libreguard.vpn.ACTION_SHOW_UPGRADE")
            upgradeIntent.setPackage(context.packageName)
            upgradeIntent.putExtra("upgrade_reason", reason)
            resourceType?.let { upgradeIntent.putExtra("resource_type", it) }
            resourceId?.let { upgradeIntent.putExtra("resource_id", it) }
            requiredTier?.let { upgradeIntent.putExtra("required_tier", it) }
            upgradeIntent.putExtra("timestamp", System.currentTimeMillis())

            // Persist a compact pending payload so app can react if backgrounded
            try {
                val payload = JSONObject()
                payload.put("upgrade_reason", reason)
                resourceType?.let { payload.put("resource_type", it) }
                resourceId?.let { payload.put("resource_id", it) }
                requiredTier?.let { payload.put("required_tier", it) }
                payload.put("timestamp", System.currentTimeMillis())

                val prefs = context.getSharedPreferences("vpn_state_prefs", Context.MODE_PRIVATE)
                prefs.edit().putString("pending_upgrade_payload", payload.toString()).apply()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist upgrade payload")
            }

            context.sendBroadcast(upgradeIntent)

            Log.d(TAG, "Upgrade broadcast sent")
        } catch (e: Exception) {
            Log.e(TAG, "Error broadcasting upgrade")
        }
    }

    /**
     * Broadcast device limit exceeded event - does NOT logout the user.
     * The user has a valid token but needs to remove a device before using the app.
     */
    private fun broadcastDeviceLimitExceeded(responseBody: String, token: String?) {
        try {
            val prefs = context.getSharedPreferences("vpn_state_prefs", Context.MODE_PRIVATE)

            // Parse response and build payload
            val payload = JSONObject()
            payload.put("type", "DEVICE_LIMIT_EXCEEDED")
            payload.put("timestamp", System.currentTimeMillis())

            try {
                val jo = JSONObject(responseBody)
                payload.put("message", jo.optString("message", "Device limit exceeded"))
                payload.put("currentDevices", jo.optInt("currentDevices", -1))
                payload.put("maxDevices", jo.optInt("maxDevices", -1))
                payload.put("planType", jo.optString("planType", ""))
                payload.put("requiresDeviceManagement", jo.optBoolean("requiresDeviceManagement", false))
                payload.put("deviceId", jo.optString("deviceId", ""))

                // Include devices array if present
                if (jo.has("devices")) {
                    payload.put("devices", jo.getJSONArray("devices"))
                }

                // Redacted email extraction from JWT
                payload.put("email", "redacted")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse device limit response")
            }

            // Store the payload for LoginScreen or MainActivity to pick up
            prefs.edit().putString("pending_device_limit_exceeded", payload.toString()).apply()

            // Broadcast the event
            val deviceLimitIntent = Intent("net.libreguard.vpn.ACTION_DEVICE_LIMIT_EXCEEDED")
            deviceLimitIntent.setPackage(context.packageName)
            deviceLimitIntent.putExtra("payload", payload.toString())
            context.sendBroadcast(deviceLimitIntent)

            Log.d(TAG, "Device limit broadcast sent")
        } catch (e: Exception) {
            Log.e(TAG, "Error broadcasting device limit")
        }
    }

    companion object {
        private const val TAG = "AuthInterceptor"

        /**
         * Extract email from JWT token payload.
         * JWT format: header.payload.signature (base64 encoded)
         */
        fun extractEmailFromJwt(token: String): String {
            return try {
                val parts = token.split(".")
                if (parts.size >= 2) {
                    val payload = parts[1]
                    // Add padding if needed for base64 decoding
                    val paddedPayload = when (payload.length % 4) {
                        2 -> payload + "=="
                        3 -> payload + "="
                        else -> payload
                    }
                    val decodedBytes = android.util.Base64.decode(paddedPayload, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
                    val decodedPayload = String(decodedBytes, Charsets.UTF_8)
                    val jsonPayload = JSONObject(decodedPayload)
                    jsonPayload.optString("email", "")
                } else {
                    ""
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to extract email from JWT: ${e.message}")
                ""
            }
        }
    }
}
