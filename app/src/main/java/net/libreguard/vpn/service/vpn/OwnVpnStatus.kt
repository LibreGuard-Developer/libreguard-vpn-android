package net.libreguard.vpn.service.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.Process
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.IServiceStatus
import de.blinkt.openvpn.core.IStatusCallbacks
import de.blinkt.openvpn.core.LogItem
import de.blinkt.openvpn.core.OpenVPNStatusService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.strongswan.android.logic.VpnStateService
import java.net.NetworkInterface

/** A device-wide tunnel is evidence of our connection only when we own it. */
internal suspend fun isOwnVpnConnected(
    networkOwnedByApp: Boolean?,
    protocolConnected: suspend () -> Boolean,
    tunnelPresent: () -> Boolean
): Boolean = networkOwnedByApp ?: (protocolConnected() && tunnelPresent())

/**
 * Read-only connection checks, including after the UI process restarts.
 * VpnService.prepare() belongs in the connect flow: it can revoke another VPN.
 */
internal class OwnVpnStatus(private val context: Context) {
    private var strongSwanService: VpnStateService? = null
    private var openVpnService: IServiceStatus? = null
    private var strongSwanBound = false
    private var openVpnBound = false
    private val strongSwanReady = CompletableDeferred<Unit>()
    private val openVpnReady = CompletableDeferred<Unit>()
    @Volatile private var openVpnConnected = false

    private val openVpnCallbacks = object : IStatusCallbacks.Stub() {
        override fun updateStateString(
            state: String?, logmessage: String?, localizedResId: Int,
            level: ConnectionStatus?, intent: Intent?
        ) {
            openVpnConnected = level == ConnectionStatus.LEVEL_CONNECTED
        }

        override fun updateByteCount(in_: Long, out: Long) {}
        override fun newLogItem(logItem: LogItem?) {}
        override fun connectedVPN(uuid: String?) {}
        override fun notifyProfileVersionChanged(uuid: String?, version: Int) {}
    }

    private val strongSwanConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            strongSwanService = (service as? VpnStateService.LocalBinder)?.service
            strongSwanReady.complete(Unit)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            strongSwanService = null
        }
    }

    private val openVpnConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            openVpnService = IServiceStatus.Stub.asInterface(service)
            try {
                openVpnService?.registerStatusCallback(openVpnCallbacks)?.close()
            } catch (_: Exception) {
                openVpnConnected = false
            }
            openVpnReady.complete(Unit)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            openVpnService = null
            openVpnConnected = false
        }
    }

    suspend fun isConnected(): Boolean = withContext(Dispatchers.IO) {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val vpnNetworks = connectivity.allNetworks.mapNotNull {
            connectivity.getNetworkCapabilities(it)
        }.filter { it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }

        // The default network can be the underlying Wi-Fi/cellular network.
        // Inspect every VPN network and check its owner, not just its transport.
        val networkOwnedByApp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && vpnNetworks.isNotEmpty()) {
            vpnNetworks.any { it.ownerUid == Process.myUid() }
        } else {
            null // Android 10 and OEMs without VPN capabilities need protocol evidence.
        }

        isOwnVpnConnected(networkOwnedByApp, ::isProtocolConnected, ::hasTunnel)
    }

    private suspend fun isProtocolConnected(): Boolean = withContext(Dispatchers.Main.immediate) {
        // These are status services only; binding never prepares or starts a tunnel.
        if (!strongSwanBound) {
            strongSwanBound = context.bindService(
                Intent(context, VpnStateService::class.java), strongSwanConnection, Context.BIND_AUTO_CREATE
            )
        }
        if (!openVpnBound) {
            openVpnBound = context.bindService(
                Intent(context, OpenVPNStatusService::class.java), openVpnConnection, Context.BIND_AUTO_CREATE
            )
        }
        withTimeoutOrNull(1_000L) {
            if (strongSwanBound) strongSwanReady.await()
            if (openVpnBound) openVpnReady.await()
        }
        strongSwanService?.state == VpnStateService.State.CONNECTED || openVpnConnected
    }

    private fun hasTunnel(): Boolean = NetworkInterface.getNetworkInterfaces()?.toList()?.any {
        it.isUp && (it.name.startsWith("tun") || it.name.startsWith("ipsec") || it.name.startsWith("wg"))
    } == true

    fun close() {
        runCatching { openVpnService?.unregisterStatusCallback(openVpnCallbacks) }
        if (openVpnBound) runCatching { context.unbindService(openVpnConnection) }
        if (strongSwanBound) runCatching { context.unbindService(strongSwanConnection) }
        openVpnBound = false
        strongSwanBound = false
        openVpnService = null
        strongSwanService = null
        openVpnConnected = false
    }
}