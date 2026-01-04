package net.libreguard.vpn.network

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    companion object {
        private const val TAG = "TokenAuthenticator"

        /**
         * OkHttp uses the Authenticator as a "follow-up" mechanism.
         * If we keep returning a new Request, OkHttp will keep retrying until it hits its follow-up limit (20).
         *
         * We hard-limit ourselves to 1 retry per request to prevent storms.
         */
        private const val HEADER_AUTH_RETRY = "X-LG-Auth-Retry"
        private const val MAX_RETRY_PER_REQUEST = 1

        /**
         * Single-flight refresh: only one refresh network call can run at a time across all requests.
         * This is critical because the backend revokes previous refresh tokens whenever a new one is issued.
         */
        private val refreshMutex = Mutex()

        /**
         * Tracks the latest access token produced by a refresh attempt so waiters can reuse it.
         */
        @Volatile
        private var lastRefreshedAccessToken: String? = null
    }

    // Circuit breaker to prevent infinite refresh loops
    @Volatile
    private var consecutiveRefreshFailures = 0
    private val maxRefreshAttempts = 2

    override fun authenticate(route: Route?, response: Response): Request? {
        // Per-request retry guard
        val retryCount = response.request.header(HEADER_AUTH_RETRY)?.toIntOrNull() ?: 0
        if (retryCount >= MAX_RETRY_PER_REQUEST) {
            android.util.Log.w(TAG, "Auth retry limit reached ($retryCount). Not attempting further refresh for ${response.request.url}")
            return null
        }

        // Circuit breaker: If we've failed too many times in a row, stop trying
        if (consecutiveRefreshFailures >= maxRefreshAttempts) {
            android.util.Log.w(TAG, "Circuit breaker activated: $consecutiveRefreshFailures consecutive refresh failures. Forcing logout.")
            logoutUser()
            return null
        }

        // Prevent recursive loops if the refresh endpoint itself fails
        val failedUrl = response.request.url.toString()
        if (failedUrl.contains("/api/login/refresh")) {
            android.util.Log.w(TAG, "Refresh endpoint itself failed - stopping refresh loop")
            consecutiveRefreshFailures++
            return null
        }

        // If another request already succeeded in refreshing and updated storage, just retry with that.
        val storedToken = tokenManager.getAccessToken()
        val requestToken = response.request.header("Authorization")?.removePrefix("Bearer ")
        if (!storedToken.isNullOrBlank() && !requestToken.isNullOrBlank() && storedToken != requestToken) {
            android.util.Log.d(TAG, "Token already refreshed by another thread - retrying with stored token")
            return response.request.newBuilder()
                .header("Authorization", "Bearer $storedToken")
                .header(HEADER_AUTH_RETRY, (retryCount + 1).toString())
                .build()
        }

        // Perform refresh with single-flight mutex.
        val refreshedToken: String? = runBlocking {
            refreshMutex.withLock {
                // Re-check after acquiring lock in case someone refreshed while we were waiting.
                val latestStored = tokenManager.getAccessToken()
                val latestRequestToken = response.request.header("Authorization")?.removePrefix("Bearer ")
                if (!latestStored.isNullOrBlank() && !latestRequestToken.isNullOrBlank() && latestStored != latestRequestToken) {
                    android.util.Log.d(TAG, "Token refreshed while waiting for mutex - reusing stored token")
                    lastRefreshedAccessToken = latestStored
                    return@withLock latestStored
                }

                // If a previous refresh attempt in this process already produced a token, reuse it
                // ONLY if it differs from the failing request's token.
                if (!lastRefreshedAccessToken.isNullOrBlank() && lastRefreshedAccessToken != latestRequestToken) {
                    android.util.Log.d(TAG, "Reusing last refreshed token")
                    return@withLock lastRefreshedAccessToken
                }

                val refreshToken = tokenManager.getRefreshToken()
                val deviceId = tokenManager.getDeviceId()

                if (refreshToken.isNullOrBlank()) {
                    android.util.Log.w(TAG, "No refresh token available")
                    return@withLock null
                }

                // Best-effort local check. If opaque refresh token, TokenManager returns false (assume valid).
                if (tokenManager.isRefreshTokenExpired()) {
                    android.util.Log.w(TAG, "Refresh token is expired locally - cannot refresh, forcing logout")
                    return@withLock null
                }

                try {
                    android.util.Log.d(TAG, "Attempting token refresh (attempt ${consecutiveRefreshFailures + 1}/$maxRefreshAttempts)")

                    val refreshResponse = authApiService.refreshToken(
                        RefreshTokenRequest(refreshToken, deviceId)
                    ).execute()

                    if (refreshResponse.isSuccessful) {
                        val authResponse = refreshResponse.body()
                        if (authResponse?.token != null && authResponse.refreshToken != null) {
                            if (authResponse.deviceId != null && authResponse.deviceId != deviceId) {
                                android.util.Log.w(TAG, "Device binding mismatch during refresh")
                                consecutiveRefreshFailures++
                                return@withLock null
                            }

                            tokenManager.saveTokens(authResponse.token, authResponse.refreshToken)
                            if (authResponse.activeDevices != null && authResponse.maxDevices != null) {
                                tokenManager.saveDeviceMetadata(authResponse.activeDevices, authResponse.maxDevices)
                            }

                            consecutiveRefreshFailures = 0
                            lastRefreshedAccessToken = authResponse.token

                            android.util.Log.d(TAG, "Token refresh successful")
                            return@withLock authResponse.token
                        }

                        consecutiveRefreshFailures++
                        android.util.Log.w(TAG, "Refresh response missing tokens")
                        return@withLock null
                    }

                    consecutiveRefreshFailures++
                    android.util.Log.w(TAG, "Refresh failed with ${refreshResponse.code()} (attempt $consecutiveRefreshFailures/$maxRefreshAttempts)")

                    // If refresh is rejected, treat as session-ending.
                    when (refreshResponse.code()) {
                        400, 401, 403, 409 -> return@withLock null
                    }

                    return@withLock null
                } catch (e: Exception) {
                    consecutiveRefreshFailures++
                    android.util.Log.e(TAG, "Exception during token refresh (attempt $consecutiveRefreshFailures/$maxRefreshAttempts)", e)
                    return@withLock null
                }
            }
        }

        if (refreshedToken.isNullOrBlank()) {
            // Don't immediately logout here; let the app see the original 401/403 and decide.
            // Logging out inside the authenticator can cascade across multiple simultaneous requests.
            android.util.Log.w(TAG, "Token refresh did not produce a new token. Not retrying request.")
            return null
        }

        // Retry the original request once with the new token; add retry marker.
        return response.request.newBuilder()
            .header("Authorization", "Bearer $refreshedToken")
            .header(HEADER_AUTH_RETRY, (retryCount + 1).toString())
            .build()
    }

    private fun logoutUser() {
        // CRITICAL: Prevent logout cascade - check if we already sent logout recently
        val prefs = context.getSharedPreferences("vpn_state_prefs", Context.MODE_PRIVATE)
        val lastLogoutBroadcast = prefs.getLong("last_logout_broadcast", 0L)
        val now = System.currentTimeMillis()

        // If we sent a logout broadcast within last 2 seconds, skip to prevent cascade
        if (now - lastLogoutBroadcast < 2000L) {
            android.util.Log.d(TAG, "Skipping duplicate logout broadcast (last sent ${now - lastLogoutBroadcast}ms ago)")
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

