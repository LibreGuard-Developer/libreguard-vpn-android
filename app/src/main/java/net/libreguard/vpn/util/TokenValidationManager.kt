package net.libreguard.vpn.util

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.libreguard.vpn.network.RetrofitClient

/**
 * TokenValidationManager
 * Handles periodic token validation to detect revocation early.
 *
 * Features:
 * - Validates token every 5 minutes using /api/token/check endpoint
 * - Can be triggered on-demand before connect/disconnect operations
 * - Automatically triggers logout if token is invalid/revoked
 * - Respects app lifecycle (stops on background, resumes on foreground)
 */
class TokenValidationManager(
    private val context: Context,
    private val tokenManager: TokenManager
) {
    private var validationJob: Job? = null
    private val TAG = "TokenValidationManager"

    /**
     * Start background token validation polling
     */
    fun startBackgroundValidation(scope: CoroutineScope) {
        if (validationJob?.isActive == true) {
            Log.d(TAG, "Background validation already running")
            return
        }

        validationJob = scope.launch(Dispatchers.IO) {
            Log.d(TAG, "Starting background token validation (every 5 minutes)")

            while (isActive) {
                try {
                    // Wait 5 minutes before each check
                    delay(5 * 60 * 1000L)

                    if (!isActive) break

                    // Only validate if we have a token
                    if (tokenManager.getAccessToken() != null) {
                        Log.d(TAG, "Running periodic token validation check")
                        validateTokenValidity()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in background validation loop", e)
                }
            }

            Log.d(TAG, "Background token validation stopped")
        }
    }

    /**
     * Stop background token validation
     */
    fun stopBackgroundValidation() {
        validationJob?.cancel()
        validationJob = null
        Log.d(TAG, "Background token validation stopped")
    }

    /**
     * Validate token immediately (used before connect/disconnect operations)
     * Returns true if token is valid, false if revoked
     */
    suspend fun validateTokenBeforeAction(): Boolean {
        val token = tokenManager.getAccessToken()
        if (token == null) {
            Log.w(TAG, "No token available for validation")
            return false
        }

        // CRITICAL: Warn if refresh token is missing (indicates OAuth persistence issue)
        val refreshToken = tokenManager.getRefreshToken()
        if (refreshToken.isNullOrBlank()) {
            Log.w(TAG, "WARNING: Refresh token is missing! This indicates OAuth tokens may not have been persisted correctly. " +
                       "User will be unable to refresh expired tokens.")
        }

        return validateTokenValidity()
    }

    /**
     * Check if token is still valid via API
     * Returns true if token is valid, false if revoked
     */
    private suspend fun validateTokenValidity(): Boolean {
        return try {
            val token = tokenManager.getAccessToken()
            if (token == null) {
                Log.w(TAG, "No token to validate")
                false
            } else {
                // CRITICAL: Check if refresh token exists (needed for token rotation)
                val refreshToken = tokenManager.getRefreshToken()
                if (refreshToken.isNullOrBlank()) {
                    Log.w(TAG, "WARNING: Refresh token is missing during validation! " +
                               "Token rotation will fail on expiry. This indicates OAuth tokens were not persisted correctly.")
                }

                val response = RetrofitClient.instance.checkTokenValidity("Bearer $token")

                when {
                    response.isSuccessful -> {
                        val body = response.body()
                        if (body?.isValid == true) {
                            Log.d(TAG, "Token validation successful - token is valid")
                            tokenManager.saveLastTokenCheckTime(System.currentTimeMillis())
                            true
                        } else {
                            Log.w(TAG, "Token validation failed - token is invalid/revoked")
                            handleTokenRevocation()
                            false
                        }
                    }
                    response.code() == 401 || response.code() == 403 -> {
                        Log.w(TAG, "Token validation returned ${response.code()} - token revoked")
                        handleTokenRevocation()
                        false
                    }
                    else -> {
                        Log.w(TAG, "Token validation failed with code ${response.code()}")
                        true // Don't logout on network errors, just skip this check
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during token validation", e)
            false // On exception, assume validation failed
        }
    }

    /**
     * Handle token revocation - clear tokens and broadcast logout
     */
    private fun handleTokenRevocation() {
        try {
            Log.w(TAG, "Handling token revocation - clearing tokens and notifying app")

            // Clear tokens
            tokenManager.clearTokens()

            // Broadcast logout event so UI can redirect to login
            val logoutIntent = Intent("net.libreguard.vpn.ACTION_LOGOUT")
            logoutIntent.setPackage(context.packageName)
            context.sendBroadcast(logoutIntent)

            Log.d(TAG, "Token revocation broadcast sent")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling token revocation", e)
        }
    }
}

