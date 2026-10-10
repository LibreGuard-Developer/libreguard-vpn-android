package net.libreguard.vpn.util

import org.junit.Assert.*
import org.junit.Test

class VpnSettingsNavigatorTest {
    @Test fun opensVpnSettingsWithoutTryingAnyOtherDestination() {
        val actions = mutableListOf<String>()
        assertTrue(openVpnSettings { actions += it })
        assertEquals(listOf("android.settings.VPN_SETTINGS"), actions)
    }
    @Test fun missingVpnDestinationFallsBackToGeneralSettings() {
        val actions = mutableListOf<String>()
        assertTrue(openVpnSettings {
            actions += it
            if (actions.size == 1) throw IllegalStateException("unavailable")
        })
        assertEquals(listOf("android.settings.VPN_SETTINGS", "android.settings.SETTINGS"), actions)
    }
    @Test fun unavailableSettingsReturnsFalseSoManualGuidanceRemainsVisible() {
        assertFalse(openVpnSettings { throw SecurityException("restricted") })
    }
}
