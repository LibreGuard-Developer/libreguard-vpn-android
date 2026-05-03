// Kotlin
package net.libreguard.vpn.service.vpn

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
import java.util.concurrent.atomic.AtomicInteger
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
    @Volatile private var hadConnected: Boolean = false
    @Volatile private var reconnectStartAt: Long = 0L
    private val connectRetryCount = AtomicInteger(0)

    // Reconnect/connecting watchdog
    private val RECONNECT_TIMEOUT_MS = 20_000L
    @Volatile private var connectingSinceMs: Long = 0L

    // Track last status callback time and level for staleness/health detection
    @Volatile private var lastStatusUpdateAt: Long = 0L
    @Volatile private var lastLibLevel: IcsConnectionStatus = IcsConnectionStatus.LEVEL_NOTCONNECTED
    @Volatile private var lastConnectedAt: Long = 0L

    // Watchdog coroutine to periodically verify connection health without network pings
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var watchdogJob: Job? = null

    // ICS OpenVPN state listener
    private val vpnStatusListener = object : StateListener {
        override fun updateState(state: String, logmessage: String, localizedResId: Int, level: de.blinkt.openvpn.core.ConnectionStatus, intent: Intent?) {
            handleStateUpdate(state, logmessage, level)
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
            handleStateUpdate(state ?: "", logmessage ?: "", level ?: IcsConnectionStatus.LEVEL_NOTCONNECTED)
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
            startWatchdog()
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

            // Reset connection markers and counters
            hadConnected = false
            reconnectStartAt = 0L
            connectRetryCount.set(0)

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
                Log.e(tag, "Failed to convert config to VpnProfile")
                _state.value = ConnectionState.Error("Invalid OpenVPN profile")
                _state.value = ConnectionState.Disconnected
                return@withContext false
            }

            // Give profile a readable name
            profile.mName = "LibreGuard OpenVPN"

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
                    putExtra(OpenVPNService.EXTRA_START_REASON, "VPN start")
                    putExtra(LaunchVPN.EXTRA_HIDELOG, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    action = Intent.ACTION_MAIN
                }
                context.startActivity(launch)
            } else {
                VPNLaunchHelper.startOpenVpn(profile, context.applicationContext, "VPN start", true)
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
            reconnectStartAt = 0L
            connectRetryCount.set(0)
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

    private fun startWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = scope.launch {
            var consecutiveUnhealthy = 0
            while (isActive) {
                try {
                    delay(5_000)
                    val current = _state.value

                    // 1) Reconnect/Connecting timeout: if we are stuck in connecting-ish states for > 20s after a prior connection, fail
                    val now = System.currentTimeMillis()
                    val isConnectingish = when (lastStateName) {
                        "CONNECTING", "WAIT", "RESOLVE", "TCP_CONNECT", "GET_CONFIG", "ASSIGN_IP", "ADD_ROUTES", "AUTH", "AUTH_PENDING", "USER_INPUT", "RECONNECTING", "CONNECTRETRY" -> true
                        else -> false
                    }
                    if (hadConnected && isConnectingish && connectingSinceMs > 0L && (now - connectingSinceMs) > RECONNECT_TIMEOUT_MS) {
                        Log.w(tag, "Connecting watchdog exceeded ${RECONNECT_TIMEOUT_MS}ms; failing connection (state=$lastStateName)")
                        connectingActive = false
                        connectingSinceMs = 0L
                        // Ensure the OpenVPN service stops trying
                        runCatching {
                            withContext(Dispatchers.IO) { stopVpnViaService(appContext) }
                        }
                        _state.value = ConnectionState.Disconnected
                        // Skip remainder of loop this tick
                        continue
                    }

                    // 2) Healthy connected watchdog: avoid false positives; rely on libConnected + tun + binder
                    if (current is ConnectionState.Connected) {
                        val tunUp = isTunUp()
                        val libConnected = (lastStateName == "CONNECTED" || lastLibLevel == IcsConnectionStatus.LEVEL_CONNECTED)
                        var binderHealthy = true
                        try { statusService?.lastConnectedVPN } catch (_: Throwable) { binderHealthy = false }
                        val staleCallbacks = (System.currentTimeMillis() - lastStatusUpdateAt) > 30_000

                        val healthy = libConnected && tunUp && binderHealthy
                        if (!healthy) {
                            consecutiveUnhealthy++
                            Log.w(tag, "Watchdog flagged unhealthy VPN state (state=$lastStateName, level=$lastLibLevel, tunUp=$tunUp, binder=$binderHealthy, stale=$staleCallbacks, count=$consecutiveUnhealthy)")
                            if (consecutiveUnhealthy >= 2) {
                                connectingActive = false
                                _state.value = ConnectionState.Disconnected
                                consecutiveUnhealthy = 0
                            }
                        } else {
                            consecutiveUnhealthy = 0
                        }
                    } else {
                        consecutiveUnhealthy = 0
                    }
                } catch (_: Throwable) {
                    // Keep watchdog alive
                }
            }
        }
    }

    private fun handleStateUpdate(stateRaw: String, logmessage: String, level: IcsConnectionStatus) {
        val now = System.currentTimeMillis()
        lastStatusUpdateAt = now
        lastLibLevel = level
        val state = stateRaw.uppercase()
        val prevState = lastStateName
        lastStateName = state

        when (state) {
            "CONNECTED" -> {
                connectingActive = false
                hadConnected = true
                reconnectStartAt = 0L
                connectRetryCount.set(0)
                lastConnectedAt = now
                connectingSinceMs = 0L
                _state.value = ConnectionState.Connected
            }
            "CONNECTING", "WAIT", "RESOLVE", "TCP_CONNECT", "GET_CONFIG", "ASSIGN_IP", "ADD_ROUTES", "AUTH", "AUTH_PENDING", "USER_INPUT" -> {
                // Start/connect timer if not already set (especially important after a prior CONNECTED)
                if (connectingSinceMs == 0L || prevState == "CONNECTED") connectingSinceMs = now
                _state.value = ConnectionState.Connecting
            }
            "RECONNECTING", "CONNECTRETRY" -> {
                _state.value = ConnectionState.Connecting
                if (reconnectStartAt == 0L) reconnectStartAt = now
                // Start/connect timer for reconnect
                if (connectingSinceMs == 0L || prevState == "CONNECTED") connectingSinceMs = now
                if (state == "CONNECTRETRY") connectRetryCount.incrementAndGet()
                if (logmessage.contains("server-pushed-connection-reset", ignoreCase = true)) connectRetryCount.incrementAndGet()

                // FAST-FAIL: Detect critical errors and immediately abort
                val hasTlsError = logmessage.contains("tls-error", ignoreCase = true) ||
                                  logmessage.contains("certificate verify fail", ignoreCase = true) ||
                                  logmessage.contains("certificate problem", ignoreCase = true)
                val hasAuthError = logmessage.contains("AUTH_FAILED", ignoreCase = true)

                if (!hadConnected && (hasTlsError || hasAuthError)) {
                    Log.w(tag, "Fast-fail: detected critical error")
                    connectingActive = false
                    connectingSinceMs = 0L
                    _state.value = ConnectionState.Error("Connection failed: ${if (hasTlsError) "TLS error" else "Authentication error"}. Will retry.")
                    return
                }

                val retryExceeded = connectRetryCount.get() >= 3
                val timeExceeded = (now - reconnectStartAt) > 10_000
                val noTun = !isTunUp()
                if (hadConnected && (retryExceeded || timeExceeded) && noTun) {
                    connectingActive = false
                    _state.value = ConnectionState.Error("OpenVPN: connection reset/refused by server")
                }
            }
            "EXITING" -> {
                connectingActive = false
                connectingSinceMs = 0L
                if (hadConnected) {
                    _state.value = ConnectionState.Disconnected
                } else {
                    _state.value = ConnectionState.Error("OpenVPN exited")
                }
            }
            "DISCONNECTED", "NOPROCESS" -> {
                val elapsed = if (connectStartAt > 0) now - connectStartAt else 0L
                connectingActive = false
                connectingSinceMs = 0L
                if (hadConnected) {
                    _state.value = ConnectionState.Disconnected
                } else if (elapsed > 2000L) {
                    _state.value = ConnectionState.Error("OpenVPN process not running")
                } else {
                    _state.value = ConnectionState.Disconnected
                }
            }
            else -> {
                if (level == IcsConnectionStatus.LEVEL_AUTH_FAILED || logmessage.contains("AUTH_FAILED", true)) {
                    connectingActive = false
                    connectingSinceMs = 0L
                    _state.value = ConnectionState.Error("Authentication failed")
                }
            }
        }
        Log.d(tag, "[AIDL] state=$state level=$level")
    }

    private suspend fun waitUntilConnectedOrFail(timeoutMs: Long = 20000L): Boolean {
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
