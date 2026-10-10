package net.libreguard.vpn.service.vpn

import android.content.Context
import android.net.VpnService
import kotlinx.coroutines.CancellationException
import net.libreguard.vpn.util.CrashlyticsReporter

internal fun openVpnPermissionFailure(state: String): VpnPermissionFailure? = when (state.uppercase(java.util.Locale.ROOT)) {
    "VPN_PERMISSION_REQUIRED" -> VpnPermissionFailure.CONSENT_REQUIRED
    "VPN_PERMISSION_REVOKED" -> VpnPermissionFailure.CONSENT_REVOKED
    "VPN_PERMISSION_CHECK_FAILED" -> VpnPermissionFailure.CHECK_FAILED
    else -> null
}

/** A startup gate, not a passive authorization query. */
internal fun checkVpnPermissionForStart(context: Context): ConnectionState.Error? =
    vpnPermissionError { VpnService.prepare(context) }

internal fun vpnPermissionError(prepare: () -> Any?): ConnectionState.Error? = try {
    if (prepare() == null) null else ConnectionState.Error(
        "VPN permission needed", VpnPermissionFailure.CONSENT_REQUIRED
    )
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    CrashlyticsReporter.recordHandledException(e, "VPN permission check failed")
    ConnectionState.Error("VPN access could not be checked", VpnPermissionFailure.CHECK_FAILED)
}
