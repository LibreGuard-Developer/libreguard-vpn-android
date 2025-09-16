// Kotlin
package com.example.shadowlinkvpn.service.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import de.blinkt.openvpn.LaunchVPN
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.IOpenVPNServiceInternal
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VPNLaunchHelper
import de.blinkt.openvpn.core.VpnStatus
import de.blinkt.openvpn.core.VpnStatus.StateListener
import de.blinkt.openvpn.core.IServiceStatus
import de.blinkt.openvpn.core.IStatusCallbacks
import de.blinkt.openvpn.core.LogItem
import de.blinkt.openvpn.core.ConnectionStatus as IcsConnectionStatus
import java.io.StringReader
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.net.NetworkInterface

class OpenVpnHandler(
    private val appContext: Context
) : VpnProtocolHandler {

    private val tag = "OpenVpnHandler"

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _state

    // Track current connect attempt to interpret transient states
    @Volatile private var connectStartAt: Long = 0L
    @Volatile private var connectingActive: Boolean = false
    @Volatile private var lastStateName: String = ""

    // ICS OpenVPN state listener
    private val vpnStatusListener = object : StateListener {
        override fun updateState(state: String, logmessage: String, localizedResId: Int, level: de.blinkt.openvpn.core.ConnectionStatus, intent: Intent?) {
            val now = System.currentTimeMillis()
            lastStateName = state.uppercase()
            when (lastStateName) {
                "CONNECTED" -> {
                    connectingActive = false
                    _state.value = ConnectionState.Connected
                }
                "CONNECTING", "WAIT", "RESOLVE", "TCP_CONNECT", "GET_CONFIG", "ASSIGN_IP", "ADD_ROUTES", "AUTH", "AUTH_PENDING", "RECONNECTING", "USER_INPUT", "EXITING" -> {
                    _state.value = ConnectionState.Connecting
                }
                "DISCONNECTED", "NOPROCESS" -> {
                    val elapsed = if (connectStartAt > 0) now - connectStartAt else 0L
                    if (connectingActive && elapsed > 2000L) {
                        connectingActive = false
                        _state.value = ConnectionState.Error("OpenVPN process not running")
                    } else {
                        _state.value = ConnectionState.Disconnected
                    }
                }
                else -> {
                    if (level == de.blinkt.openvpn.core.ConnectionStatus.LEVEL_AUTH_FAILED) {
                        connectingActive = false
                        _state.value = ConnectionState.Error("Authentication failed")
                    }
                }
            }
            Log.d(tag, "[ICS] state=$state level=$level msg=$logmessage")
        }

        override fun setConnectedVPN(uuid: String?) {
            // no-op for our state machine; updateState handles transitions
        }
    }

    // Cross-process status binding (OpenVPNStatusService)
    @Volatile private var statusService: IServiceStatus? = null
    private var statusServiceBound = false
    private val statusLogs = CopyOnWriteArrayList<String>()

    private val statusCallbacks = object : IStatusCallbacks.Stub() {
        override fun updateStateString(state: String?, logmessage: String?, localizedResId: Int, level: IcsConnectionStatus?, intent: Intent?) {
            val st = (state ?: "").uppercase()
            lastStateName = st
            val lvl = level ?: IcsConnectionStatus.LEVEL_NOTCONNECTED
            val now = System.currentTimeMillis()
            when (st) {
                "CONNECTED" -> {
                    connectingActive = false
                    _state.value = ConnectionState.Connected
                }
                "CONNECTING", "WAIT", "RESOLVE", "TCP_CONNECT", "GET_CONFIG", "ASSIGN_IP", "ADD_ROUTES", "AUTH", "AUTH_PENDING", "RECONNECTING", "USER_INPUT", "EXITING" -> _state.value = ConnectionState.Connecting
                "DISCONNECTED", "NOPROCESS" -> {
                    val elapsed = if (connectStartAt > 0) now - connectStartAt else 0L
                    if (connectingActive && elapsed > 2000L) {
                        connectingActive = false
                        _state.value = ConnectionState.Error("OpenVPN process not running")
                    } else {
                        _state.value = ConnectionState.Disconnected
                    }
                }
                else -> if (lvl == IcsConnectionStatus.LEVEL_AUTH_FAILED) {
                    connectingActive = false
                    _state.value = ConnectionState.Error("Authentication failed")
                }
            }
            Log.d(tag, "[AIDL] state=$st level=$lvl msg=${logmessage ?: ""}")
        }
        override fun updateByteCount(in_: Long, out: Long) { /* ignore */ }
        override fun newLogItem(logItem: LogItem?) {
            if (logItem != null) {
                try {
                    statusLogs.add(logItem.getString(appContext))
                    if (statusLogs.size > 500) statusLogs.removeAt(0)
                } catch (_: Throwable) {}
            }
        }
        override fun connectedVPN(uuid: String?) { /* ignore */ }
        override fun notifyProfileVersionChanged(uuid: String?, version: Int) { /* ignore */ }
    }

    private val statusConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            statusService = IServiceStatus.Stub.asInterface(service)
            statusServiceBound = true
            try {
                val pfd: ParcelFileDescriptor? = statusService?.registerStatusCallback(statusCallbacks)
                try { pfd?.close() } catch (_: Throwable) {}
            } catch (t: Throwable) { Log.w(tag, "registerStatusCallback failed: ${t.message}") }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            try { statusService?.unregisterStatusCallback(statusCallbacks) } catch (_: Throwable) {}
            statusServiceBound = false
            statusService = null
        }
    }

    private fun bindStatusService(context: Context) {
        if (statusServiceBound) return
        val intent = Intent(context, de.blinkt.openvpn.core.OpenVPNStatusService::class.java)
        try {
            statusServiceBound = context.bindService(intent, statusConn, Context.BIND_AUTO_CREATE)
            Log.d(tag, "Status service bind initiated: $statusServiceBound")
        } catch (t: Throwable) {
            Log.w(tag, "bindService(OpenVPNStatusService) failed: ${t.message}")
        }
    }

    private fun unbindStatusService(context: Context) {
        if (!statusServiceBound) return
        try {
            statusService?.unregisterStatusCallback(statusCallbacks)
        } catch (_: Throwable) {}
        try { context.unbindService(statusConn) } catch (_: Throwable) {}
        statusServiceBound = false
        statusService = null
    }

    private val listenerRegistered = AtomicBoolean(false)

    override suspend fun initialize(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            ensureOpenVpnNotificationChannels(context)
            bindStatusService(context)
            if (listenerRegistered.compareAndSet(false, true)) {
                // Keep local process listener for completeness, but AIDL will deliver real updates
                VpnStatus.addStateListener(vpnStatusListener)
            }
            true
        } catch (t: Throwable) {
            Log.e(tag, "Failed to initialize OpenVPN handler", t)
            false
        }
    }

    override suspend fun connect(context: Context, configPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            ensureOpenVpnNotificationChannels(context)
            bindStatusService(context)

            // Read config
            val cfg = runCatching { context.filesDir.resolve(configPath).readText() }
                .getOrElse {
                    // If configPath is absolute, fallback to File(configPath)
                    kotlin.runCatching { java.io.File(configPath).readText() }.getOrElse { e ->
                        Log.e(tag, "OpenVPN config not found at $configPath", e)
                        _state.value = ConnectionState.Error("OpenVPN config not found")
                        _state.value = ConnectionState.Disconnected
                        return@withContext false
                    }
                }

            // Parse .ovpn into VpnProfile via ICS parser
            val parser = ConfigParser()
            parser.parseConfig(StringReader(cfg))
            val profile = parser.convertProfile() ?: run {
                Log.e(tag, "Failed to convert .ovpn to VpnProfile")
                _state.value = ConnectionState.Error("Invalid OpenVPN profile")
                _state.value = ConnectionState.Disconnected
                return@withContext false
            }

            // Give profile a readable name
            profile.mName = profile.mName ?: "ShadowLink OpenVPN"
            if (profile.mName.isBlank()) profile.mName = "ShadowLink OpenVPN"

            // Save as temporary profile to avoid polluting profile list
            ProfileManager.setTemporaryProfile(context, profile)
            ProfileManager.saveProfile(context, profile)
            ProfileManager.getInstance(context).saveProfileList(context)

            _state.value = ConnectionState.Connecting
            connectStartAt = System.currentTimeMillis()
            connectingActive = true

            // Start VPN: if permission already granted, start service directly; else use LaunchVPN to prompt
            val needsPermission = android.net.VpnService.prepare(context) != null
            if (needsPermission) {
                val launch = Intent(context, LaunchVPN::class.java).apply {
                    putExtra(LaunchVPN.EXTRA_KEY, profile.getUUIDString())
                    putExtra(OpenVPNService.EXTRA_START_REASON, "ShadowLink start")
                    putExtra(LaunchVPN.EXTRA_HIDELOG, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    action = Intent.ACTION_MAIN
                }
                context.startActivity(launch)
            } else {
                VPNLaunchHelper.startOpenVpn(profile, context.applicationContext, "ShadowLink start", true)
            }

            // Wait until connected (or error) to report success
            val success = waitUntilConnectedOrFail()
            if (!success) Log.w(tag, "OpenVPN did not reach CONNECTED state in time")
            success
        } catch (t: Throwable) {
            Log.e(tag, "Failed to start OpenVPN", t)
            connectingActive = false
            _state.value = ConnectionState.Error("OpenVPN start failed: ${t.localizedMessage}")
            _state.value = ConnectionState.Disconnected
            false
        }
    }

    override suspend fun disconnect(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            _state.value = ConnectionState.Disconnecting
            connectingActive = false
            connectStartAt = 0L
            val stopped = stopVpnViaService(context)
            if (!stopped) {
                Log.w(tag, "Service stopVPN returned false; forcing process stop")
                // Fallback: send an explicit intent to the service to ensure it is running and will clean up
                runCatching {
                    val i = Intent(context, OpenVPNService::class.java)
                    i.action = OpenVPNService.START_SERVICE
                    context.startService(i)
                }
            }
            _state.value = ConnectionState.Disconnected
            true
        } catch (t: Throwable) {
            Log.e(tag, "Failed to stop OpenVPN", t)
            _state.value = ConnectionState.Error("OpenVPN disconnect failed: ${t.localizedMessage}")
            _state.value = ConnectionState.Disconnected
            false
        } finally {
            // Keep status service bound to avoid races on next connect
            if (listenerRegistered.compareAndSet(true, false)) {
                runCatching { VpnStatus.removeStateListener(vpnStatusListener) }
            }
            // Do not unbind here; keep callbacks active across reconnects
        }
    }

    override suspend fun getConnectionLogs(context: Context): String? = withContext(Dispatchers.IO) {
        // Prefer logs collected via AIDL callback
        if (statusLogs.isNotEmpty()) return@withContext statusLogs.joinToString("\n")
        // Fallback to local process buffer
        try {
            val items = VpnStatus.getlogbuffer()
            buildString {
                items.forEach { li ->
                    append("[")
                    append(li.getLogtime())
                    append("] ")
                    append(li.getString(context))
                    append('\n')
                }
            }
        } catch (t: Throwable) {
            Log.e(tag, "Failed to get OpenVPN logs", t)
            null
        }
    }

    private suspend fun stopVpnViaService(context: Context): Boolean = withContext(Dispatchers.IO) {
        var result = false
        val bindIntent = Intent(context, OpenVPNService::class.java).apply {
            action = OpenVPNService.START_SERVICE
        }
        val latch = java.util.concurrent.CountDownLatch(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                try {
                    val svc = IOpenVPNServiceInternal.Stub.asInterface(service)
                    result = svc?.stopVPN(false) == true
                } catch (t: Throwable) {
                    Log.w(tag, "Error calling stopVPN", t)
                } finally {
                    try { context.unbindService(this) } catch (_: Throwable) {}
                    latch.countDown()
                }
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                latch.countDown()
            }
        }
        return@withContext try {
            val bound = context.bindService(bindIntent, conn, Context.BIND_AUTO_CREATE)
            if (!bound) return@withContext false
            // Wait a short time for binder call
            if (!latch.await(2, java.util.concurrent.TimeUnit.SECONDS)) {
                try { context.unbindService(conn) } catch (_: Throwable) {}
            }
            result
        } catch (t: Throwable) {
            Log.w(tag, "bindService stop failed: ${t.message}")
            false
        }
    }

    private fun ensureOpenVpnNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val existing = nm.notificationChannels.associateBy { it.id }

            fun createIfMissing(id: String, name: String, importance: Int) {
                if (existing[id] == null) {
                    val ch = NotificationChannel(id, name, importance)
                    nm.createNotificationChannel(ch)
                    Log.d(tag, "Created notification channel: $id")
                }
            }

            createIfMissing(OpenVPNService.NOTIFICATION_CHANNEL_BG_ID, "OpenVPN Background", NotificationManager.IMPORTANCE_MIN)
            createIfMissing(OpenVPNService.NOTIFICATION_CHANNEL_NEWSTATUS_ID, "OpenVPN Status", NotificationManager.IMPORTANCE_LOW)
            createIfMissing(OpenVPNService.NOTIFICATION_CHANNEL_USERREQ_ID, "OpenVPN User Requests", NotificationManager.IMPORTANCE_HIGH)
        } catch (t: Throwable) {
            Log.w(tag, "Failed to ensure OpenVPN notification channels: ${t.message}")
        }
    }

    private fun isTunUp(): Boolean {
        return try {
            val en = NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val ni = en.nextElement()
                if (ni.name.startsWith("tun") && ni.isUp) return true
            }
            false
        } catch (_: Throwable) { false }
    }

    private suspend fun waitUntilConnectedOrFail(timeoutMs: Long = 45000L): Boolean {
        return try {
            withContext(Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(timeoutMs) {
                    // Fast path via state flow
                    val initialCheck = connectionState
                        .filter { it is ConnectionState.Connected || it is ConnectionState.Error }
                        .first()
                    initialCheck is ConnectionState.Connected
                }
            }
        } catch (_: Throwable) {
            // Fallback polling on ASSIGN_IP/ADD_ROUTES or tun up within the same timeout
            try {
                withContext(Dispatchers.Default) {
                    val start = System.currentTimeMillis()
                    while (System.currentTimeMillis() - start < timeoutMs) {
                        val st = lastStateName
                        if (st == "ASSIGN_IP" || st == "ADD_ROUTES" || isTunUp()) return@withContext true
                        kotlinx.coroutines.delay(300)
                    }
                    false
                }
            } catch (_: Throwable) { false }
        }
    }
}