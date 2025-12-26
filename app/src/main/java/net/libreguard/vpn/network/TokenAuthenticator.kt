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

    override fun authenticate(route: Route?, response: Response): Request? {
        // 1. Get stored refresh token and device ID
        val refreshToken = tokenManager.getRefreshToken()
        val deviceId = tokenManager.getDeviceId()
        if (refreshToken == null) {
            return null // No token, let it fail
        }

        synchronized(this) {
            // Check if the token has changed since the request was made (concurrency)
            val newAccessToken = tokenManager.getAccessToken()
            // If the request's header token is different from storage, it means another thread already refreshed it.
            // We can just retry with the new token.
            val requestToken = response.request.header("Authorization")?.removePrefix("Bearer ")
            if (requestToken != null && requestToken != newAccessToken) {
                return response.request.newBuilder()
                    .header("Authorization", "Bearer $newAccessToken")
                    .build()
            }

            // 2. Synchronously call refresh endpoint with device binding
            try {
                val refreshResponse = authApiService.refreshToken(
                    RefreshTokenRequest(refreshToken, deviceId)
                ).execute()

                if (refreshResponse.isSuccessful) {
                    val authResponse = refreshResponse.body()
                    if (authResponse != null && authResponse.token != null && authResponse.refreshToken != null) {
                        // 3. Validate device binding: returned deviceId must match current device
                        if (authResponse.deviceId != null && authResponse.deviceId != deviceId) {
                            // Device binding mismatch - token was bound to different device or unbound
                            logoutUser()
                            return null
                        }

                        // 4. Save NEW tokens with updated device metadata if present
                        tokenManager.saveTokens(authResponse.token, authResponse.refreshToken)

                        // Update device metadata if returned
                        if (authResponse.activeDevices != null && authResponse.maxDevices != null) {
                            tokenManager.saveDeviceMetadata(authResponse.activeDevices, authResponse.maxDevices)
                        }

                        // 5. Retry the original request with the new access token
                        return response.request.newBuilder()
                            .header("Authorization", "Bearer ${authResponse.token}")
                            .build()
                    }
                } else {
                    // Handle 400 (missing deviceId) or 409 (device limit exceeded) or 401 (token revoked)
                    when (refreshResponse.code()) {
                        400, 409, 401 -> {
                            // Backend rejected refresh - likely device binding or limit issue
                            // Force logout immediately
                            logoutUser()
                            return null
                        }
                    }
                }
            } catch (e: Exception) {
                // Network error or other issue during refresh
                e.printStackTrace()
            }

            // Refresh failed (token expired/revoked/device mismatch) -> Force Logout
            logoutUser()
            return null
        }
    }

    private fun logoutUser() {
        tokenManager.clearTokens()
        tokenManager.clearDeviceData()
        // Broadcast logout event
        val intent = Intent("net.libreguard.vpn.ACTION_LOGOUT")
        intent.setPackage(context.packageName)
        context.sendBroadcast(intent)
    }
}

