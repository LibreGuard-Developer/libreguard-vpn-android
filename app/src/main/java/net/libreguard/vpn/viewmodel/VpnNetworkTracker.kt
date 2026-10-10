package net.libreguard.vpn.viewmodel

/** Mutations run on the UI scope; probe workers only read the published flag. */
internal class VpnNetworkTracker<N> {
    private val vpnNetworks = mutableSetOf<N>()
    @Volatile var isVpnPresent = false
        private set

    fun capabilitiesChanged(network: N, isVpn: Boolean) {
        if (isVpn) vpnNetworks.add(network) else vpnNetworks.remove(network)
        isVpnPresent = vpnNetworks.isNotEmpty()
    }

    fun lost(network: N) {
        vpnNetworks.remove(network)
        isVpnPresent = vpnNetworks.isNotEmpty()
    }

    /** Reconcile before user actions/resume, including when a ROM misses a lost callback. */
    fun reconcile(visibleNetworks: Map<N, Boolean?>) {
        vpnNetworks.retainAll(visibleNetworks.keys)
        visibleNetworks.forEach { (network, isVpn) ->
            when (isVpn) {
                true -> vpnNetworks.add(network)
                false -> vpnNetworks.remove(network)
                null -> Unit // Keep known state until capabilities become available.
            }
        }
        isVpnPresent = vpnNetworks.isNotEmpty()
    }
}
