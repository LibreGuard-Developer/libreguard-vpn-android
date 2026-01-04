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
    private var refreshJob: Job? = null
    private val TAG = "TokenValidationManager"

    /**
     * Start background token refresh polling
     * Checks token expiry every 10 minutes and proactively refreshes if expiring within 5 minutes
     * This prevents expired token issues during long-running sessions
     */
    fun startBackgroundTokenRefresh(scope: CoroutineScope) {
        if (refreshJob?.isActive == true) {
            Log.d(TAG, "Background token refresh already running")
            return
        }

        refreshJob = scope.launch(Dispatchers.IO) {
            Log.d(TAG, "Starting background token refresh (checks every 10 minutes)")

            while (isActive) {
                try {
                    // Wait 10 minutes before each check
                    delay(10 * 60 * 1000L)

                    if (!isActive) break

                    // Only refresh if we have tokens
                    if (tokenManager.getAccessToken() != null) {
                        Log.d(TAG, "Running periodic token refresh check")

                        // Check if token is expired or expiring within 5 minutes
                        if (tokenManager.isTokenExpired() || tokenManager.isTokenExpiringWithin(300)) {
                            Log.d(TAG, "Token expired or expiring soon - attempting background refresh")

                            val refreshSuccess = tokenManager.refreshTokenIfNeeded(
                                net.libreguard.vpn.network.RetrofitClient.authApiService
                            )

                            if (refreshSuccess) {
                                Log.d(TAG, "Background token refresh successful")
                            } else {
                                Log.w(TAG, "Background token refresh failed - checking if refresh token is expired")

                                // Only check refresh token expiry AFTER refresh has already failed
                                // This prevents false positives on fresh logins
                                if (tokenManager.isRefreshTokenExpired()) {
                                    Log.w(TAG, "Refresh token is expired - session ended, triggering logout")
                                    handleTokenRevocation()
                                    break // Stop the background job
                                } else {
                                    Log.w(TAG, "Refresh failed but refresh token seems valid - might be network issue, will retry later")
                                }
                            }
                        } else {
                            Log.d(TAG, "Token still valid, no refresh needed")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in background token refresh loop", e)
                }
            }

            Log.d(TAG, "Background token refresh stopped")
        }
    }

    /**
     * Stop background token refresh
     */
    fun stopBackgroundTokenRefresh() {
        refreshJob?.cancel()
        refreshJob = null
        Log.d(TAG, "Background token refresh stopped")
    }

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

        // OPTIMIZATION: Check if access token is expired locally first
        if (tokenManager.isTokenExpired()) {
            Log.d(TAG, "Access token is expired locally - checking if refresh token is available")

            // Only check refresh token expiry if access token is expired
            // This prevents false positives on fresh logins where refresh token might be opaque/non-JWT
            if (tokenManager.isRefreshTokenExpired()) {
                Log.w(TAG, "Both access and refresh tokens are expired - cannot recover, triggering logout")
                handleTokenRevocation()
                return false
            }

            // Access token expired but refresh token is valid - skip API validation
            // Let the normal refresh flow handle it
            Log.d(TAG, "Access token expired but refresh token valid - skipping API validation, will refresh on next request")
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
                return false
            }

            // OPTIMIZATION: Check if token is expired locally before making API call
            // This prevents unnecessary 401 responses that trigger TokenAuthenticator loops
            if (tokenManager.isTokenExpired()) {
                Log.w(TAG, "Token is expired locally - skipping API validation to avoid authenticator loop")
                return false
            }

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

