package net.libreguard.vpn.util

import org.junit.Assert.*
import org.junit.Test

class BackgroundConnectionSupportTest {
    @Test fun android10DoesNotQueryExactAlarmAccessOrOpenUnsupportedSettings() {
        val platform = FakePlatform(sdkInt = 29)
        val support = BackgroundConnectionSupport(platform)
        assertEquals(true, support.readStatus().exactAlarmAvailable)
        assertEquals(0, platform.exactChecks)
        assertFalse(support.openExactAlarmSettings())
        assertTrue(platform.opened.isEmpty())
    }

    @Test fun freshAndroid14DenialIsReportedAndGrantAppearsOnRefresh() {
        val platform = FakePlatform()
        val support = BackgroundConnectionSupport(platform)
        assertEquals(BackgroundConnectionStatus(false, false, false), support.readStatus())
        platform.battery = true
        platform.exact = true
        assertEquals(BackgroundConnectionStatus(true, true, false), support.readStatus())
        assertTrue(platform.opened.isEmpty())
    }

    @Test fun systemCheckFailuresAreUnknownRatherThanClaimingAccess() {
        val platform = FakePlatform().apply { failChecks = true }
        assertEquals(BackgroundConnectionStatus(null, null, false), BackgroundConnectionSupport(platform).readStatus())
    }

    @Test fun xiaomiFamilyUsesBothManufacturerAndBrandCaseInsensitively() {
        for ((manufacturer, brand) in listOf("Xiaomi" to "other", "other" to " POCO ", "REDMI" to "other")) {
            assertTrue(BackgroundConnectionSupport(FakePlatform(manufacturer = manufacturer, brand = brand)).readStatus().isXiaomiFamily)
        }
        assertFalse(BackgroundConnectionSupport(FakePlatform(manufacturer = "OnePlus")).readStatus().isXiaomiFamily)
    }

    @Test fun missingBatteryRequestFallsBackToBatteryListThenAppDetails() {
        val platform = FakePlatform().apply { available = setOf(BackgroundSettingsDestination.APP_DETAILS) }
        assertTrue(BackgroundConnectionSupport(platform).openBatterySettings())
        assertEquals(listOf(
            BackgroundSettingsDestination.BATTERY_REQUEST,
            BackgroundSettingsDestination.BATTERY_SETTINGS,
            BackgroundSettingsDestination.APP_DETAILS
        ), platform.opened)
    }

    @Test fun missingExactAlarmSettingsFallsBackToAppDetails() {
        val platform = FakePlatform().apply { available = setOf(BackgroundSettingsDestination.APP_DETAILS) }
        assertTrue(BackgroundConnectionSupport(platform).openExactAlarmSettings())
        assertEquals(listOf(BackgroundSettingsDestination.EXACT_ALARM_REQUEST, BackgroundSettingsDestination.APP_DETAILS), platform.opened)
    }

    @Test fun missingAllSettingsActivitiesReturnsFalse() {
        val platform = FakePlatform().apply { available = emptySet() }
        assertFalse(BackgroundConnectionSupport(platform).openBatterySettings())
        assertFalse(BackgroundConnectionSupport(platform).openExactAlarmSettings())
        assertFalse(BackgroundConnectionSupport(platform).openAppDetails())
    }

    @Test fun launchingARequestDoesNotPretendPermissionWasGranted() {
        val platform = FakePlatform()
        val support = BackgroundConnectionSupport(platform)
        assertTrue(support.openBatterySettings())
        assertTrue(support.openExactAlarmSettings())
        assertEquals(false, support.readStatus().batteryExempt)
        assertEquals(false, support.readStatus().exactAlarmAvailable)
    }

    @Test fun tipRequiresSuccessfulForegroundConnectionAndIsShownOnlyOnce() {
        val store = MemoryStore()
        val status = BackgroundConnectionStatus(false, false, false)
        assertFalse(shouldShowBackgroundTip(store, status, true, false, true))
        assertFalse(shouldShowBackgroundTip(store, status, true, true, false))
        assertFalse(store.hasBeenShown())
        assertTrue(shouldShowBackgroundTip(store, status, true, true, true))
        store.markShown()
        assertFalse(shouldShowBackgroundTip(store, status, true, true, true))
    }

    @Test fun openVpnIgnoresExactAlarmDenialButXiaomiStillNeedsManualReview() {
        val store = MemoryStore()
        assertFalse(shouldShowBackgroundTip(store, BackgroundConnectionStatus(true, false, false), false, true, true))
        assertTrue(shouldShowBackgroundTip(store, BackgroundConnectionStatus(true, true, true), false, true, true))
    }

    private class MemoryStore : BackgroundTipStore {
        var shown = false
        override fun hasBeenShown() = shown
        override fun markShown() { shown = true }
    }

    private class FakePlatform(
        override val sdkInt: Int = 34,
        override val manufacturer: String = "Google",
        override val brand: String = "google"
    ) : BackgroundConnectionPlatform {
        var battery = false
        var exact = false
        var failChecks = false
        var exactChecks = 0
        val opened = mutableListOf<BackgroundSettingsDestination>()
        var available = BackgroundSettingsDestination.entries.toSet()
        override fun isBatteryExempt(): Boolean {
            if (failChecks) throw SecurityException()
            return battery
        }
        override fun canScheduleExactAlarms(): Boolean {
            exactChecks++
            if (failChecks) throw SecurityException()
            return exact
        }
        override fun openSettings(destination: BackgroundSettingsDestination): Boolean {
            opened += destination
            return destination in available
        }
    }
}
