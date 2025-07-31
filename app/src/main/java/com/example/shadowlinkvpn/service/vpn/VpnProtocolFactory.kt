// app/src/main/java/com/example/shadowlinkvpn/service/vpn/VpnProtocolFactory.kt
package com.example.shadowlinkvpn.service.vpn

import com.example.shadowlinkvpn.viewmodel.VpnProtocol

object VpnProtocolFactory {
    fun createHandler(protocol: VpnProtocol): VpnProtocolHandler {
        return when(protocol) {
            VpnProtocol.IKEV2_IPSEC -> StrongSwanHandler()
            VpnProtocol.OPENVPN -> OpenVpnHandler()
            VpnProtocol.WIREGUARD -> WireGuardHandler()
        }
    }
}