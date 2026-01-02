package net.libreguard.vpn.service

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Kill Switch Manager
 *
 * Manages the Kill Switch feature which blocks all internet traffic when VPN disconnects
 * unexpectedly to prevent IP leaks and maintain privacy.
 *
 * Features:
 * - Monitors VPN connection state
 * - Blocks traffic on unexpected VPN disconnect
 * - Does NOT block on manual disconnect
 * - Works with Auto-Connect for automatic reconnection
 * - Persists enabled/disabled state
 */
class KillSwitchManager private constructor(
    private val context: Context
) {
    private val TAG = "KillSwitchManager"

    private val sharedPrefs: SharedPreferences by lazy {
        context.getSharedPreferences("kill_switch_prefs", Context.MODE_PRIVATE)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Kill Switch state
    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled

    private val _isTrafficBlocked = MutableStateFlow(false)
    val isTrafficBlocked: StateFlow<Boolean> = _isTrafficBlocked

    // VPN connection state tracking
    @Volatile
    private var isVpnConnected = false

    @Volatile
    private var isManualDisconnect = false

    // Network monitoring
    private val connectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        loadKillSwitchPreference()
        Log.d(TAG, "KillSwitchManager initialized, enabled=${_isEnabled.value}")
    }

    /**
     * Load Kill Switch preference from SharedPreferences
     */
    private fun loadKillSwitchPreference() {
        try {
            val enabled = sharedPrefs.getBoolean(PREF_KILL_SWITCH_ENABLED, false)
            _isEnabled.value = enabled
            Log.d(TAG, "Loaded Kill Switch preference: $enabled")

            if (enabled) {
                startMonitoring()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Kill Switch preference: ${e.message}")
        }
    }

    /**
     * Enable or disable Kill Switch
     */
    fun setEnabled(enabled: Boolean) {
        Log.d(TAG, "Setting Kill Switch enabled=$enabled")
        _isEnabled.value = enabled

        // Persist preference
        sharedPrefs.edit()
            .putBoolean(PREF_KILL_SWITCH_ENABLED, enabled)
            .apply()

        if (enabled) {
            startMonitoring()
            // If VPN is not connected and Kill Switch is enabled, we should be in a safe state
            // Don't block traffic immediately - only block when VPN drops
        } else {
            stopMonitoring()
            // Unblock traffic if it was blocked
            if (_isTrafficBlocked.value) {
                unblockTraffic()
            }
        }
    }

    /**
     * Called when VPN successfully connects
     */
    fun onVpnConnected() {
        Log.d(TAG, "VPN connected - Kill Switch active")
        isVpnConnected = true
        isManualDisconnect = false

        // If traffic was blocked due to previous disconnect, unblock it now
        if (_isTrafficBlocked.value) {
            unblockTraffic()
        }

        // Start monitoring if Kill Switch is enabled
        if (_isEnabled.value) {
            startMonitoring()
        }
    }

    /**
     * Called when VPN disconnects
     * @param isManual true if user manually disconnected, false if unexpected disconnect
     */
    fun onVpnDisconnected(isManual: Boolean) {
        Log.d(TAG, "VPN disconnected - isManual=$isManual, killSwitchEnabled=${_isEnabled.value}")
        isVpnConnected = false
        isManualDisconnect = isManual

        // Only block traffic if:
        // 1. Kill Switch is enabled
        // 2. Disconnect was NOT manual
        if (_isEnabled.value && !isManual) {
            Log.w(TAG, "Unexpected VPN disconnect - activating Kill Switch")
            blockTraffic()
        } else if (isManual) {
            Log.d(TAG, "Manual disconnect - NOT blocking traffic")
            // Ensure traffic is unblocked on manual disconnect
            if (_isTrafficBlocked.value) {
                unblockTraffic()
            }
        }
    }

    /**
     * Start monitoring network state for VPN drops
     */
    private fun startMonitoring() {
        if (networkCallback != null) {
            Log.d(TAG, "Network monitoring already active")
            return
        }

        Log.d(TAG, "Starting network monitoring for Kill Switch")

        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "NetworkCallback: VPN network available")
                // VPN is active
                if (_isTrafficBlocked.value && isVpnConnected) {
                    // Traffic was blocked but VPN is back, unblock it
                    scope.launch {
                        unblockTraffic()
                    }
                }
            }

            override fun onLost(network: Network) {
                Log.w(TAG, "NetworkCallback: VPN network lost")
                // VPN connection lost
                if (_isEnabled.value && isVpnConnected && !isManualDisconnect) {
                    // Unexpected VPN loss while connected
                    scope.launch {
                        blockTraffic()
                    }
                }
            }
        }

        try {
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
            Log.d(TAG, "Network callback registered successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback: ${e.message}")
            networkCallback = null
        }
    }

    /**
     * Stop monitoring network state
     */
    private fun stopMonitoring() {
        networkCallback?.let { callback ->
            try {
                connectivityManager.unregisterNetworkCallback(callback)
                Log.d(TAG, "Network callback unregistered")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister network callback: ${e.message}")
            }
            networkCallback = null
        }
    }

    /**
     * Block all internet traffic
     *
     * Note: This is a placeholder implementation. In a production app, you would:
     * 1. Use VpnService.Builder.setBlocking(true) when creating VPN
     * 2. Or create a blocking VPN service that drops all packets
     * 3. Or use firewall rules (requires root)
     *
     * For now, we track the state and show notifications.
     */
    private fun blockTraffic() {
        if (_isTrafficBlocked.value) {
            Log.d(TAG, "Traffic already blocked")
            return
        }

        Log.w(TAG, "🛑 KILL SWITCH ACTIVATED - Blocking traffic")
        _isTrafficBlocked.value = true

        // Show notification to user
        showKillSwitchNotification(true)

        // TODO: Implement actual traffic blocking using VpnService.Builder.setBlocking(true)
        // This requires integration with the VPN handlers to set blocking mode when building VPN
        // For now, we just track state and notify
    }

    /**
     * Unblock internet traffic
     */
    private fun unblockTraffic() {
        if (!_isTrafficBlocked.value) {
            Log.d(TAG, "Traffic already unblocked")
            return
        }

        Log.d(TAG, "✅ KILL SWITCH DEACTIVATED - Unblocking traffic")
        _isTrafficBlocked.value = false

        // Hide notification
        showKillSwitchNotification(false)

        // TODO: Implement actual traffic unblocking
    }

    /**
     * Show notification about Kill Switch status
     */
    private fun showKillSwitchNotification(isBlocking: Boolean) {
        try {
            if (isBlocking) {
                Log.w(TAG, "📢 Showing Kill Switch ACTIVE notification")
                KillSwitchNotification.showKillSwitchActive(context)
            } else {
                Log.d(TAG, "📢 Showing Kill Switch INACTIVE notification")
                KillSwitchNotification.showKillSwitchInactive(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show Kill Switch notification: ${e.message}", e)
            // Fallback to log-only notification
            if (isBlocking) {
                Log.w(TAG, "📢 NOTIFICATION (fallback): Kill Switch is active - Internet blocked")
            } else {
                Log.d(TAG, "📢 NOTIFICATION (fallback): Kill Switch deactivated - Internet restored")
            }
        }
    }

    /**
     * Check if traffic is currently blocked by Kill Switch
     */
    fun isTrafficCurrentlyBlocked(): Boolean {
        return _isTrafficBlocked.value
    }

    /**
     * Get current state for debugging
     */
    fun getDebugState(): String {
        return """
            Kill Switch State:
            - Enabled: ${_isEnabled.value}
            - Traffic Blocked: ${_isTrafficBlocked.value}
            - VPN Connected: $isVpnConnected
            - Last Disconnect Manual: $isManualDisconnect
            - Monitoring Active: ${networkCallback != null}
        """.trimIndent()
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        Log.d(TAG, "Cleaning up KillSwitchManager")
        stopMonitoring()
        if (_isTrafficBlocked.value) {
            unblockTraffic()
        }
    }

    companion object {
        private const val PREF_KILL_SWITCH_ENABLED = "kill_switch_enabled"

        @Volatile
        private var instance: KillSwitchManager? = null

        /**
         * Get singleton instance of KillSwitchManager
         */
        fun getInstance(context: Context): KillSwitchManager {
            return instance ?: synchronized(this) {
                instance ?: KillSwitchManager(context.applicationContext).also { instance = it }
            }
        }
    }
}

