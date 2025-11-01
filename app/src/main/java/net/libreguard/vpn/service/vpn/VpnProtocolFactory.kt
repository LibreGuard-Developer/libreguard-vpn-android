package net.libreguard.vpn.service.vpn

import android.content.Context
import net.libreguard.vpn.viewmodel.VpnProtocol

object VpnProtocolFactory {
    fun createHandler(protocol: VpnProtocol, context: Context): VpnProtocolHandler {
        return when (protocol) {
            VpnProtocol.IKEV2_IPSEC -> StrongSwanHandler(context)
            VpnProtocol.OPENVPN -> OpenVpnHandler(context)
            VpnProtocol.WIREGUARD -> WireGuardHandler(context)
        }
    }
}
