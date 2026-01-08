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

        private const val HEADER_AUTH_RETRY = "X-LG-Auth-Retry"
        private const val MAX_RETRY_PER_REQUEST = 1

        private val refreshMutex = Mutex()

        @Volatile
        private var lastRefreshedAccessToken: String? = null
    }

    @Volatile
    private var consecutiveRefreshFailures = 0
    private val maxRefreshAttempts = 2

    override fun authenticate(route: Route?, response: Response): Request? {
        val retryCount = response.request.header(HEADER_AUTH_RETRY)?.toIntOrNull() ?: 0
        if (retryCount >= MAX_RETRY_PER_REQUEST) {
            android.util.Log.w(TAG, "Auth retry limit reached ($retryCount). Not attempting further refresh for ${response.request.url}")
            return null
        }

        if (consecutiveRefreshFailures >= maxRefreshAttempts) {
            android.util.Log.w(TAG, "Circuit breaker activated: $consecutiveRefreshFailures consecutive refresh failures. Forcing logout.")
            logoutUser()
            return null
        }

        val failedUrl = response.request.url.toString()
        if (failedUrl.contains("/api/login/refresh")) {
            android.util.Log.w(TAG, "Refresh endpoint itself failed - stopping refresh loop")
            consecutiveRefreshFailures++
            return null
        }

        val storedToken = tokenManager.getAccessToken()
        val requestToken = response.request.header("Authorization")?.removePrefix("Bearer ")
        if (!storedToken.isNullOrBlank() && !requestToken.isNullOrBlank() && storedToken != requestToken) {
            android.util.Log.d(TAG, "Token already refreshed by another thread - retrying with stored token")
            return response.request.newBuilder()
                .header("Authorization", "Bearer $storedToken")
                .header(HEADER_AUTH_RETRY, (retryCount + 1).toString())
                .build()
        }

        val refreshedToken: String? = runBlocking {
            refreshMutex.withLock {
                val latestStored = tokenManager.getAccessToken()
                val latestRequestToken = response.request.header("Authorization")?.removePrefix("Bearer ")
                if (!latestStored.isNullOrBlank() && !latestRequestToken.isNullOrBlank() && latestStored != latestRequestToken) {
                    android.util.Log.d(TAG, "Token refreshed while waiting for mutex - reusing stored token")
                    lastRefreshedAccessToken = latestStored
                    return@withLock latestStored
                }

                if (!lastRefreshedAccessToken.isNullOrBlank() && lastRefreshedAccessToken != latestRequestToken) {
                    android.util.Log.d(TAG, "Reusing last refreshed token")
                    return@withLock lastRefreshedAccessToken
                }

                val refreshToken = tokenManager.getRefreshToken()
                if (refreshToken.isNullOrBlank()) {
                    android.util.Log.w(TAG, "No refresh token available")
                    return@withLock null
                }

                if (tokenManager.isRefreshTokenExpired()) {
                    android.util.Log.w(TAG, "Refresh token is expired locally - cannot refresh")
                    return@withLock null
                }

                val deviceId = try {
                    tokenManager.requireDeviceId()
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Failed to ensure deviceId for refresh: ${e.message}")
                    return@withLock null
                }
                val appVersion = tokenManager.getAppVersion()

                try {
                    android.util.Log.d(TAG, "Attempting token refresh (attempt ${consecutiveRefreshFailures + 1}/$maxRefreshAttempts)")

                    val refreshResponse = authApiService.refreshToken(
                        RefreshTokenRequest(refreshToken = refreshToken, deviceId = deviceId, appVersion = appVersion)
                    ).execute()

                    if (refreshResponse.isSuccessful) {
                        val authResponse = refreshResponse.body()
                        if (authResponse?.token != null && authResponse.refreshToken != null) {
                            tokenManager.saveTokens(authResponse.token, authResponse.refreshToken)
                            tokenManager.saveDeviceId(authResponse.deviceId ?: deviceId)
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
                    return@withLock null
                } catch (e: Exception) {
                    consecutiveRefreshFailures++
                    android.util.Log.e(TAG, "Exception during token refresh (attempt $consecutiveRefreshFailures/$maxRefreshAttempts)", e)
                    return@withLock null
                }
            }
        }

        if (refreshedToken.isNullOrBlank()) {
            android.util.Log.w(TAG, "Token refresh did not produce a new token. Not retrying request.")
            return null
        }

        return response.request.newBuilder()
            .header("Authorization", "Bearer $refreshedToken")
            .header(HEADER_AUTH_RETRY, (retryCount + 1).toString())
            .build()
    }

    private fun logoutUser() {
        val prefs = context.getSharedPreferences("vpn_state_prefs", Context.MODE_PRIVATE)
        val lastLogoutBroadcast = prefs.getLong("last_logout_broadcast", 0L)
        val now = System.currentTimeMillis()

        if (now - lastLogoutBroadcast < 2000L) {
            android.util.Log.d(TAG, "Skipping duplicate logout broadcast (last sent ${now - lastLogoutBroadcast}ms ago)")
            return
        }

        prefs.edit().putLong("last_logout_broadcast", now).apply()

        tokenManager.clearTokens()
        tokenManager.clearDeviceData()
        val intent = Intent("net.libreguard.vpn.ACTION_LOGOUT")
        intent.setPackage(context.packageName)
        context.sendBroadcast(intent)
    }
}

