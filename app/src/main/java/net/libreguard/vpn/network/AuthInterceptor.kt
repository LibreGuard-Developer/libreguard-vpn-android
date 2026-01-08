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
                Log.d(TAG, "Received 401 for token endpoint ${newRequest.url} - letting authenticator handle refresh")
                return response
            }

            // If TokenAuthenticator already retried this request and we STILL got 401,
            // then we're truly unauthorized and should consider logout.
            val authRetry = newRequest.header("X-LG-Auth-Retry")?.toIntOrNull() ?: 0

            var responseBodyString = ""
            try {
                val peekBody = response.peekBody(4096)
                responseBodyString = try { peekBody.string() } catch (_: Exception) { "" }
                val hasAuthHeader = newRequest.header("Authorization") != null
                Log.w(TAG, "Received 401 for request ${newRequest.method} ${newRequest.url}. HasAuthHeader=$hasAuthHeader. AuthRetry=$authRetry. ResponseBody=${responseBodyString.take(1000)}")
            } catch (ex: Exception) {
                Log.w(TAG, "Received 401 - failed to read response body: ${ex.message}")
            }

            // If backend explicitly tells us to login again, honor it.
            // Otherwise, do NOT logout on first failure: allow authenticator path to recover.
            val requiresLogin = try {
                if (responseBodyString.isBlank()) false else JSONObject(responseBodyString).optBoolean("requiresLogin", false)
            } catch (_: Exception) {
                false
            }

            if (requiresLogin || authRetry > 0) {
                Log.w(TAG, "Received 401 response - requiresLogin=$requiresLogin, authRetry=$authRetry. Triggering logout.")
                handleTokenRevocation()
            } else {
                Log.w(TAG, "Received 401 response - not forcing logout yet (authRetry=$authRetry). Letting TokenAuthenticator attempt refresh.")
            }

            return response
        }

        // Handle 403: could be subscription/tier issue OR actual auth issue
        if (response.code == 403) {
            // Peek at response body to differentiate between subscription issues and auth issues
            val peekBody = response.peekBody(Long.MAX_VALUE)
            val responseBodyString = try {
                peekBody.string()
            } catch (e: Exception) {
                ""
            }

            // ===== Device limit exceeded (forced logout reason + upgrade path) =====
            // Backend may return DEVICE_LIMIT_EXCEEDED with requiresDeviceManagement=true.
            // We persist this as a logout reason so LoginScreen can show the user why they were logged out.
            try {
                if (!responseBodyString.isNullOrBlank()) {
                    val jo = JSONObject(responseBodyString)
                    val errorCode = jo.optString("errorCode", "")
                    if (errorCode.equals("DEVICE_LIMIT_EXCEEDED", ignoreCase = true)) {
                        val prefs = context.getSharedPreferences("vpn_state_prefs", Context.MODE_PRIVATE)
                        val payload = JSONObject()
                        payload.put("type", "DEVICE_LIMIT_EXCEEDED")
                        payload.put("message", jo.optString("message", "Device limit exceeded"))
                        payload.put("currentDevices", jo.optInt("currentDevices", -1))
                        payload.put("maxDevices", jo.optInt("maxDevices", -1))
                        payload.put("planType", jo.optString("planType", ""))
                        payload.put("requiresDeviceManagement", jo.optBoolean("requiresDeviceManagement", false))
                        payload.put("deviceId", jo.optString("deviceId", ""))
                        payload.put("timestamp", System.currentTimeMillis())
                        prefs.edit().putString("pending_forced_logout_reason", payload.toString()).apply()
                    }
                }
            } catch (_: Exception) {
                // best-effort only
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

            // Keyword fallback detection
            if (responseBodyString.contains("subscription", ignoreCase = true) ||
                responseBodyString.contains("upgrade", ignoreCase = true) ||
                responseBodyString.contains("tier", ignoreCase = true) ||
                responseBodyString.contains("requires pro", ignoreCase = true) ||
                responseBodyString.contains("requires premium", ignoreCase = true)) {

                // If reason not set from JSON, use the response text
                if (reason == "Access requires higher subscription" && !responseBodyString.isNullOrBlank()) {
                    reason = responseBodyString.take(200)
                }

                Log.w(TAG, "Received 403 response - subscription/tier access issue. Broadcasting upgrade required. Request=${newRequest.method} ${newRequest.url} ResponseBody=${responseBodyString.take(1000)}")
                broadcastUpgradeRequired(reason, resourceType, resourceId, requiredTier)
                return response
            }

            // Otherwise, treat as token revocation
            Log.w(TAG, "Received 403 response - token likely revoked. Triggering logout. Request=${newRequest.method} ${newRequest.url} ResponseBody=${responseBodyString.take(1000)}")
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

            Log.d(TAG, "Token revocation handled - logout broadcast sent")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling token revocation", e)
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
                Log.w(TAG, "Failed to persist pending upgrade payload: ${e.message}")
            }

            context.sendBroadcast(upgradeIntent)

            Log.d(TAG, "Upgrade required broadcast sent")
        } catch (e: Exception) {
            Log.e(TAG, "Error broadcasting upgrade required", e)
        }
    }

    companion object {
        private const val TAG = "AuthInterceptor"
    }
}
