package net.libreguard.vpn.network

import android.content.Context
import android.content.Intent
import android.util.Log
import net.libreguard.vpn.util.TokenManager
import okhttp3.Interceptor
import okhttp3.Response

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

        // If we get a 401 or 403, the token might be revoked
        if (response.code == 401 || response.code == 403) {
            Log.w(TAG, "Received ${response.code} response - token likely revoked. Triggering logout.")
            handleTokenRevocation()
            return response
        }

        return response
    }

    private fun handleTokenRevocation() {
        try {
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

    companion object {
        private const val TAG = "AuthInterceptor"
    }
}

