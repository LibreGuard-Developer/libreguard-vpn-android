// Kotlin
package com.example.shadowlinkvpn.service.vpn

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

class WireGuardHandler(
    private val appContext: Context
) : VpnProtocolHandler {

    private val tag = "WireGuardHandler"

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _state

    override suspend fun initialize(context: Context): Boolean = true

    override suspend fun connect(context: Context, configPath: String): Boolean = withContext(Dispatchers.IO) {
        Log.w(tag, "WireGuard not implemented")
        _state.value = ConnectionState.Error("WireGuard is not implemented")
        _state.value = ConnectionState.Disconnected
        false
    }

    override suspend fun disconnect(context: Context): Boolean = withContext(Dispatchers.IO) {
        _state.value = ConnectionState.Disconnecting
        _state.value = ConnectionState.Disconnected
        true
    }

    override suspend fun getConnectionLogs(context: Context): String? {
        return "WireGuard handler is not implemented"
    }
}