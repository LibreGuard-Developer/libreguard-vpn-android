package net.libreguard.vpn.viewmodel

import org.junit.Assert.*
import org.junit.Test

class VpnNetworkTrackerTest {
    @Test fun lostCallbackClearsOnlyThatVpn() {
        val tracker = VpnNetworkTracker<Int>()
        tracker.capabilitiesChanged(1, true)
        tracker.capabilitiesChanged(2, true)
        tracker.lost(1)
        assertTrue(tracker.isVpnPresent)
        tracker.lost(2)
        assertFalse(tracker.isVpnPresent)
    }

    @Test fun transportChangeClearsVpnWithoutLostCallback() {
        val tracker = VpnNetworkTracker<Int>()
        tracker.capabilitiesChanged(1, true)
        tracker.capabilitiesChanged(1, false)
        assertFalse(tracker.isVpnPresent)
    }

    @Test fun resumeOrUserActionRecoversFromMissingLostCallback() {
        val tracker = VpnNetworkTracker<Int>()
        tracker.capabilitiesChanged(99, true)
        tracker.reconcile(mapOf(106 to false))
        assertFalse(tracker.isVpnPresent)
    }

    @Test fun snapshotFindsVpnWithoutAvailableCallback() {
        val tracker = VpnNetworkTracker<Int>()
        tracker.reconcile(mapOf(106 to false, 107 to true))
        assertTrue(tracker.isVpnPresent)
    }

    @Test fun unknownCapabilitiesKeepKnownVpnUntilLost() {
        val tracker = VpnNetworkTracker<Int>()
        tracker.capabilitiesChanged(1, true)
        tracker.reconcile(mapOf(1 to null, 2 to false))
        assertTrue(tracker.isVpnPresent)
        tracker.reconcile(mapOf(2 to false))
        assertFalse(tracker.isVpnPresent)
    }

    @Test fun lateAvailableCapabilitiesAreCorrectedByNextSnapshot() {
        val tracker = VpnNetworkTracker<Int>()
        tracker.reconcile(mapOf(106 to false))
        tracker.capabilitiesChanged(99, true)
        tracker.reconcile(mapOf(106 to false))
        assertFalse(tracker.isVpnPresent)
    }
}
