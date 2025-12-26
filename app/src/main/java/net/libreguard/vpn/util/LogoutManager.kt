package net.libreguard.vpn.util

import android.util.Log
import net.libreguard.vpn.network.LogoutRequest
import net.libreguard.vpn.network.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.gson.stream.MalformedJsonException

/**
 * Manages the complete logout flow:
 * 1. Call backend API to mark device as inactive
 * 2. Clear local authentication tokens
 * 3. Clear device metadata
 *
 * All operations are best-effort with silent fallback to local logout if API fails.
 * This ensures users can always logout locally, even if backend is unreachable.
 */
object LogoutManager {
    private const val TAG = "LogoutManager"

    /**
     * Perform complete logout: call API, clear tokens, and clear device data.
     *
     * @return true if logout was completed (either API succeeded or local fallback succeeded)
     *         false if both API call and local cleanup failed (highly unlikely)
     *
     * IMPORTANT: Must be called on a coroutine scope (this function is a suspend function)
     * and should be run in the background - do NOT block UI for this operation.
     */
    suspend fun logout(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Step 1: Call backend logout API (best-effort)
            callLogoutApi()
        } catch (e: Exception) {
            Log.e(TAG, "Logout API call failed (will proceed with local logout): ${e.message}", e)
            // Continue to local cleanup even if API fails
        }

        try {
            // Step 2: Clear all local authentication data
            clearLocalData()
            Log.i(TAG, "Local logout completed successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Local logout cleanup failed: ${e.message}", e)
            false // Very unlikely scenario
        }
    }

    /**
     * Call the backend /api/logout endpoint to mark device as inactive.
     * This notifies the backend that the device is logging out.
     *
     * Handles malformed JSON responses gracefully (just logs them, doesn't throw).
     * This is best-effort - even if API call fails, we still clear local tokens.
     */
    private suspend fun callLogoutApi() {
        val tokenManager = RetrofitClient.getTokenManager()
        val accessToken = tokenManager.getAccessToken()
        val refreshToken = tokenManager.getRefreshToken()
        val deviceId = tokenManager.getDeviceId()

        if (accessToken.isNullOrBlank()) {
            Log.w(TAG, "No access token found - skipping API call")
            return
        }

        // Log request details for debugging
        Log.d(TAG, "Logout API Request:")
        Log.d(TAG, "  Authorization: Bearer ${accessToken.take(20)}...")
        Log.d(TAG, "  RefreshToken: ${refreshToken?.take(20) ?: "null"}...")
        Log.d(TAG, "  DeviceId: $deviceId")
        Log.d(TAG, "  Endpoint: POST /api/logout")

        try {
            val request = LogoutRequest(
                refreshToken = refreshToken ?: "",
                deviceId = deviceId
            )
            Log.d(TAG, "  Request body: refreshToken=${request.refreshToken.take(20)}..., deviceId=$deviceId")

            val response = RetrofitClient.instance.logout(
                authorization = "Bearer $accessToken",
                request = request
            )

            Log.i(TAG, "Logout API Response: HTTP ${response.code()}")

            if (response.isSuccessful) {
                Log.i(TAG, "✅ Logout API call successful: ${response.body()?.message}")
            } else {
                val errorBody = response.errorBody()?.string() ?: "empty"
                Log.w(TAG, "❌ Logout API returned error: ${response.code()} - $errorBody")
            }
        } catch (e: MalformedJsonException) {
            Log.w(
                TAG,
                "⚠️ Logout API response was malformed JSON (device IS marked inactive on backend): ${e.message}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "❌ Logout API call failed: ${e.message}", e)
        }
    }

    /**
     * Clear all local authentication and device data from storage.
     */
    private fun clearLocalData() {
        val tokenManager = RetrofitClient.getTokenManager()

        // Clear tokens
        tokenManager.clearTokens()
        Log.d(TAG, "Tokens cleared")

        // Clear device metadata
        tokenManager.clearDeviceData()
        Log.d(TAG, "Device metadata cleared")
    }
}
