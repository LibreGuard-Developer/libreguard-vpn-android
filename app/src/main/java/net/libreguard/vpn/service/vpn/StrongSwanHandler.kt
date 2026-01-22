package net.libreguard.vpn.service.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.security.KeyChain
import android.security.KeyChainException
import android.widget.Toast
import org.strongswan.android.security.LocalCertificateKeyStoreManager
import android.util.Log
import net.libreguard.vpn.service.VpnNotificationManager
import net.libreguard.vpn.util.VpnConfigManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.strongswan.android.data.LogContentProvider
import org.strongswan.android.data.VpnProfile
import org.strongswan.android.data.VpnProfileDataSource
import org.strongswan.android.data.VpnProfileSource
import org.strongswan.android.data.VpnType
import org.strongswan.android.logic.CharonVpnService
import org.strongswan.android.logic.VpnStateService
import org.strongswan.android.logic.imc.ImcState
import org.strongswan.android.logic.StrongSwanApplication
import java.io.File
import java.io.FileInputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class StrongSwanHandler(
    private val appContext: Context
) : VpnProtocolHandler {

    private val tag = "StrongSwanHandler"
    private val configManager = VpnConfigManager(appContext)

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _state

    @Volatile
    private var currentProfile: VpnProfile? = null

    // Connection lock to prevent concurrent connection attempts
    private val isConnecting = AtomicBoolean(false)

    // State monitoring
    private var vpnStateService: VpnStateService? = null
    private var stateServiceBound = false
    private val monitoringScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var logMonitorJob: Job? = null
    private var tunMonitorJob: Job? = null
    private val authFailureCount = AtomicInteger(0)
    private val isMonitoring = AtomicBoolean(false)
    private val dataLimitNotificationShown = AtomicBoolean(false)
    @Volatile private var lastLogPosition = 0L

    private val stateServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            try {
                vpnStateService = (service as? VpnStateService.LocalBinder)?.service
                Log.d(tag, "Connected to VpnStateService")

                // Start monitoring for auth failures
                if (_state.value is ConnectionState.Connecting || _state.value is ConnectionState.Connected) {
                    startLogMonitoring()
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to bind VpnStateService: ${e.message}")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            vpnStateService = null
            stateServiceBound = false
            Log.d(tag, "Disconnected from VpnStateService")
        }
    }

    override suspend fun initialize(context: Context): Boolean {
        Log.d(tag, "Initializing StrongSwan handler")
        bindVpnStateService(context)
        return true
    }

    private fun bindVpnStateService(context: Context) {
        if (stateServiceBound) return
        try {
            val intent = Intent(context, VpnStateService::class.java)
            stateServiceBound = context.bindService(intent, stateServiceConnection, Context.BIND_AUTO_CREATE)
            Log.d(tag, "VpnStateService bind initiated: $stateServiceBound")
        } catch (e: Exception) {
            Log.w(tag, "Failed to bind VpnStateService: ${e.message}")
        }
    }

    private fun unbindVpnStateService(context: Context) {
        if (!stateServiceBound) return
        try {
            context.unbindService(stateServiceConnection)
            stateServiceBound = false
            vpnStateService = null
            Log.d(tag, "Unbound from VpnStateService")
        } catch (e: Exception) {
            Log.w(tag, "Failed to unbind VpnStateService: ${e.message}")
        }
    }

    private fun startLogMonitoring() {
        if (!isMonitoring.compareAndSet(false, true)) {
            Log.d(tag, "Log monitoring already active")
            return
        }

        authFailureCount.set(0)
        lastLogPosition = 0L

        logMonitorJob?.cancel()
        logMonitorJob = monitoringScope.launch {
            Log.d(tag, "Started log monitoring for auth failures")
            try {
                while (isMonitoring.get()) {
                    checkForAuthFailures()
                    delay(2000) // Check every 2 seconds
                }
            } catch (e: Exception) {
                Log.e(tag, "Log monitoring error: ${e.message}")
            } finally {
                Log.d(tag, "Log monitoring stopped")
            }
        }

        // NEW: Start periodic TUN interface monitoring
        startTunMonitoring()
    }

    private fun stopLogMonitoring(cancelActiveJob: Boolean = true) {
        isMonitoring.set(false)
        if (cancelActiveJob) {
            logMonitorJob?.cancel()
            logMonitorJob = null
        }
        tunMonitorJob?.cancel()
        tunMonitorJob = null
        authFailureCount.set(0)
        Log.d(tag, "Stopped log monitoring")
    }

    /**
     * Start periodic TUN interface monitoring to detect server-side disconnections
     */
    private fun startTunMonitoring() {
        tunMonitorJob?.cancel()
        tunMonitorJob = monitoringScope.launch {
            Log.d(tag, "Started periodic TUN interface monitoring")
            try {
                while (isMonitoring.get() && _state.value is ConnectionState.Connected) {
                    delay(3000) // Check every 3 seconds

                    // Check if TUN interface is still active
                    val tunActive = checkIfVpnIsActive()

                    if (!tunActive && _state.value is ConnectionState.Connected) {
                        Log.e(tag, "TUN interface disappeared - VPN connection lost")
                        withContext(Dispatchers.Main) {
                            _state.value = ConnectionState.Disconnected
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "TUN monitoring error: ${e.message}")
            } finally {
                Log.d(tag, "TUN monitoring stopped")
            }
        }
    }

    /**
     * Stop periodic TUN interface monitoring
     */
    private fun stopTunMonitoring() {
        tunMonitorJob?.cancel()
        tunMonitorJob = null
    }

    override suspend fun getConnectionLogs(context: Context): String? {
        return withContext(Dispatchers.IO) {
            try {
                val logFile = File(context.filesDir, "charon.log")
                if (!logFile.exists()) {
                    Log.w(tag, "charon.log does not exist")
                    return@withContext null
                }

                // Read the entire log file
                val logs = StringBuilder()
                BufferedReader(InputStreamReader(FileInputStream(logFile))).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        logs.append(line).append("\n")
                    }
                }

                val result = logs.toString()
                Log.d(tag, "Retrieved ${result.length} characters from charon.log")
                result
            } catch (e: Exception) {
                Log.e(tag, "Failed to read charon.log: ${e.message}")
                null
            }
        }
    }

    private suspend fun checkForAuthFailures() {
        try {
            val logFile = File(appContext.filesDir, "charon.log")
            if (!logFile.exists()) return

            val currentLength = logFile.length()
            if (currentLength < lastLogPosition) {
                // Log file was rotated/truncated
                lastLogPosition = 0L
            }

            if (currentLength == lastLogPosition) {
                // No new content
                return
            }

            // Read only new content
            val newContent = StringBuilder()
            BufferedReader(InputStreamReader(FileInputStream(logFile))).use { reader ->
                reader.skip(lastLogPosition)
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    newContent.append(line).append("\n")
                }
            }
            lastLogPosition = currentLength

            val logs = newContent.toString()
            if (logs.isEmpty()) return

            // Check for authentication failures and certificate issues
            val hasAuthFailed = logs.contains("AUTH_FAILED", ignoreCase = true) ||
                               logs.contains("authentication failed", ignoreCase = true)
            val hasCertStatusIssue = logs.contains("certificate status is not available", ignoreCase = true)
            val hasEapTlsFailed = logs.contains("EAP_TLS method failed", ignoreCase = true)
            val hasCertRevoked = logs.contains("certificate was revoked", ignoreCase = true) ||
                                logs.contains("certificate has been revoked", ignoreCase = true)
            val hasIkeAuthFailed = logs.contains("IKE_SA.*failed", ignoreCase = true)
            val hasCertUnknown = logs.contains("certificate unknown", ignoreCase = true)

            // NEW: Detect ACTUAL failures vs temporary network issues
            // MOBIKE (IKEv2 mobility) can handle network changes - don't force disconnect on temporary issues
            val hasMobikeUpdate = logs.contains("MOBIKE update", ignoreCase = true) ||
                                  logs.contains("old path is not available", ignoreCase = true) ||
                                  logs.contains("looking for a route", ignoreCase = true)

            // Only treat as fatal if we're giving up, not if MOBIKE is working
            val hasPeerNotResponding = (logs.contains("giving up after", ignoreCase = true) ||
                                       logs.contains("establishing IKE_SA failed", ignoreCase = true)) &&
                                       !hasMobikeUpdate  // Don't force disconnect if MOBIKE is active

            if (hasPeerNotResponding) {
                Log.e(tag, "Detected connectivity failure (peer not responding / unreachable) - forcing disconnect")
                withContext(Dispatchers.Main) {
                    handleConnectionFailure("Remote peer not responding")
                }
                return
            }

            // Log MOBIKE activity but don't treat as failure
            if (hasMobikeUpdate) {
                Log.d(tag, "MOBIKE network path update in progress - allowing reconnection")
            }

            if (hasAuthFailed || hasCertStatusIssue || hasEapTlsFailed || hasCertRevoked || hasIkeAuthFailed || hasCertUnknown) {
                authFailureCount.incrementAndGet()
                Log.w(tag, "Detected auth failure signal (count: ${authFailureCount.get()}): " +
                          "AUTH_FAILED=$hasAuthFailed, CERT_STATUS=$hasCertStatusIssue, " +
                          "EAP_TLS_FAILED=$hasEapTlsFailed, CERT_REVOKED=$hasCertRevoked, CERT_UNKNOWN=$hasCertUnknown")

                // CRITICAL: EAP_TLS failure with AUTH_FAILED means immediate connection failure
                // Don't wait for threshold if we have clear authentication rejection
                // Also treating regular AUTH_FAILED as critical for immediate revocation response
                if ((hasEapTlsFailed && hasAuthFailed) || hasCertRevoked || hasCertUnknown || hasAuthFailed) {
                    Log.e(tag, "Critical authentication failure detected - forcing aggressive disconnect")

                    // CRITICAL FIX: Clear the VPN profile BEFORE stopping service
                    // This prevents CharonVpnService from auto-reconnecting with the same profile
                    try {
                        val clearIntent = Intent(appContext, CharonVpnService::class.java)
                        clearIntent.action = CharonVpnService.DISCONNECT_ACTION
                        appContext.startService(clearIntent)
                        delay(100) // Give service time to clear profile
                        Log.d(tag, "Profile cleared - preventing auto-reconnect")
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to clear profile: ${e.message}")
                    }

                    // IMMEDIATELY stop the CharonVpnService to tear down the tunnel
                    // This prevents the rapid reconnection loop
                    try {
                        val stopIntent = Intent(appContext, CharonVpnService::class.java)
                        appContext.stopService(stopIntent)
                        Log.d(tag, "Emergency stop: CharonVpnService stopped due to auth failure")
                    } catch (e: Exception) {
                        Log.e(tag, "Failed emergency stop: ${e.message}")
                    }

                    withContext(Dispatchers.Main) {
                        handleAuthenticationFailure()
                    }
                } else if (authFailureCount.get() >= 2) {
                    // For other auth failures, still use threshold
                    Log.e(tag, "Authentication failure threshold reached - forcing disconnect")
                    withContext(Dispatchers.Main) {
                        handleAuthenticationFailure()
                    }
                }
            }

            // Also check if connection was actually established but then dropped
            if (logs.contains("connection-closed", ignoreCase = true) ||
                logs.contains("IKE_SA deleted", ignoreCase = true)) {
                Log.w(tag, "Connection closed detected in logs")
                if (_state.value is ConnectionState.Connected) {
                    withContext(Dispatchers.Main) {
                        _state.value = ConnectionState.Disconnected
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(tag, "Error checking auth failures: ${e.message}")
        }
    }

    private fun handleAuthenticationFailure() {
        stopLogMonitoring(cancelActiveJob = false)
        _state.value = ConnectionState.Error("Authentication failed: Traffic limit exceeded or certificate invalid")

        // Fire persistent notification/toast even if UI is backgrounded
        if (dataLimitNotificationShown.compareAndSet(false, true)) {
            try {
                VpnNotificationManager.showDataLimitExceeded(appContext)
                Toast.makeText(appContext, "VPN disconnected: data limit exceeded", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e(tag, "Failed to show data-limit notification", e)
            }
        }

        // NOTE: Notification will be shown by ViewModel based on error message
        // This allows proper detection of data limit vs certificate issues

        // Give UI a moment to show error, then transition to disconnected
        monitoringScope.launch {
            delay(500)
            _state.value = ConnectionState.Disconnected

            // Force cleanup
            try {
                disconnect(appContext)
            } catch (e: Exception) {
                Log.e(tag, "Error during forced disconnect: ${e.message}")
            }
        }
    }

    private fun handleConnectionFailure(message: String) {
        stopLogMonitoring(cancelActiveJob = false)
        _state.value = ConnectionState.Error(message)
        monitoringScope.launch {
            delay(300)
            _state.value = ConnectionState.Disconnected
            try {
                disconnect(appContext)
            } catch (e: Exception) {
                Log.e(tag, "Error during forced disconnect: ${e.message}")
            }
        }
    }

    suspend fun connect(context: Context, profile: VpnProfile): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // CRITICAL: Check state BEFORE trying to acquire lock
                // This ensures we reject attempts even if lock is available
                val currentState = _state.value
                if (currentState is ConnectionState.Connecting || currentState is ConnectionState.Connected) {
                    Log.w(tag, "Already connecting or connected (state=$currentState)")
                    return@withContext false
                }

                // Acquire the connection lock
                if (!isConnecting.compareAndSet(false, true)) {
                    Log.w(tag, "Connection attempt already in progress (lock held)")
                    return@withContext false
                }

                // Double-check state after acquiring lock (prevent race condition)
                val stateAfterLock = _state.value
                if (stateAfterLock is ConnectionState.Connecting || stateAfterLock is ConnectionState.Connected) {
                    Log.w(tag, "State changed to $stateAfterLock after acquiring lock, aborting")
                    isConnecting.set(false)
                    return@withContext false
                }

                _state.value = ConnectionState.Connecting
                dataLimitNotificationShown.set(false)
                Log.d(tag, "Starting IKEv2 connection with VpnProfile object")

                // Reset auth failure tracking
                authFailureCount.set(0)
                lastLogPosition = 0L

                // Clear the log file to ensure we only check new logs from this connection attempt
                try {
                    val logFile = File(appContext.filesDir, "charon.log")
                    if (logFile.exists()) {
                        logFile.delete()
                        Log.d(tag, "Cleared old charon.log before new connection attempt")
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Failed to clear log file: ${e.message}")
                }

                currentProfile = profile
                Log.d(tag, "Using VpnProfile: ${profile.name}, Gateway: ${profile.gateway}, Type: ${profile.vpnType}")

                // Start CharonVpnService with the profile; pass only present extras
                val intent = Intent(appContext, CharonVpnService::class.java).apply {
                    val bundle = Bundle().apply {
                        putString(VpnProfileDataSource.KEY_UUID, profile.getUUID().toString())
                         // Only pass credentials for EAP/EAP-TLS variants
                         if (profile.vpnType == VpnType.IKEV2_EAP || profile.vpnType == VpnType.IKEV2_EAP_TLS || profile.vpnType == VpnType.IKEV2_CERT_EAP) {
                            profile.password?.takeIf { it.isNotBlank() }?.let { putString(VpnProfileDataSource.KEY_PASSWORD, it) }
                            profile.remoteId?.takeIf { it.isNotBlank() }?.let { putString("remoteId", it) }
                         } else {
                            // In certificate-only mode, do not pass EAP credentials to avoid prompts
                            profile.remoteId?.takeIf { it.isNotBlank() }?.let { putString("remoteId", it) }
                         }

                        // Authentication type logging
                        Log.d(tag, "Auth mode: ${profile.vpnType}")

                        // If we have a P12 certificate alias, add it
                        profile.userCertificateAlias?.let { alias ->
                            putString("certificate_alias", alias)
                            Log.d(tag, "Added certificate alias (certificate_alias): $alias")
                        }

                        // Add username only for EAP variants
                        if (profile.vpnType == VpnType.IKEV2_EAP || profile.vpnType == VpnType.IKEV2_EAP_TLS || profile.vpnType == VpnType.IKEV2_CERT_EAP) {
                            profile.username?.takeIf { it.isNotBlank() }?.let {
                                putString("username", it)
                                Log.d(tag, "Set username for VPN connection: ${it}")
                            }
                        }
                    }
                    putExtras(bundle)
                    Log.d(tag, "Starting CharonVpnService with profile UUID: ${profile.getUUID()}")
                    Log.d(tag, "VPN Type: ${profile.vpnType}, Gateway: ${profile.gateway}")
                    Log.d(tag, "Certificate alias: ${profile.userCertificateAlias}")
                }

                // The VpnProfile needs to be in the database for CharonVpnService to find it
                val dsObj = VpnProfileSource(appContext)
                val dataSource = try {
                    val m = dsObj.javaClass.getMethod("open")
                    val ret = m.invoke(dsObj)
                    (ret as? org.strongswan.android.data.VpnProfileDataSource) ?: dsObj
                } catch (_: Throwable) { dsObj }

                 // Clean up profile values minimally; do NOT overwrite server/user-specific fields
                 profile.gateway = profile.gateway?.trim()?.replace("\n", "")?.replace("\r", "")
                 profile.name = profile.name?.trim()?.replace("\n", "")?.replace("\r", "")
                 profile.username = profile.username?.trim()?.replace("\n", "")?.replace("\r", "")
                 profile.remoteId = profile.remoteId?.trim()?.replace("\n", "")?.replace("\r", "")

                 // Avoid assigning null to proposal fields; empty string prevents SettingsWriter newline issues
                 profile.ikeProposal = profile.ikeProposal ?: ""
                 profile.espProposal = profile.espProposal ?: ""
                 profile.splitTunneling = profile.splitTunneling // leave as provided
                 // Optionally set a safe MTU if not set
                 if (profile.mtu == 0) profile.mtu = 1400

                 // Ensure password is preserved when saving to database
                 Log.d(tag, "Profile fields ready; saving to database (alias=${profile.userCertificateAlias})")

                val existingProfile = try {
                    dataSource.getVpnProfile(profile.getUUID().toString())
                } catch (_: Throwable) {
                    try {
                        val gm = dataSource.javaClass.getMethod("getVpnProfile", java.util.UUID::class.java)
                        gm.invoke(dataSource, profile.getUUID()) as? VpnProfile
                    } catch (_: Throwable) { null }
                }
                if (existingProfile == null) {
                    try {
                        dataSource.javaClass.getMethod("insertProfile", VpnProfile::class.java).invoke(dataSource, profile)
                    } catch (_: Throwable) {}
                    Log.d(tag, "Inserted VPN profile ${profile.name}")
                } else {
                    // Merge critical fields
                    existingProfile.password = profile.password
                    existingProfile.userCertificateAlias = profile.userCertificateAlias
                    existingProfile.vpnType = profile.vpnType
                    existingProfile.gateway = profile.gateway
                    existingProfile.name = profile.name
                    existingProfile.username = profile.username
                    existingProfile.remoteId = profile.remoteId
                    existingProfile.ikeProposal = profile.ikeProposal
                    existingProfile.espProposal = profile.espProposal
                    existingProfile.splitTunneling = profile.splitTunneling
                    if (existingProfile.mtu == 0 && profile.mtu != 0) existingProfile.mtu = profile.mtu
                    try {
                        dataSource.javaClass.getMethod("updateVpnProfile", VpnProfile::class.java).invoke(dataSource, existingProfile)
                    } catch (_: Throwable) {}
                    Log.d(tag, "Updated existing VPN profile ${existingProfile.name}")
                }

                // Verify key fields after database save
                val savedProfile = try {
                    dataSource.getVpnProfile(profile.getUUID().toString())
                } catch (_: Throwable) {
                    try {
                        val gm = dataSource.javaClass.getMethod("getVpnProfile", java.util.UUID::class.java)
                        gm.invoke(dataSource, profile.getUUID()) as? VpnProfile
                    } catch (_: Throwable) { null }
                }
                Log.d(tag, "Saved profile alias=${savedProfile?.userCertificateAlias} gateway=${savedProfile?.gateway} remoteId=${savedProfile?.remoteId}")

                try { dsObj.close() } catch (_: Throwable) {}

                // Ensure VpnStateService is bound before starting connection
                if (!stateServiceBound) {
                    bindVpnStateService(appContext)
                    delay(500) // Give it time to bind
                }

                // Preflight: Validate access to user certificate/key (supports local: aliases)
                 try {
                     val alias = profile.userCertificateAlias
                     if (alias.isNullOrBlank()) {
                         _state.value = ConnectionState.Error("No client certificate selected. Please select a user certificate.")
                         return@withContext false
                     }
                     if (alias.startsWith("local:")) {
                        val mgr = LocalCertificateKeyStoreManager(appContext)
                        val hasCert = mgr.isCertificateAvailable(alias)
                        val key = mgr.getPrivateKey(alias)
                        if (!hasCert || key == null) {
                            _state.value = ConnectionState.Error("Client certificate/key not accessible (local store). Re-import certificate or grant access.")
                            return@withContext false
                        }
                        Log.d(tag, "[CertFlow] Pre-flight Local OK: hasCert=$hasCert hasKey=${key != null}")
                     } else {
                        val chain = KeyChain.getCertificateChain(appContext, alias)
                        val key = try { KeyChain.getPrivateKey(appContext, alias) } catch (e: KeyChainException) { null }
                        if (chain == null || chain.isEmpty() || key == null) {
                            _state.value = ConnectionState.Error("Client certificate/key not accessible. Re-select certificate and grant access.")
                            return@withContext false
                        }
                        Log.d(tag, "[CertFlow] Pre-flight KeyChain OK: chain=${chain.size} hasKey=${key != null}")
                     }
                 } catch (e: Exception) {
                     Log.w(tag, "KeyChain preflight failed: ${e.message}")
                 }

                // Ensure VPN permission was granted; otherwise charon cannot initialize
                try {
                    val prep = VpnService.prepare(appContext)
                    if (prep != null) {
                        // UI must launch VpnService.prepare() intent (Android 10+), especially on Android 14.
                        _state.value = ConnectionState.Error("VPN permission required. Tap Connect again and approve the VPN dialog.")
                        Log.w(tag, "VPN permission not granted (VpnService.prepare returned an Intent)")
                        return@withContext false
                    }
                } catch (e: Exception) {
                    Log.w(tag, "VpnService.prepare check failed: ${e.message}")
                }

                // Start service without FGS API to avoid 5s timeout; service will call startForeground() itself
                appContext.startService(intent)

                Log.d(tag, "CharonVpnService started, waiting for connection result...")

                // Phase 0: Wait for charon to initialize to avoid premature timeout
                var charonStarted = false
                for (i in 1..24) { // ~6 seconds total
                    delay(250)
                    val logs = getConnectionLogs(context) ?: ""
                    if (logs.contains("charon started", ignoreCase = true)) {
                        charonStarted = true
                        Log.d(tag, "Detected 'charon started' in logs (${i}/24)")
                        break
                    }
                    if (logs.contains("failed to load user certificate and key", ignoreCase = true)) {
                        Log.e(tag, "Charon reported: failed to load user certificate and key")
                        _state.value = ConnectionState.Error("Client certificate not available. Please re-select the user certificate.")
                        disconnect(context)
                        currentProfile = null
                        return@withContext false
                    }
                }
                if (!charonStarted) {
                    Log.w(tag, "charon did not report 'started' within expected time; continuing with auth checks")
                }

                // DON'T start background monitoring yet - we'll check synchronously
                // This prevents race conditions with state updates

                // Wait for connection to establish or fail
                // We need to wait longer for auth to complete before checking TUN
                var connectionEstablished = false
                var authFailureDetected = false
                var peerUnreachableDetected = false
                var ikeEstablished = false
                var childEstablished = false

                // Phase 1: Wait for authentication to complete (first 4 seconds)
                // During this phase, DON'T treat plain TUN presence as success
                for (i in 1..16) { // extend to ~8 seconds
                     delay(500) // Check every 500ms for faster failure detection

                     // Check logs for authentication and connectivity failures
                     val logs = getConnectionLogs(context) ?: ""

                    if (logs.contains("failed to load user certificate and key", ignoreCase = true)) {
                        Log.e(tag, "Charon reported: failed to load user certificate and key (auth phase)")
                        _state.value = ConnectionState.Error("Client certificate not available. Please re-select the user certificate.")
                        break
                    }

                    // Connectivity failures
                    if (logs.contains("peer not responding", ignoreCase = true) ||
                        logs.contains("giving up after", ignoreCase = true) ||
                        logs.contains("UNREACHABLE", ignoreCase = true) ||
                        logs.contains("establishing IKE_SA failed", ignoreCase = true)) {
                        peerUnreachableDetected = true
                        Log.e(tag, "Connectivity failure detected in logs (check ${i}/8)")
                        break
                    }

                    // Auth failures
                    if (logs.contains("certificate unknown", ignoreCase = true) ||
                        logs.contains("certificate was revoked", ignoreCase = true) ||
                        logs.contains("certificate has been revoked", ignoreCase = true) ||
                        (logs.contains("EAP_TLS method failed", ignoreCase = true) && logs.contains("AUTH_FAILED", ignoreCase = true)) ||
                        logs.contains("AUTH_FAILED", ignoreCase = true)) {
                        authFailureDetected = true
                        Log.e(tag, "Authentication failure detected in logs (check ${i}/8)")
                        break
                    }

                    // Success signals
                    if (!ikeEstablished && logs.contains("IKE_SA", ignoreCase = true) && logs.contains("established", ignoreCase = true)) {
                        ikeEstablished = true
                        Log.d(tag, "IKE_SA established detected (check ${i}/8)")
                    }
                    if (!childEstablished && logs.contains("CHILD_SA", ignoreCase = true) && logs.contains("established", ignoreCase = true)) {
                        childEstablished = true
                        Log.d(tag, "CHILD_SA established detected (check ${i}/8)")
                    }

                    if (ikeEstablished && childEstablished) {
                        // Verify TUN is active as a secondary confirmation
                        if (checkIfVpnIsActive()) {
                            connectionEstablished = true
                            Log.d(tag, "IKE/CHILD established and TUN confirmed (check ${i}/8)")
                            break
                        }
                    }

                    Log.v(tag, "Auth phase check ${i}/8: Waiting for authentication to complete...")
                }

                // Phase 2: If no clear result yet, do a final verification (2 more seconds)
                if (!authFailureDetected && !peerUnreachableDetected && !connectionEstablished) {
                    Log.d(tag, "Auth phase complete, performing final verification...")
                    delay(1000)

                    val finalLogs = getConnectionLogs(context) ?: ""

                    if (finalLogs.contains("failed to load user certificate and key", ignoreCase = true)) {
                        Log.e(tag, "Charon reported: failed to load user certificate and key (final check)")
                        _state.value = ConnectionState.Error("Client certificate not available. Please re-select the user certificate.")
                        disconnect(context)
                        currentProfile = null
                        return@withContext false
                    }

                    // Connectivity failures
                    if (finalLogs.contains("peer not responding", ignoreCase = true) ||
                        finalLogs.contains("giving up after", ignoreCase = true) ||
                        finalLogs.contains("UNREACHABLE", ignoreCase = true) ||
                        finalLogs.contains("establishing IKE_SA failed", ignoreCase = true)) {
                        peerUnreachableDetected = true
                        Log.e(tag, "Connectivity failure found in final log check")
                    }

                    // Final check for any auth failures
                    if (!peerUnreachableDetected && (finalLogs.contains("AUTH_FAILED", ignoreCase = true) ||
                        finalLogs.contains("authentication failed", ignoreCase = true) ||
                        finalLogs.contains("certificate unknown", ignoreCase = true) ||
                        finalLogs.contains("EAP_TLS method failed", ignoreCase = true))) {
                        authFailureDetected = true
                        Log.e(tag, "Authentication failure found in final log check")
                    } else if (!peerUnreachableDetected && !authFailureDetected) {
                        // Re-evaluate establishment flags in final logs
                        if (!ikeEstablished && finalLogs.contains("IKE_SA", ignoreCase = true) && finalLogs.contains("established", ignoreCase = true)) {
                            ikeEstablished = true
                        }
                        if (!childEstablished && finalLogs.contains("CHILD_SA", ignoreCase = true) && finalLogs.contains("established", ignoreCase = true)) {
                            childEstablished = true
                        }
                        if (ikeEstablished && childEstablished && checkIfVpnIsActive()) {
                            connectionEstablished = true
                            Log.d(tag, "IKE/CHILD established and TUN confirmed in final check")
                        } else {
                            Log.e(tag, "Success criteria not met in final check (IKE=$ikeEstablished CHILD=$childEstablished TUN=${checkIfVpnIsActive()})")
                        }
                    }
                }

                // Evaluate final connection status
                if (peerUnreachableDetected) {
                    _state.value = ConnectionState.Error("Remote peer not responding")
                    Log.d(tag, "Forcing aggressive VPN cleanup due to auth failure")

                    // Method 1: Send disconnect action BEFORE calling disconnect()
                    try {
                        val disconnectIntent = Intent(appContext, CharonVpnService::class.java).apply {
                            action = CharonVpnService.DISCONNECT_ACTION
                        }
                        appContext.startService(disconnectIntent)
                        Log.d(tag, "Sent immediate disconnect intent to CharonVpnService")
                    } catch (e: Exception) {
                        Log.w(tag, "Failed to send disconnect intent: ${e.message}")
                    }

                    // Method 2: Stop the service directly
                    try {
                        val stopIntent = Intent(appContext, CharonVpnService::class.java)
                        appContext.stopService(stopIntent)
                        Log.d(tag, "Sent stop service intent to CharonVpnService")
                    } catch (e: Exception) {
                        Log.w(tag, "Failed to stop service: ${e.message}")
                    }

                    // Give services time to respond
                    delay(500)

                    // Method 3: Now call full disconnect procedure
                    try {
                        disconnect(context)
                    } catch (e: Exception) {
                        Log.e(tag, "Error during disconnect: ${e.message}")
                    }

                    // Method 4: Wait and verify TUN interface is down
                    delay(500)
                    val tunStillActive = checkIfVpnIsActive()
                    if (tunStillActive) {
                        Log.e(tag, "TUN interface still active after disconnect attempts, forcing additional cleanup")

                        // Try alternative disconnect approaches
                        try {
                            // Send multiple disconnect signals
                            repeat(3) {
                                val intent = Intent(appContext, CharonVpnService::class.java).apply {
                                    action = CharonVpnService.DISCONNECT_ACTION
                                }
                                appContext.startService(intent)
                                delay(200)
                            }

                            // Force stop service
                            val forceStop = Intent(appContext, CharonVpnService::class.java)
                            appContext.stopService(forceStop)

                            Log.d(tag, "Sent additional disconnect signals")
                        } catch (e: Exception) {
                            Log.e(tag, "Failed additional cleanup: ${e.message}")
                        }
                    }

                    _state.value = ConnectionState.Disconnected
                    currentProfile = null
                    return@withContext false

                } else if (connectionEstablished) {
                    _state.value = ConnectionState.Connected
                    Log.d(tag, "Connection established successfully")

                    // NOW start background monitoring to detect disconnections
                    startLogMonitoring()
                    return@withContext true

                } else {
                    Log.e(tag, "Connection timeout: Unable to establish VPN tunnel")
                    _state.value = ConnectionState.Error("Connection timeout: Unable to establish VPN tunnel")

                    delay(500)
                    disconnect(context)
                    _state.value = ConnectionState.Disconnected
                    currentProfile = null
                    return@withContext false
                }

            } catch (ex: Exception) {
                 Log.e(tag, "Connect failed", ex)
                 stopLogMonitoring()
                 _state.value = ConnectionState.Error("Connect failed: ${ex.message}")
                 _state.value = ConnectionState.Disconnected
                 currentProfile = null
                 false
            } finally {
                // Release the connection lock
                isConnecting.set(false)
            }
        }
    }

    override suspend fun connect(context: Context, configPath: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val configFile = File(configPath)
                if (!configFile.exists()) {
                    throw IllegalArgumentException("Config file does not exist: $configPath")
                }
                val configContent = configFile.readText()
                Log.d(tag, "Config content length: ${configContent.length}")

                var profile: VpnProfile? = configManager.parseServerResponseToProfile(configContent)
                if (profile == null) {
                    Log.d(tag, "Not JSON, attempting .conf parse fallback")
                    profile = parseStrongSwanConf(configContent)
                    if (profile != null) {
                        // Try to upgrade to richer stored profile if exists (same gateway)
                        val ds = VpnProfileSource(appContext)
                        ds.open()
                        try {
                            val existing = ds.allVpnProfiles.firstOrNull { it.gateway == profile.gateway }
                            if (existing != null) {
                                Log.d(tag, "Found existing stored profile for gateway; reusing its auth + type")
                                existing.name = existing.name ?: profile.name
                                profile = existing
                            }
                        } catch (e: Exception) {
                            Log.w(tag, "Failed to lookup existing profiles: ${e.message}")
                        } finally { ds.close() }
                    }
                }
                if (profile == null) throw IllegalArgumentException("Unsupported config format; could not parse configuration")
                connect(context, profile)
            } catch (ex: Exception) {
                Log.e(tag, "Connect failed", ex)
                _state.value = ConnectionState.Error("Connect failed: ${ex.message}")
                _state.value = ConnectionState.Disconnected
                currentProfile = null
                false
            }
        }
    }

    private fun parseStrongSwanConf(content: String): VpnProfile? {
        return try {
            val lines = content.lines()
            val map = mutableMapOf<String, String>()
            lines.forEach { raw ->
                val line = raw.trim()
                if (line.startsWith("#") || line.isBlank()) return@forEach
                if (line.startsWith("conn ")) {
                    map["conn"] = line.removePrefix("conn").trim()
                    return@forEach
                }
                val parts = line.split('=', limit = 2)
                if (parts.size == 2) {
                    val key = parts[0].trim().lowercase()
                    val value = parts[1].trim()
                    map[key] = value
                }
            }
            val gateway = map["right"] ?: map["righthost"] ?: map["rightaddress"]
            val leftauth = map["leftauth"]?.lowercase() ?: ""
            val rightauth = map["rightauth"]?.lowercase() ?: ""
            val profile = VpnProfile().apply {
                name = map["conn"].takeUnless { it.isNullOrBlank() } ?: "LibreGuard Config"
                this.gateway = gateway ?: ""
                remoteId = map["rightid"] ?: this.gateway
                username = map["leftid"] ?: map["eap_identity"]
                vpnType = when {
                    leftauth.contains("pubkey") || rightauth.contains("pubkey") || leftauth.contains("rsasig") || rightauth.contains("rsasig") -> VpnType.IKEV2_CERT
                    leftauth.contains("tls") || rightauth.contains("tls") -> VpnType.IKEV2_EAP_TLS
                    leftauth.contains("eap") || rightauth.contains("eap") -> VpnType.IKEV2_EAP
                    else -> VpnType.IKEV2_CERT
                }
            }
            if (profile.gateway.isBlank()) {
                Log.w(tag, ".conf parse produced empty gateway")
            }
            profile
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse .conf: ${e.message}")
            null
        }
    }

    override suspend fun disconnect(context: Context): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Stop log monitoring first
                stopLogMonitoring()

                // Force disconnect even if state says disconnected, to handle app restart scenarios
                // where handler state is out of sync with actual service state
                if (_state.value is ConnectionState.Disconnected) {
                    Log.d(tag, "Handler state is Disconnected, but proceeding with disconnect to ensure cleanup")
                }

                _state.value = ConnectionState.Disconnecting
                Log.d(tag, "Disconnecting from VPN")

                var disconnectSuccess = false

                // Method 0: CRITICAL - Clear profile FIRST to prevent auto-reconnect
                try {
                    val clearIntent = Intent(appContext, CharonVpnService::class.java).apply {
                        action = CharonVpnService.DISCONNECT_ACTION
                    }
                    appContext.startService(clearIntent)
                    delay(100) // Give service time to clear profile
                    Log.d(tag, "Profile cleared to prevent auto-reconnect")
                } catch (e: Exception) {
                    Log.w(tag, "Failed to clear profile: ${e.message}")
                }

                // Method 1: Try to stop CharonVpnService directly (MOST IMPORTANT - DO THIS SECOND)
                try {
                    val stopIntent = Intent(appContext, CharonVpnService::class.java)
                    val stopResult = appContext.stopService(stopIntent)
                    Log.d(tag, "Stop service result: $stopResult")
                    // Assume success if we stopped the service, but keep trying other methods just in case
                    disconnectSuccess = true
                } catch (e: Exception) {
                    Log.w(tag, "Failed to stop CharonVpnService: ${e.message}")
                }

                // Method 2: Try multiple disconnect action approaches (backup)
                 try {
                    // Try with explicit in-app CharonVpnService disconnect action
                     val disconnectIntent1 = Intent(appContext, CharonVpnService::class.java).apply {
                         action = CharonVpnService.DISCONNECT_ACTION
                     }
                     appContext.startService(disconnectIntent1)
                     Log.d(tag, "Sent explicit disconnect action (backup)")

                    // Also try generic disconnect action (fallback)
                     val disconnectIntent2 = Intent(appContext, CharonVpnService::class.java).apply {
                         action = "disconnect"
                     }
                     appContext.startService(disconnectIntent2)
                     Log.d(tag, "Sent generic disconnect action")

                     disconnectSuccess = true
                 } catch (e: Exception) {
                     Log.w(tag, "Failed to send disconnect intents: ${e.message}")
                 }

                // Method 3: Try to kill the VPN connection via VpnService if we have permission
                try {
                    // Check if we can prepare VPN service (means we have permission)
                    val vpnPrepareIntent = VpnService.prepare(context)
                    if (vpnPrepareIntent == null) {
                        Log.d(tag, "VPN service prepared, attempting to revoke VPN")
                        // We have VPN permission, but we can't directly revoke another app's VPN
                        // However, stopping the service should work
                    }
                } catch (e: Exception) {
                    Log.w(tag, "VPN service check failed: ${e.message}")
                }

                // Method 4: Clear StrongSwan state files to force cleanup
                try {
                    val charonLog = File(context.filesDir, "charon.log")
                    if (charonLog.exists()) {
                        charonLog.delete()
                        Log.d(tag, "Cleared charon.log file")
                    }

                    val configDir = File(context.filesDir, "sswan_configs")
                    if (configDir.exists()) {
                        configDir.listFiles()?.forEach { file ->
                            if (file.name.endsWith(".conf") || file.name.endsWith(".p12")) {
                                file.delete()
                                Log.d(tag, "Cleared config file: ${file.name}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Failed to clear state files: ${e.message}")
                }

                // Give some time for the disconnect actions to take effect
                delay(1000)

                _state.value = ConnectionState.Disconnected
                currentProfile = null

                Log.d(tag, "VPN disconnect process completed (success: $disconnectSuccess)")
                return@withContext true // Always return true since we've cleaned up our state

            } catch (e: Exception) {
                Log.e(tag, "Failed to disconnect", e)
                stopLogMonitoring()
                _state.value = ConnectionState.Error("Disconnect failed: ${e.message}")
                false
            }
        }
    }

    /**
     * Set the current profile and update connection state accordingly
     * This is used when restoring connection state after app restart
     */
    fun setCurrentProfile(profile: VpnProfile) {
        currentProfile = profile

        // Check if VPN is actually active and update our state accordingly
        val isVpnActive = checkIfVpnIsActive()
        if (isVpnActive) {
            _state.value = ConnectionState.Connected
            Log.d(tag, "Set current profile and detected active VPN connection - updated state to Connected")
            // Start monitoring since we have an active connection
            startLogMonitoring()
        } else {
            _state.value = ConnectionState.Disconnected
            Log.d(tag, "Set current profile but no active VPN detected - state remains Disconnected")
        }

        Log.d(tag, "Current profile set: ${profile.name}, Gateway: ${profile.gateway}, State: ${_state.value}")
    }

    /**
     * Check if VPN is actually active by examining network interfaces
     */
    private fun checkIfVpnIsActive(): Boolean {
        return try {
            val networkInterfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (networkInterfaces.hasMoreElements()) {
                val networkInterface = networkInterfaces.nextElement()
                // Check for common VPN interface prefixes:
                // - tun: OpenVPN, WireGuard, generic VPN tunnels
                // - ipsec: IPSec/IKEv2 (StrongSwan)
                // - wg: WireGuard specific
                val isVpnInterface = (networkInterface.name.startsWith("tun") ||
                                     networkInterface.name.startsWith("ipsec") ||
                                     networkInterface.name.startsWith("wg")) &&
                                    networkInterface.isUp

                if (isVpnInterface) {
                    Log.d(tag, "Found active VPN interface: ${networkInterface.name} (up=${networkInterface.isUp})")
                    return true
                }
            }
            Log.d(tag, "No active VPN interfaces found")
            false
        } catch (e: Exception) {
            Log.w(tag, "Failed to check network interfaces: ${e.message}")
            false
        }
    }

    fun cleanup() {
        try {
            stopLogMonitoring()
            monitoringScope.cancel()
            unbindVpnStateService(appContext)
        } catch (e: Exception) {
            Log.e(tag, "Error during cleanup: ${e.message}")
        }
    }
}
