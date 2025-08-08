// app/src/main/java/com/example/shadowlinkvpn/service/vpn/ConnectionState.kt
package com.example.shadowlinkvpn.service.vpn

sealed class ConnectionState {
    object Connecting : ConnectionState()
    object Connected : ConnectionState()
    object Disconnected : ConnectionState()
    object Disconnecting : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}