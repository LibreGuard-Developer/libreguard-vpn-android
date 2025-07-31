// app/src/main/java/com/example/shadowlinkvpn/service/vpn/VpnProtocolHandler.kt
package com.example.shadowlinkvpn.service.vpn

import android.content.Context
import android.net.VpnService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

enum class VpnConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

data class VpnConnectionState(
    val status: VpnConnectionStatus,
    val errorMessage: String? = null
)

interface VpnProtocolHandler {
    val connectionState: Flow<VpnConnectionState>

    suspend fun initialize(context: Context): Boolean
    suspend fun connect(configPath: String, params: Map<String, Any>?): Boolean
    suspend fun disconnect(): Boolean
    fun cleanup()
}