package net.libreguard.vpn.service.vpn

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnVpnStatusTest {
    @Test
    fun `another apps VPN cannot suppress connection for either protocol`() = runTest {
        for (staleProtocolConnected in listOf(false, true)) {
            assertFalse(isOwnVpnConnected(
                networkOwnedByApp = false,
                protocolConnected = { staleProtocolConnected },
                tunnelPresent = { true }
            ))
        }
    }

    @Test
    fun `our VPN survives UI restart without a protocol handler`() = runTest {
        assertTrue(isOwnVpnConnected(
            networkOwnedByApp = true,
            protocolConnected = { error("Owned network should not require a handler") },
            tunnelPresent = { false }
        ))
    }

    @Test
    fun `Android 10 ignores an external tunnel without our connected protocol`() = runTest {
        assertFalse(isOwnVpnConnected(
            networkOwnedByApp = null,
            protocolConnected = { false },
            tunnelPresent = { true }
        ))
    }

    @Test
    fun `Android 10 restores either connected protocol with a live tunnel`() = runTest {
        for (protocol in listOf("IKEv2", "OpenVPN")) {
            assertTrue(protocol, isOwnVpnConnected(
                networkOwnedByApp = null,
                protocolConnected = { true },
                tunnelPresent = { true }
            ))
        }
    }

    @Test
    fun `a stale connected protocol without a tunnel is disconnected`() = runTest {
        assertFalse(isOwnVpnConnected(
            networkOwnedByApp = null,
            protocolConnected = { true },
            tunnelPresent = { false }
        ))
    }

    @Test
    fun `permission handoff with lingering external interface does not claim success`() = runTest {
        assertFalse(isOwnVpnConnected(
            networkOwnedByApp = null,
            protocolConnected = { false },
            tunnelPresent = { true }
        ))
        assertTrue(isOwnVpnConnected(
            networkOwnedByApp = true,
            protocolConnected = { true },
            tunnelPresent = { true }
        ))
    }

    @Test
    fun `cancelling a status check propagates cancellation`() = runTest {
        var cancelled = false
        try {
            isOwnVpnConnected(
                networkOwnedByApp = null,
                protocolConnected = { throw CancellationException("Connect cancelled") },
                tunnelPresent = { true }
            )
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }
}