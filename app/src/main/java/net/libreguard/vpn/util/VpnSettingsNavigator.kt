package net.libreguard.vpn.util

import android.provider.Settings

internal fun openVpnSettings(launch: (String) -> Unit): Boolean {
    for (action in listOf(Settings.ACTION_VPN_SETTINGS, Settings.ACTION_SETTINGS)) {
        try {
            launch(action)
            return true
        } catch (_: Exception) {
            // OEMs may not expose the VPN destination, or may restrict settings access.
        }
    }
    CrashlyticsReporter.log("VPN settings destinations unavailable")
    return false
}
