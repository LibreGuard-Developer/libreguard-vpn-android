// app/src/main/java/com/example/shadowlinkvpn/service/vpn/WireGuardHandler.kt
package com.example.shadowlinkvpn.service.vpn

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow

class WireGuardHandler : VpnProtocolHandler {
    private val TAG = "WireGuardHandler"

    override val connectionState = MutableStateFlow(VpnConnectionState(VpnConnectionStatus.DISCONNECTED))

    override suspend fun initialize(context: Context): Boolean {
        Log.d(TAG, "WireGuard handler initialized (placeholder)")
        return true
    }

    override suspend fun connect(configPath: String, params: Map<String, Any>?): Boolean {
        Log.d(TAG, "WireGuard connect called with config: $configPath")
        connectionState.value = VpnConnectionState(VpnConnectionStatus.CONNECTING)
        // TODO: Implement actual WireGuard connection logic
        return false
    }

    override suspend fun disconnect(): Boolean {
        Log.d(TAG, "WireGuard disconnect called")
        connectionState.value = VpnConnectionState(VpnConnectionStatus.DISCONNECTED)
        return true
    }

    override fun cleanup() {
        Log.d(TAG, "WireGuard cleanup called")
        // TODO: Implement WireGuard cleanup logic
    }
}