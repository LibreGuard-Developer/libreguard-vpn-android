// app/src/main/java/com/example/shadowlinkvpn/service/vpn/ConnectionState.kt
package net.libreguard.vpn.service.vpn

sealed class ConnectionState {
    object Connecting : ConnectionState()
    object Connected : ConnectionState()
    object Disconnected : ConnectionState()
    object Disconnecting : ConnectionState()
    data class Error(
        val message: String,
        val permissionFailure: VpnPermissionFailure? = null
    ) : ConnectionState()
}

enum class VpnPermissionFailure {
    CONSENT_REQUIRED, CONSENT_REVOKED, CHECK_FAILED, DENIED, REQUEST_UNAVAILABLE
}
