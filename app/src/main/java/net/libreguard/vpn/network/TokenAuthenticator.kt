package net.libreguard.vpn.network

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.libreguard.vpn.util.DeviceKeyManager
import net.libreguard.vpn.util.TokenManager
import net.libreguard.vpn.util.AccountSessionContext
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
        @Volatile private var lastRefreshedSession: AccountSessionContext? = null
    }

    @Volatile
    private var consecutiveRefreshFailures = 0
    private val maxRefreshAttempts = 2
    private var failureSession: AccountSessionContext? = null

    override fun authenticate(route: Route?, response: Response): Request? {
        val url = response.request.url.encodedPath

        // Public auth/account endpoints are intentionally reachable without a JWT.
        if (isPublicAuthEndpoint(url)) {
            android.util.Log.d(TAG, "Skipping token refresh for public endpoint: $url")
            return null
        }

        val expectedSession = response.request.tag(AccountSessionContext::class.java) ?: return null
        if (!tokenManager.isSessionCurrent(expectedSession)) return null
        if (failureSession != expectedSession) {
            consecutiveRefreshFailures = 0
            failureSession = expectedSession
        }

        val retryCount = response.request.header(HEADER_AUTH_RETRY)?.toIntOrNull() ?: 0
        if (retryCount >= MAX_RETRY_PER_REQUEST) {
            android.util.Log.w(TAG, "Auth retry limit reached. Not attempting further refresh.")
            return null
        }

        if (consecutiveRefreshFailures >= maxRefreshAttempts) {
            android.util.Log.w(TAG, "Circuit breaker activated: $consecutiveRefreshFailures consecutive refresh failures. Forcing logout.")
            if (url != "/api/account/newsletter" && url != "/api/account/newsletter/onboarding" &&
                tokenManager.isSessionCurrent(expectedSession)) logoutUser(expectedSession)
            return null
        }

        val failedUrl = response.request.url.toString()
        if (failedUrl.contains("/api/login/refresh")) {
            android.util.Log.w(TAG, "Refresh endpoint itself failed - stopping refresh loop")
            consecutiveRefreshFailures++
            return null
        }

        val storedSnapshot = tokenManager.sessionSnapshot()
        if (storedSnapshot?.context != expectedSession) return null
        val storedToken = storedSnapshot.accessToken
        val requestToken = response.request.header("Authorization")?.removePrefix("Bearer ")
        if (!storedToken.isNullOrBlank() && !requestToken.isNullOrBlank() && storedToken != requestToken) {
            android.util.Log.d(TAG, "Token already refreshed by another thread - retrying with stored token")
            return retryForSession(response, expectedSession, retryCount)
        }

        val refreshedToken: String? = runBlocking {
            refreshMutex.withLock {
                val latestSnapshot = tokenManager.sessionSnapshot()
                if (latestSnapshot?.context != expectedSession || !tokenManager.isSessionCurrent(expectedSession))
                    return@withLock null
                if (lastRefreshedSession != expectedSession) {
                    lastRefreshedAccessToken = null
                    lastRefreshedSession = expectedSession
                    consecutiveRefreshFailures = 0
                }
                val latestStored = latestSnapshot.accessToken
                val latestRequestToken = response.request.header("Authorization")?.removePrefix("Bearer ")
                if (!latestStored.isNullOrBlank() && !latestRequestToken.isNullOrBlank() && latestStored != latestRequestToken) {
                    android.util.Log.d(TAG, "Token refreshed while waiting for mutex - reusing stored token")
                    lastRefreshedAccessToken = latestStored
                    return@withLock latestStored
                }

                val refreshToken = tokenManager.currentRefreshToken(expectedSession)
                if (refreshToken.isNullOrBlank()) {
                    android.util.Log.w(TAG, "No refresh token available")
                    return@withLock null
                }

                if (tokenManager.isRefreshTokenExpired()) {
                    android.util.Log.w(TAG, "Refresh token is expired locally - cannot refresh")
                    return@withLock null
                }

                val deviceId = tokenManager.requireDeviceId()
                val appVersion = tokenManager.getAppVersion() ?: "1.0.0"

                try {
                    val binding = DeviceKeyManager.ensureKeyBinding()
                    val refreshRequest = RefreshTokenRequest(
                        refreshToken = refreshToken,
                        deviceId = deviceId,
                        appVersion = appVersion,
                        devicePublicKey = binding.publicKeyBase64,
                        devicePublicKeyId = binding.keyId,
                        devicePublicKeyAlgorithm = DeviceKeyManager.algorithm()
                    )
                    android.util.Log.d(TAG, "Requesting new token")

                    android.util.Log.d(TAG, "Attempting token refresh")

                    val refreshResponse = authApiService.refreshToken(refreshRequest).execute()

                    if (refreshResponse.isSuccessful) {
                        val authResponse = refreshResponse.body()
                        if (authResponse?.token != null && authResponse.refreshToken != null) {
                            val accepted = tokenManager.commitRefresh(
                                expectedSession, authResponse.token, authResponse.refreshToken
                            ) {
                                tokenManager.saveDeviceId(authResponse.deviceId ?: deviceId)
                                tokenManager.saveCurrentDeviceKeyId(binding.keyId)
                                if (authResponse.activeDevices != null && authResponse.maxDevices != null)
                                    tokenManager.saveDeviceMetadata(authResponse.activeDevices, authResponse.maxDevices)
                            }
                            if (!accepted) return@withLock null

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

        return retryForSession(response, expectedSession, retryCount)
    }

    private fun retryForSession(response: Response, expectedSession: AccountSessionContext, retryCount: Int): Request? {
        val snapshot = tokenManager.sessionSnapshot()
        if (snapshot?.context != expectedSession || !tokenManager.isSessionCurrent(expectedSession)) return null
        return response.request.newBuilder()
            .tag(AccountSessionContext::class.java, expectedSession)
            .header("Authorization", "Bearer " + snapshot.accessToken)
            .header(HEADER_AUTH_RETRY, (retryCount + 1).toString())
            .build()
    }

    private fun logoutUser(originSession: AccountSessionContext) {
        val prefs = context.getSharedPreferences("vpn_state_prefs", Context.MODE_PRIVATE)
        val lastLogoutBroadcast = prefs.getLong("last_logout_broadcast", 0L)
        val now = System.currentTimeMillis()

        if (now - lastLogoutBroadcast < 2000L) {
            android.util.Log.d(TAG, "Skipping duplicate logout broadcast (last sent ${now - lastLogoutBroadcast}ms ago)")
            return
        }

        prefs.edit().putLong("last_logout_broadcast", now).apply()

        if (!tokenManager.clearSessionIfCurrent(originSession) { tokenManager.clearDeviceData() }) return
        val intent = Intent("net.libreguard.vpn.ACTION_LOGOUT")
        intent.putExtra("account_session_epoch", originSession.epoch)
        intent.setPackage(context.packageName)
        context.sendBroadcast(intent)
    }
}
