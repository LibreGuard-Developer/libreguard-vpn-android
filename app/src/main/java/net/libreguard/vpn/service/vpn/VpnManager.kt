package net.libreguard.vpn.service.vpn

import android.content.Context
import android.content.Intent
import android.util.Log
import net.libreguard.vpn.service.LibreGuardVpnService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * VPN Manager that handles VPN connection lifecycle and state management
 * Provides logging and connection status updates
 */
class VpnManager(private val context: Context) {

    private val TAG = "VpnManager"

    private val _connectionState = MutableStateFlow(VpnConnectionState.DISCONNECTED)
    val connectionState: StateFlow<VpnConnectionState> = _connectionState.asStateFlow()

    private val _connectionLogs = MutableStateFlow<List<VpnLogEntry>>(emptyList())
    val connectionLogs: StateFlow<List<VpnLogEntry>> = _connectionLogs.asStateFlow()

    /**
     * Connect to VPN using configuration from CA server
     */
    fun connectVpn(config: VpnConfiguration) {
        log("Initiating VPN connection to ${config.serverAddress}")

        try {
            // Update state
            _connectionState.value = VpnConnectionState.CONNECTING

            // Convert VpnConfiguration to JSON for the service
            val configJson = config.toJson()
            log("Created VPN configuration JSON: ${configJson.substring(0, minOf(200, configJson.length))}...")

            // Create intent to start VPN service with full config
            val intent = Intent(context, LibreGuardVpnService::class.java).apply {
                action = LibreGuardVpnService.ACTION_CONNECT
                putExtra(LibreGuardVpnService.EXTRA_CONFIG_JSON, configJson)
            }

            context.startForegroundService(intent)
            log("VPN service started, parsing P12 certificate and beginning IKE negotiation...")

        } catch (e: Exception) {
            log("Failed to start VPN connection: ${e.message}", LogLevel.ERROR)
            _connectionState.value = VpnConnectionState.DISCONNECTED
        }
    }

    /**
     * Disconnect from VPN
     */
    fun disconnectVpn() {
        log("Disconnecting VPN")

        try {
            _connectionState.value = VpnConnectionState.DISCONNECTING

            val intent = Intent(context, LibreGuardVpnService::class.java).apply {
                action = LibreGuardVpnService.ACTION_DISCONNECT
            }

            context.startService(intent)

        } catch (e: Exception) {
            log("Failed to disconnect VPN: ${e.message}", LogLevel.ERROR)
        } finally {
            _connectionState.value = VpnConnectionState.DISCONNECTED
        }
    }

    /**
     * Add log entry with timestamp
     */
    private fun log(message: String, level: LogLevel = LogLevel.INFO) {
        val logEntry = VpnLogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            message = message
        )

        // Add to logs
        val currentLogs = _connectionLogs.value.toMutableList()
        currentLogs.add(logEntry)

        // Keep only last 100 log entries
        if (currentLogs.size > 100) {
            currentLogs.removeAt(0)
        }

        _connectionLogs.value = currentLogs

        // Also log to Android Log
        when (level) {
            LogLevel.DEBUG -> Log.d(TAG, message)
            LogLevel.INFO -> Log.i(TAG, message)
            LogLevel.WARNING -> Log.w(TAG, message)
            LogLevel.ERROR -> Log.e(TAG, message)
        }
    }

    /**
     * Clear all logs
     */
    fun clearLogs() {
        _connectionLogs.value = emptyList()
    }

    /**
     * Update connection state (called by VPN service or state observer)
     */
    fun updateConnectionState(state: VpnConnectionState) {
        val previousState = _connectionState.value
        _connectionState.value = state

        log("Connection state changed: $previousState -> $state")

        when (state) {
            VpnConnectionState.CONNECTING -> log("Starting IKE negotiation...")
            VpnConnectionState.CONNECTED -> log("VPN connection established successfully")
            VpnConnectionState.DISCONNECTING -> log("Terminating VPN connection...")
            VpnConnectionState.DISCONNECTED -> log("VPN disconnected")
            VpnConnectionState.ERROR -> log("VPN connection failed", LogLevel.ERROR)
        }
    }
}

/**
 * VPN connection states
 */
enum class VpnConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    ERROR
}

/**
 * Log entry for VPN connection logging
 */
data class VpnLogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val message: String
)

/**
 * Log levels for VPN logging
 */
enum class LogLevel {
    DEBUG,
    INFO,
    WARNING,
    ERROR
}
