package com.example.shadowlinkvpn.service.vpn

import android.content.Context
import com.example.shadowlinkvpn.viewmodel.VpnProtocol

object VpnProtocolFactory {
    fun createHandler(protocol: VpnProtocol, context: Context): VpnProtocolHandler {
        return when (protocol) {
            VpnProtocol.IKEV2_IPSEC -> StrongSwanHandler(context)
            VpnProtocol.OPENVPN -> OpenVpnHandler(context)
            VpnProtocol.WIREGUARD -> WireGuardHandler(context)
        }
    }
}