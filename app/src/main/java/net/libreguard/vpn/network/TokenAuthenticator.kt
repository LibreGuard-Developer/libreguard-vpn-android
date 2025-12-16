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
        // 1. Get stored refresh token
        val refreshToken = tokenManager.getRefreshToken()
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

            // 2. Synchronously call refresh endpoint
            try {
                val refreshResponse = authApiService.refreshToken(RefreshTokenRequest(refreshToken)).execute()

                if (refreshResponse.isSuccessful) {
                    val authResponse = refreshResponse.body()
                    if (authResponse != null && authResponse.token != null && authResponse.refreshToken != null) {
                        // 3. Save NEW tokens
                        tokenManager.saveTokens(authResponse.token, authResponse.refreshToken)

                        // 4. Retry the original request with the new access token
                        return response.request.newBuilder()
                            .header("Authorization", "Bearer ${authResponse.token}")
                            .build()
                    }
                }
            } catch (e: Exception) {
                // Network error or other issue during refresh
                e.printStackTrace()
            }

            // Refresh failed (token expired/revoked) -> Force Logout
            logoutUser()
            return null
        }
    }

    private fun logoutUser() {
        tokenManager.clearTokens()
        // Broadcast logout event
        val intent = Intent("net.libreguard.vpn.ACTION_LOGOUT")
        intent.setPackage(context.packageName)
        context.sendBroadcast(intent)
    }
}

