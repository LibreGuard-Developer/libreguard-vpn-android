package net.libreguard.vpn.service.vpn

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class VpnPermissionCheckTest {
    @Test fun onlyNullPreparationAllowsStartup() {
        assertNull(vpnPermissionError { null })
        assertEquals(VpnPermissionFailure.CONSENT_REQUIRED, vpnPermissionError { Any() }?.permissionFailure)
    }
    @Test fun failedPreparationStopsStartupWithoutClaimingTheUserDenied() {
        assertEquals(VpnPermissionFailure.CHECK_FAILED,
            vpnPermissionError { throw SecurityException("platform check failed") }?.permissionFailure)
    }
    @Test fun cancellationIsPropagated() {
        assertThrows(CancellationException::class.java) { vpnPermissionError { throw CancellationException() } }
    }
    @Test fun openVpnPermissionSignalsAreTypedAndOtherErrorsKeepTheirOwnDiagnosis() {
        assertEquals(VpnPermissionFailure.CONSENT_REQUIRED, openVpnPermissionFailure("VPN_PERMISSION_REQUIRED"))
        assertEquals(VpnPermissionFailure.CONSENT_REVOKED, openVpnPermissionFailure("VPN_PERMISSION_REVOKED"))
        assertEquals(VpnPermissionFailure.CHECK_FAILED, openVpnPermissionFailure("VPN_PERMISSION_CHECK_FAILED"))
        assertNull(openVpnPermissionFailure("AUTH_FAILED"))
        assertNull(openVpnPermissionFailure("USER_INPUT"))
        assertNull(openVpnPermissionFailure("NOPROCESS"))
    }
}
