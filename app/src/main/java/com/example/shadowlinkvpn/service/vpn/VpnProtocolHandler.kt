// app/src/main/java/com/example/shadowlinkvpn/service/vpn/VpnProtocolHandler.kt
package com.example.shadowlinkvpn.service.vpn

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

abstract class VpnProtocolHandler {
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    protected fun updateConnectionState(state: ConnectionState) {
        _connectionState.value = state
    }

    abstract suspend fun initialize(context: Context): Boolean
    abstract suspend fun connect(context: Context, configPath: String): Boolean
    abstract suspend fun disconnect(context: Context): Boolean
}

enum class VpnConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    ERROR
}