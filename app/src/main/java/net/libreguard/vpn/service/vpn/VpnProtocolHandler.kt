package net.libreguard.vpn.service.vpn

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

interface VpnProtocolHandler {
    val connectionState: StateFlow<ConnectionState>

    suspend fun initialize(context: Context): Boolean

    /**
     * Connect using a protocol-specific config path (e.g., `.sswan` file for strongSwan).
     * Return true on success.
     */
    suspend fun connect(context: Context, configPath: String): Boolean

    /**
     * Disconnect the active VPN connection. Return true on success.
     */
    suspend fun disconnect(context: Context): Boolean

    /**
     * Return recent connection logs (or null if not available).
     */
    suspend fun getConnectionLogs(context: Context): String?
}
