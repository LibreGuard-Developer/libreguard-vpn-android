package net.libreguard.vpn.service.vpn

import android.os.Handler
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.IStatusCallbacks
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.strongswan.android.logic.VpnStateService

/** Exercise the actual native status sinks without preparing or starting a device VPN. */
class VpnProtocolPermissionPlatformTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun openVpnStartupPermissionLossStopsWatchdogAndSurvivesGenericStatuses() =
        assertOpenVpnFailure("VPN_PERMISSION_REQUIRED", VpnPermissionFailure.CONSENT_REQUIRED, connected = false)

    @Test fun openVpnRuntimeRevocationClearsConnectedStateAndStopsWatchdog() =
        assertOpenVpnFailure("VPN_PERMISSION_REVOKED", VpnPermissionFailure.CONSENT_REVOKED, connected = true)

    @Test fun openVpnCheckFailureRetainsItsOwnDiagnosis() =
        assertOpenVpnFailure("VPN_PERMISSION_CHECK_FAILED", VpnPermissionFailure.CHECK_FAILED, connected = false)

    @Test fun cachedOpenVpnPermissionFailureCannotFailAnInactiveNewHandler() {
        val handler = OpenVpnHandler(context)
        try {
            val callback = field<IStatusCallbacks>(handler, "statusCallbacks")
            callback.updateStateString("VPN_PERMISSION_REVOKED", "", 0, ConnectionStatus.LEVEL_NOTCONNECTED, null)
            assertEquals(ConnectionState.Disconnected, handler.connectionState.value)
        } finally { handler.cleanup() }
    }

    @Test fun strongSwanStartupPermissionLossCancelsExistingRetryAndMonitoring() =
        assertStrongSwanFailure(VpnStateService.ErrorState.VPN_PERMISSION_REQUIRED,
            VpnPermissionFailure.CONSENT_REQUIRED, connected = false)

    @Test fun strongSwanRuntimeRevocationCancelsExistingRetryAndMonitoring() =
        assertStrongSwanFailure(VpnStateService.ErrorState.VPN_PERMISSION_REVOKED,
            VpnPermissionFailure.CONSENT_REVOKED, connected = true)

    private fun assertOpenVpnFailure(status: String, expected: VpnPermissionFailure, connected: Boolean) {
        val handler = OpenVpnHandler(context)
        val watchdog = Job()
        try {
            setField(handler, "connectingActive", !connected)
            setField(handler, "hadConnected", connected)
            setField(handler, "watchdogJob", watchdog)
            field<MutableStateFlow<ConnectionState>>(handler, "_state").value =
                if (connected) ConnectionState.Connected else ConnectionState.Connecting
            val callback = field<IStatusCallbacks>(handler, "statusCallbacks")
            callback.updateStateString(status, "", 0, ConnectionStatus.LEVEL_NOTCONNECTED, null)
            assertEquals(expected, (handler.connectionState.value as ConnectionState.Error).permissionFailure)
            assertTrue(watchdog.isCancelled)
            for (generic in listOf("EXITING", "DISCONNECTED", "CONNECTED")) {
                callback.updateStateString(generic, "", 0, ConnectionStatus.LEVEL_NOTCONNECTED, null)
                assertEquals(expected, (handler.connectionState.value as ConnectionState.Error).permissionFailure)
            }
        } finally { handler.cleanup() }
    }

    private fun assertStrongSwanFailure(error: VpnStateService.ErrorState, expected: VpnPermissionFailure, connected: Boolean) {
        val handler = StrongSwanHandler(context)
        val service = VpnStateService()
        val queue = Handler(Looper.getMainLooper())
        val logMonitor = Job()
        val tunMonitor = Job()
        try {
            // Only the status service is simulated; no Charon service or tunnel is created.
            setField(service, "mHandler", queue)
            setField(handler, "vpnStateService", service)
            setField(handler, "logMonitorJob", logMonitor)
            setField(handler, "tunMonitorJob", tunMonitor)
            field<MutableStateFlow<ConnectionState>>(handler, "_state").value =
                if (connected) ConnectionState.Connected else ConnectionState.Connecting
            service.registerListener(field<VpnStateService.VpnStateListener>(handler, "vpnStateListener"))
            instrumentation.runOnMainSync { service.setError(VpnStateService.ErrorState.UNREACHABLE) }
            instrumentation.waitForIdleSync()
            assertTrue(service.retryIn > 0)
            instrumentation.runOnMainSync { service.setError(error) }
            instrumentation.waitForIdleSync()
            assertEquals(0, service.retryIn)
            assertEquals(expected, (handler.connectionState.value as ConnectionState.Error).permissionFailure)
            assertTrue(logMonitor.isCancelled)
            assertTrue(tunMonitor.isCancelled)
            instrumentation.runOnMainSync { service.setError(VpnStateService.ErrorState.GENERIC_ERROR) }
            instrumentation.waitForIdleSync()
            assertEquals(error, service.errorState)
            assertEquals(expected, (handler.connectionState.value as ConnectionState.Error).permissionFailure)
        } finally {
            queue.removeCallbacksAndMessages(null)
            handler.cleanup()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(instance: Any, name: String): T = instance.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(instance) as T

    private fun setField(instance: Any, name: String, value: Any?) {
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(instance, value)
    }
}
