package net.libreguard.vpn.util

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
     * Checks token expiry every 5 minutes and proactively refreshes if expiring within 10 minutes
     * This prevents expired token issues during long-running sessions
     *
     * With 1-hour JWT lifetime, refreshing at 50 minutes ensures continuous session.
     */
    fun startBackgroundTokenRefresh(scope: CoroutineScope) {
        if (refreshJob?.isActive == true) {
            Log.d(TAG, "Background token refresh already running")
            return
        }

        refreshJob = scope.launch(Dispatchers.IO) {
            Log.d(TAG, "Starting background token refresh (checks every 5 minutes, refreshes if expiring within 10 min)")

            while (isActive) {
                try {
                    // Wait 5 minutes before each check (more frequent for better responsiveness)
                    delay(5 * 60 * 1000L)

                    if (!isActive) break

                    // Only refresh if we have tokens
                    if (tokenManager.getAccessToken() != null) {
                        Log.d(TAG, "Running periodic token refresh check")

                        // Check if token is expired or expiring within 10 minutes (600 seconds)
                        // More aggressive than before to ensure smooth session continuity
                        if (tokenManager.isTokenExpired() || tokenManager.isTokenExpiringWithin(600)) {
                            Log.d(TAG, "Token expired or expiring within 10 minutes - attempting background refresh")

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
                } catch (e: CancellationException) {
                    throw e
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
                        validateTokenBeforeConnection()
                    }
                } catch (e: CancellationException) {
                    throw e
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

    /** Only confirmed rejection reports an invalid session; connectivity failures remain retryable. */
    suspend fun validateTokenBeforeConnection(): TokenValidationResult {
        if (tokenManager.getAccessToken().isNullOrBlank()) return TokenValidationResult.REVOKED

        if (tokenManager.isTokenExpired() || tokenManager.isTokenExpiringWithin(300)) {
            val refresh = tokenManager.refreshTokenWithResult(RetrofitClient.authApiService).validationResult()
            if (refresh == TokenValidationResult.REVOKED) handleTokenRevocation()
            if (refresh != TokenValidationResult.VALID) return refresh
        }

        val token = tokenManager.getAccessToken() ?: return TokenValidationResult.REVOKED
        val result = validateTokenRequest(
            request = { RetrofitClient.instance.checkTokenValidity("Bearer $token") },
            onFailure = { Log.w(TAG, "Token validation unavailable", it) }
        )
        Log.d(TAG, "Token validation result=$result")
        when (result) {
            TokenValidationResult.VALID -> tokenManager.saveLastTokenCheckTime(System.currentTimeMillis())
            TokenValidationResult.REVOKED -> handleTokenRevocation()
            TokenValidationResult.UNAVAILABLE -> Unit
        }
        return result
    }

    /** Compatibility for disconnect: network failure must never prevent tunnel cleanup. */
    suspend fun validateTokenBeforeAction(): Boolean =
        validateTokenBeforeConnection() != TokenValidationResult.REVOKED

    suspend fun validateTokenWithTimeout(timeoutMs: Long = 2000L): Boolean =
        withTimeoutOrNull(timeoutMs) { validateTokenBeforeAction() } ?: run {
            Log.d(TAG, "Token validation timed out after ${timeoutMs}ms - proceeding without validation")
            true
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

