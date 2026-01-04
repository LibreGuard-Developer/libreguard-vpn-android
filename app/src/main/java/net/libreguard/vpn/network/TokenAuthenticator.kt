package net.libreguard.vpn.network

import android.content.Context
import android.content.Intent
import net.libreguard.vpn.util.TokenManager
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

class TokenAuthenticator(
    private val context: Context,
    private val tokenManager: TokenManager,
    private val authApiService: ApiService
) : Authenticator {

    // Circuit breaker to prevent infinite refresh loops
    @Volatile
    private var consecutiveRefreshFailures = 0
    private val maxRefreshAttempts = 2

    override fun authenticate(route: Route?, response: Response): Request? {
        // Circuit breaker: If we've failed too many times in a row, stop trying
        if (consecutiveRefreshFailures >= maxRefreshAttempts) {
            android.util.Log.w("TokenAuthenticator", "Circuit breaker activated: $consecutiveRefreshFailures consecutive refresh failures. Forcing logout.")
            logoutUser()
            return null
        }

        // 1. Get stored refresh token and device ID
        val refreshToken = tokenManager.getRefreshToken()
        val deviceId = tokenManager.getDeviceId()
        if (refreshToken == null) {
            android.util.Log.w("TokenAuthenticator", "No refresh token available")
            return null // No token, let it fail
        }

        // CRITICAL: Check if refresh token is expired locally before making API call
        if (tokenManager.isRefreshTokenExpired()) {
            android.util.Log.w("TokenAuthenticator", "Refresh token is expired locally - cannot refresh, forcing logout")
            logoutUser()
            return null
        }

        // CRITICAL: Check if the failing request IS the refresh endpoint itself
        // This prevents infinite loops where refresh fails and triggers another refresh
        val failedUrl = response.request.url.toString()
        if (failedUrl.contains("/api/login/refresh")) {
            android.util.Log.w("TokenAuthenticator", "Refresh endpoint itself failed - stopping refresh loop")
            consecutiveRefreshFailures++
            logoutUser()
            return null
        }

        synchronized(this) {
            // Check if the token has changed since the request was made (concurrency)
            val newAccessToken = tokenManager.getAccessToken()
            // If the request's header token is different from storage, it means another thread already refreshed it.
            // We can just retry with the new token.
            val requestToken = response.request.header("Authorization")?.removePrefix("Bearer ")
            if (requestToken != null && requestToken != newAccessToken) {
                android.util.Log.d("TokenAuthenticator", "Token already refreshed by another thread - retrying with new token")
                return response.request.newBuilder()
                    .header("Authorization", "Bearer $newAccessToken")
                    .build()
            }

            // 2. Synchronously call refresh endpoint with device binding
            try {
                android.util.Log.d("TokenAuthenticator", "Attempting token refresh (attempt ${consecutiveRefreshFailures + 1}/$maxRefreshAttempts)")

                val refreshResponse = authApiService.refreshToken(
                    RefreshTokenRequest(refreshToken, deviceId)
                ).execute()

                if (refreshResponse.isSuccessful) {
                    val authResponse = refreshResponse.body()
                    if (authResponse != null && authResponse.token != null && authResponse.refreshToken != null) {
                        // 3. Validate device binding: returned deviceId must match current device
                        if (authResponse.deviceId != null && authResponse.deviceId != deviceId) {
                            // Device binding mismatch - token was bound to different device or unbound
                            android.util.Log.w("TokenAuthenticator", "Device binding mismatch during refresh")
                            consecutiveRefreshFailures++
                            logoutUser()
                            return null
                        }

                        // 4. Save NEW tokens with updated device metadata if present
                        tokenManager.saveTokens(authResponse.token, authResponse.refreshToken)

                        // Update device metadata if returned
                        if (authResponse.activeDevices != null && authResponse.maxDevices != null) {
                            tokenManager.saveDeviceMetadata(authResponse.activeDevices, authResponse.maxDevices)
                        }

                        // Reset circuit breaker on success
                        consecutiveRefreshFailures = 0
                        android.util.Log.d("TokenAuthenticator", "Token refresh successful - circuit breaker reset")

                        // 5. Retry the original request with the new access token
                        return response.request.newBuilder()
                            .header("Authorization", "Bearer ${authResponse.token}")
                            .build()
                    }
                } else {
                    // Handle 400 (missing deviceId) or 409 (device limit exceeded) or 401 (token revoked)
                    consecutiveRefreshFailures++
                    android.util.Log.w("TokenAuthenticator", "Refresh failed with ${refreshResponse.code()} (attempt $consecutiveRefreshFailures/$maxRefreshAttempts)")

                    when (refreshResponse.code()) {
                        400, 409 -> {
                            // Backend rejected refresh - likely device binding or limit issue
                            // Force logout immediately
                            logoutUser()
                            return null
                        }
                        401 -> {
                            // Check if it's a device binding issue or just expired token
                            val errorBody = try {
                                refreshResponse.errorBody()?.string() ?: ""
                            } catch (e: Exception) {
                                ""
                            }

                            // If it's a device binding error, force logout
                            // Otherwise, it might just be an expired refresh token (normal flow)
                            if (errorBody.contains("device binding", ignoreCase = true) ||
                                errorBody.contains("INVALID_TOKEN", ignoreCase = true)) {
                                android.util.Log.w("TokenAuthenticator", "Device binding error during refresh: $errorBody")
                                logoutUser()
                                return null
                            }
                            // For other 401 errors, just let it fail naturally
                        }
                    }
                }
            } catch (e: Exception) {
                // Network error or other issue during refresh
                consecutiveRefreshFailures++
                android.util.Log.e("TokenAuthenticator", "Exception during token refresh (attempt $consecutiveRefreshFailures/$maxRefreshAttempts)", e)
            }

            // Refresh failed (token expired/revoked/device mismatch) -> Force Logout
            logoutUser()
            return null
        }
    }

    private fun logoutUser() {
        // CRITICAL: Prevent logout cascade - check if we already sent logout recently
        val prefs = context.getSharedPreferences("vpn_state_prefs", Context.MODE_PRIVATE)
        val lastLogoutBroadcast = prefs.getLong("last_logout_broadcast", 0L)
        val now = System.currentTimeMillis()

        // If we sent a logout broadcast within last 2 seconds, skip to prevent cascade
        if (now - lastLogoutBroadcast < 2000L) {
            android.util.Log.d("TokenAuthenticator", "Skipping duplicate logout broadcast (last sent ${now - lastLogoutBroadcast}ms ago)")
            return
        }

        // Update last broadcast timestamp
        prefs.edit().putLong("last_logout_broadcast", now).apply()

        tokenManager.clearTokens()
        tokenManager.clearDeviceData()
        // Broadcast logout event
        val intent = Intent("net.libreguard.vpn.ACTION_LOGOUT")
        intent.setPackage(context.packageName)
        context.sendBroadcast(intent)
    }
}

