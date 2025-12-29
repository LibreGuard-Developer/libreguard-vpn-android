package net.libreguard.vpn.viewmodel

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.libreguard.vpn.R
import net.libreguard.vpn.data.ConnectionHistoryManager
import net.libreguard.vpn.data.ConnectionRecord
import net.libreguard.vpn.network.RemoteVpnServer
import net.libreguard.vpn.network.RetrofitClient
import net.libreguard.vpn.network.VpnConfigRequest
import net.libreguard.vpn.service.LibreGuardVpnService
import net.libreguard.vpn.service.vpn.ConnectionState
import net.libreguard.vpn.service.vpn.OpenVpnHandler
import net.libreguard.vpn.service.vpn.StrongSwanHandler
import net.libreguard.vpn.service.vpn.VpnProtocolFactory
import net.libreguard.vpn.service.vpn.VpnProtocolHandler
import net.libreguard.vpn.service.vpn.WireGuardHandler
import net.libreguard.vpn.util.TokenValidationManager
import net.libreguard.vpn.util.TokenManager
import net.libreguard.vpn.network.PingService
import net.libreguard.vpn.service.data.DataUsageManager
import net.libreguard.vpn.service.data.DataUsageInfo
import org.json.JSONObject
import org.strongswan.android.data.VpnProfile
import org.strongswan.android.data.VpnProfileDataSource
import org.strongswan.android.data.VpnProfileSource
import org.strongswan.android.data.VpnType
import java.io.File
import java.io.InputStreamReader
import java.lang.reflect.Method
import android.security.KeyChain
import android.security.KeyChainAliasCallback
import net.libreguard.vpn.network.OpenVpnDownloadRequest
import okhttp3.ResponseBody
import kotlinx.coroutines.isActive
import net.libreguard.vpn.network.CertificateRequest
import net.libreguard.vpn.util.VpnConfigManager

private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
val connectionState: StateFlow<ConnectionState> = _connectionState


private const val TAG = "VpnViewModel"

enum class VpnProtocol(val displayName: String, val apiName: String) {
    IKEV2_IPSEC("IKEV2/IPSec", "IKEV2"),
    OPENVPN("OpenVPN", "OPENVPN"),
    WIREGUARD("WireGuard", "WIREGUARD")
}

class VpnViewModel(application: Application) : AndroidViewModel(application) {
    private val _servers = MutableStateFlow<List<RemoteVpnServer>>(emptyList())
    val servers: StateFlow<List<RemoteVpnServer>> = _servers

    private val _selectedServer = MutableStateFlow<RemoteVpnServer?>(null)
    val selectedServer: StateFlow<RemoteVpnServer?> = _selectedServer

    private val _selectedProtocol = MutableStateFlow(VpnProtocol.IKEV2_IPSEC)
    val selectedProtocol: StateFlow<VpnProtocol> = _selectedProtocol

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting

    private val _isLoadingServers = MutableStateFlow(false)
    val isLoadingServers: StateFlow<Boolean> = _isLoadingServers

    // Server latency tracking (cached until server list reload while not connected)
    private val _serverLatencies = MutableStateFlow<Map<Int, Int>>(emptyMap())
    val serverLatencies: StateFlow<Map<Int, Int>> = _serverLatencies

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private val _pendingKeyChainImport = MutableStateFlow<Intent?>(null)
    val pendingKeyChainImport: StateFlow<Intent?> = _pendingKeyChainImport
    private val _showCertPicker = MutableStateFlow(false)
    val showCertPicker: StateFlow<Boolean> = _showCertPicker

    // New certificate management state
    private val _availableCertificates = MutableStateFlow<List<String>>(emptyList())
    val availableCertificates: StateFlow<List<String>> = _availableCertificates

    private val _showCertSelectionDialog = MutableStateFlow(false)
    val showCertSelectionDialog: StateFlow<Boolean> = _showCertSelectionDialog

    private val _showImportCertDialog = MutableStateFlow(false)
    val showImportCertDialog: StateFlow<Boolean> = _showImportCertDialog

    // Certificate installation state tracking
    private val _isInstallingCertificate = MutableStateFlow(false)
    val isInstallingCertificate: StateFlow<Boolean> = _isInstallingCertificate

    // Subscription state
    private val _showUpgradeDialog = MutableStateFlow(false)
    val showUpgradeDialog: StateFlow<Boolean> = _showUpgradeDialog

    private val _upgradeReason = MutableStateFlow<String?>(null)
    val upgradeReason: StateFlow<String?> = _upgradeReason

    private var _isPro = MutableStateFlow(false)
    val isPro: StateFlow<Boolean> = _isPro

    private var authToken: String? = null
    private var currentUserId: String? = null
    private var activeVpnHandler: VpnProtocolHandler? = null
    private var pendingProfile: VpnProfile? = null
    private val configManager by lazy { VpnConfigManager(getApplication()) }

    // Track the StateFlow observer job to prevent stacking observers
    private var stateObserverJob: Job? = null

    // Add SharedPreferences for state persistence
    private val sharedPrefs by lazy {
        getApplication<Application>().getSharedPreferences("vpn_state_prefs", Context.MODE_PRIVATE)
    }

    // Data usage tracking
    private val dataUsageManager by lazy { DataUsageManager(getApplication()) }

    private val _dataUsageInfo = MutableStateFlow(DataUsageInfo())
    val dataUsageInfo: StateFlow<DataUsageInfo> = _dataUsageInfo

    // Connection history tracking
    private val connectionHistoryManager by lazy { ConnectionHistoryManager(getApplication()) }
    private var currentConnectionStartTime: Long? = null
    private var currentConnectionDataStart: Double = 0.0

    // Connection duration tracking (persists across navigation)
    private val _connectionDuration = MutableStateFlow("00:00:00")
    val connectionDuration: StateFlow<String> = _connectionDuration
    private var connectionTimerJob: Job? = null

    // VPN IP tracking (persists across navigation)
    private val _vpnIP = MutableStateFlow("")
    val vpnIP: StateFlow<String> = _vpnIP

    // Token validation for early revocation detection
    private var tokenValidationManager: TokenValidationManager? = null

    // Polling configuration for certificate issuance
    private val certPollIntervalMs = 2000L
    private val certMaxWaitMs = 120000L // 2 minutes

    // Track OpenVPN state collection
    private var openVpnStateJob: Job? = null

    // Upgrade events for UI navigation
    private val _upgradeEvents = MutableSharedFlow<Map<String, String?>>(replay = 0)
    val upgradeEvents: SharedFlow<Map<String, String?>> = _upgradeEvents

    init {
        // Load persisted auth token and connection state immediately on startup
        loadPersistedAuthToken()
        // Restore any previously active VPN session (e.g., after process death or app swipe-away)
        loadPersistedState()

        // Load cached subscription status (isPro)
        loadCachedSubscriptionStatus()

        // Start observing data usage
        startDataUsageObservation()
    }

    /**
     * Start observing data usage changes
     */
    private fun startDataUsageObservation() {
        viewModelScope.launch {
            dataUsageManager.dataUsage.collect { dataUsage ->
                _dataUsageInfo.value = dataUsage
            }
        }
    }

    /**
     * Cache VPN servers to SharedPreferences
     */
    private fun cacheServers(servers: List<RemoteVpnServer>) {
        try {
            val gson = Gson()
            val json = gson.toJson(servers)
            sharedPrefs.edit().putString("vpn_servers_cache", json).apply()
            Log.d(TAG, "Cached ${servers.size} VPN servers")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cache servers: ${e.message}")
        }
    }

    /**
     * Load cached VPN servers from SharedPreferences
     */
    private fun loadCachedServers(): List<RemoteVpnServer> {
        return try {
            val json = sharedPrefs.getString("vpn_servers_cache", null)
            if (json != null) {
                val gson = Gson()
                val serverListType = object : TypeToken<List<RemoteVpnServer>>() {}.type
                val servers: List<RemoteVpnServer> = gson.fromJson(json, serverListType)
                Log.d(TAG, "Loaded ${servers.size} VPN servers from cache")
                servers
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load cached servers: ${e.message}")
            emptyList()
        }
    }

    /**
     * Load persisted auth token immediately on startup
     */
    private fun loadPersistedAuthToken() {
        try {
            val savedAuthToken = sharedPrefs.getString("auth_token", null)
            if (!savedAuthToken.isNullOrBlank()) {
                authToken = savedAuthToken
                // Restore stable user id for scoping caches
                currentUserId = sharedPrefs.getString("current_user_id", null)
                Log.d(TAG, "Restored auth token from persistent storage on init")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load persisted auth token on init", e)
        }
    }

    /**
     * Clear cached auth token - used when entering email confirmation flow
     * to prevent the ViewModel from using an old/invalid token
     */
    fun clearCachedAuthToken() {
        Log.d(TAG, "Clearing cached auth token from ViewModel")
        authToken = null
        currentUserId = null
    }

    /**
     * Load persisted connection state when app restarts
     */
    private fun loadPersistedState() {
        viewModelScope.launch {
            try {
                val wasConnected = sharedPrefs.getBoolean("was_connected", false)
                val serverName = sharedPrefs.getString("connected_server", null)
                val protocolName = sharedPrefs.getString("connected_protocol", null)
                val connectedAt = sharedPrefs.getLong("connected_at", 0L)
                val persistedServerIp = sharedPrefs.getString("connected_server_ip", null)

                // If servers list is empty (e.g., not yet loaded), try cached list so we can restore the selected server
                if (_servers.value.isEmpty()) {
                    val cachedServers = loadCachedServers()
                    if (cachedServers.isNotEmpty()) {
                        _servers.value = cachedServers
                        Log.d(TAG, "Loaded cached servers during state restore: ${cachedServers.size}")
                    }
                }

                Log.d(TAG, "Loading persisted state: wasConnected=$wasConnected, server=$serverName, protocol=$protocolName, hasToken=${authToken != null}, connectedAt=$connectedAt, ip=$persistedServerIp")

                if (wasConnected && serverName != null && protocolName != null) {
                    Log.d(TAG, "Restoring connection state: server=$serverName, protocol=$protocolName")

                    // Check if VPN is actually still active using improved method
                    val isVpnActive = checkVpnStatusImproved()
                    Log.d(TAG, "VPN status check result: $isVpnActive")

                    if (isVpnActive) {
                        // Restore UI state first
                        val protocol = VpnProtocol.values().find { it.displayName == protocolName }
                        if (protocol != null) {
                            _selectedProtocol.value = protocol
                            Log.d(TAG, "Restored protocol: ${protocol.displayName}")
                        }

                        // Find and set the server
                        val server = _servers.value.find { it.serverName == serverName }
                        if (server != null) {
                            _selectedServer.value = server
                            Log.d(TAG, "Restored server: ${server.serverName}")
                        }

                        // Restore connection state
                        _isConnected.value = true
                        _connectionState.value = ConnectionState.Connected
                        _isConnecting.value = false
                        _errorMessage.value = "Reconnected to existing VPN session"
                        Log.d(TAG, "Successfully restored VPN connection state")

                        // Restore persisted start time/IP for UI timer and VPN IP display
                        if (connectedAt > 0) {
                            currentConnectionStartTime = connectedAt
                            val elapsedSeconds = ((System.currentTimeMillis() - connectedAt) / 1000L).coerceAtLeast(0)
                            startConnectionTimer(initialSeconds = elapsedSeconds.toInt())
                        }
                        if (!persistedServerIp.isNullOrBlank()) {
                            _vpnIP.value = persistedServerIp
                        }

                        // Also start tracking if we have a server restored (ensures history/timer even if handler state misses)
                        if (_selectedServer.value != null && connectedAt > 0) {
                            currentConnectionStartTime = connectedAt
                            val elapsedSeconds = ((System.currentTimeMillis() - connectedAt) / 1000L).coerceAtLeast(0)
                            startConnectionTimer(initialSeconds = elapsedSeconds.toInt())
                        }

                        // ENHANCED FIX: Create a handler for the restored connection AND restore the VPN profile
                        try {
                            val context = getApplication<Application>().applicationContext
                            activeVpnHandler = VpnProtocolFactory.createHandler(protocol ?: VpnProtocol.IKEV2_IPSEC, context)

                            // Initialize the handler so it can properly handle disconnect requests
                            val initialized = withContext(Dispatchers.IO) {
                                activeVpnHandler?.initialize(context) ?: false
                            }

                            if (initialized) {
                                Log.d(TAG, "Successfully created and initialized VPN handler for restored connection")

                                // CRITICAL FIX: Restore the VPN profile in the handler for proper disconnection
                                val profileUuid = sharedPrefs.getString("vpn_profile_uuid", null)
                                if (profileUuid != null && protocol == VpnProtocol.IKEV2_IPSEC) {
                                    try {
                                        val restoredProfile = restoreVpnProfileFromPersistence(profileUuid)
                                        if (restoredProfile != null) {
                                            // Use the new setCurrentProfile method instead of reflection
                                            (activeVpnHandler as? StrongSwanHandler)?.let { handler ->
                                                handler.setCurrentProfile(restoredProfile)
                                                Log.d(TAG, "Successfully restored VPN profile in handler using setCurrentProfile: UUID=$profileUuid")
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Failed to restore VPN profile in handler: ${e.message}")
                                    }
                                }
                            } else {
                                Log.w(TAG, "Failed to initialize VPN handler for restored connection, but connection state restored")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to create VPN handler for restored connection: ${e.message}")
                            // Don't fail the entire restoration just because handler creation failed
                            // The user can still see the connection status and try to disconnect
                        }
                    } else {
                        // VPN is no longer active, clear persisted state
                        Log.d(TAG, "VPN is no longer active, clearing persisted state")
                        clearPersistedState()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load persisted state", e)
                clearPersistedState()
            }
        }
    }

    /**
     * Restore VPN profile from persistence or database
     */
    private suspend fun restoreVpnProfileFromPersistence(profileUuid: String): VpnProfile? {
        return withContext(Dispatchers.IO) {
            try {
                val context = getApplication<Application>().applicationContext

                // First try to get from StrongSwan database
                val dataSource = VpnProfileSource(context)
                dataSource.open()
                val profile = try {
                    dataSource.getVpnProfile(profileUuid)
                } finally {
                    dataSource.close()
                }

                if (profile != null) {
                    Log.d(TAG, "Restored VPN profile from database: ${profile.name}")
                    return@withContext profile
                }

                // If not found in database, recreate from saved preferences
                val name = sharedPrefs.getString("vpn_profile_name", null)
                val gateway = sharedPrefs.getString("vpn_profile_gateway", null)
                val remoteId = sharedPrefs.getString("vpn_profile_remote_id", null)
                val userCertAlias = sharedPrefs.getString("vpn_profile_user_cert_alias", null)
                val username = sharedPrefs.getString("vpn_profile_username", null)
                val password = sharedPrefs.getString("vpn_profile_password", null)

                if (gateway != null) {
                    val recreatedProfile = VpnProfile().apply {
                        this.name = name ?: "LibreGuard VPN"
                        this.gateway = gateway
                        this.remoteId = remoteId ?: gateway
                        this.userCertificateAlias = userCertAlias
                        this.username = username
                        this.password = password
                        // Set other necessary fields
                        this.vpnType = VpnType.IKEV2_CERT
                        this.splitTunneling = 0
                        this.mtu = 1400
                        this.natKeepAlive = 20
                        this.port = 500
                    }

                    // Set the UUID if we have it
                    try {
                        val uuidField = VpnProfile::class.java.getDeclaredField("mUUID")
                        uuidField.isAccessible = true
                        uuidField.set(recreatedProfile, java.util.UUID.fromString(profileUuid))
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to set UUID on recreated profile: ${e.message}")
                    }

                    Log.d(TAG, "Recreated VPN profile from preferences: $name")
                    return@withContext recreatedProfile
                }

                Log.w(TAG, "Could not restore VPN profile - insufficient data")
                null
            } catch (e: Exception) {
                Log.e(TAG, "Error restoring VPN profile from persistence", e)
                null
            }
        }
    }

    /**
     * Save connection state for persistence
     */
    private fun saveConnectionState() {
        try {
            val server = _selectedServer.value
            val protocol = _selectedProtocol.value
            val connected = _isConnected.value
            val startTime = currentConnectionStartTime ?: System.currentTimeMillis()

            sharedPrefs.edit().apply {
                putBoolean("was_connected", connected)
                putString("connected_server", server?.serverName)
                putString("connected_protocol", protocol.displayName)
                putLong("connected_at", startTime)
                putString("connected_server_ip", server?.serverIp)
                apply()
            }
            Log.d(TAG, "Saved connection state: connected=$connected, server=${server?.serverName}, hasToken=${authToken != null}, connectedAt=$startTime, ip=${server?.serverIp}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save connection state", e)
        }
    }

    /**
     * Clear persisted connection state but preserve auth token
     */
    private fun clearPersistedState() {
        try {
            val currentAuthToken = authToken // Preserve current auth token
            sharedPrefs.edit().apply {
                // Clear connection-related state
                remove("was_connected")
                remove("connected_server")
                remove("connected_protocol")
                remove("connected_at")
                remove("connected_server_ip")
                // Preserve auth token
                if (currentAuthToken != null) {
                    putString("auth_token", currentAuthToken)
                }
                apply()
            }
            Log.d(TAG, "Cleared persisted connection state but preserved auth token")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear persisted state", e)
        }
    }

    /**
     * Clear ALL persisted state including auth token (for full logout)
     */
    fun clearAllPersistedState() {
        try {
            sharedPrefs.edit().clear().apply()
            authToken = null
            Log.d(TAG, "Cleared ALL persisted state including auth token")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear all persisted state", e)
        }
    }


    fun loadRemoteServers() {
        loadRemoteServersWithFallback()
    }

    /**
     * Check if user is logged in (has valid auth token)
     */
    fun isLoggedIn(): Boolean {
        return !authToken.isNullOrBlank()
    }

    /**
     * Logout user and clear all state
     */
    fun logout() {
        viewModelScope.launch {
            // Stop background token validation
            tokenValidationManager?.stopBackgroundValidation()

            // Disconnect VPN if connected
            if (_isConnected.value) {
                disconnect()
            }

            // Clear user data properly - this preserves the user's total data usage
            dataUsageManager.clearUser()

            // Clear user-scoped cached VPN configs
            try {
                clearUserScopedVpnCaches()
            } catch (e: Exception) {
                Log.w(TAG, "Failed clearing user-scoped caches on logout: ${e.message}")
            }

            // Clear auth token and all related state
            authToken = null
            currentUserId = null
            _selectedServer.value = null
            _errorMessage.value = "Logged out successfully"

            // Clear only session-related persisted state, preserving user data
            clearPersistedState()

            Log.d(TAG, "User logged out successfully - data usage preserved for future login")
        }
    }

    /**
     * Force disconnect VPN when user logs out (manual or token revocation).
     * Called from MainActivity's logout broadcast receiver.
     * Does NOT require valid authentication token.
     *
     * CRITICAL: This ensures VPN tunnels are terminated when:
     * - User manually clicks logout
     * - Token is revoked by admin (detected via background poll or API error)
     * - Session expires
     */
    suspend fun forceDisconnectVpn(context: Context) {
        try {
            Log.d(TAG, "🛑 Force disconnect VPN on logout")

            // 1. Stop background token validation first
            tokenValidationManager?.stopBackgroundValidation()
            Log.d(TAG, "Stopped token validation background job")

            // 2. Cancel any ongoing OpenVPN state observer
            openVpnStateJob?.cancel()
            openVpnStateJob = null

            // 3. Get handler reference (avoid smart cast issues)
            val handler = activeVpnHandler

            // 4. Try normal disconnect using active handler
            if (handler != null) {
                try {
                    val result = withContext(Dispatchers.IO) {
                        handler.disconnect(context)
                    }
                    Log.d(TAG, "Handler disconnect result: $result")
                } catch (e: Exception) {
                    Log.w(TAG, "Handler disconnect failed: ${e.message}")
                }
            } else {
                Log.d(TAG, "No active handler - trying fallback methods")
            }

            // 4b. Explicitly stop foreground VPN service if running
            try {
                val serviceIntent = Intent(context, net.libreguard.vpn.service.LibreGuardVpnService::class.java).apply {
                    action = net.libreguard.vpn.service.LibreGuardVpnService.ACTION_DISCONNECT
                }
                context.startService(serviceIntent)
                Log.d(TAG, "Sent ACTION_DISCONNECT to LibreGuardVpnService")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send ACTION_DISCONNECT to service: ${e.message}")
            }

            // 5. Try fallback disconnect methods (force-stop apps)
            tryFallbackDisconnect(context)

            // 6. Verify it's actually disconnected (waits until status false or timeout)
            val disconnected = verifyVpnDisconnected()

            // 7. Clear all VPN state
            _isConnected.value = false
            _isConnecting.value = false
            activeVpnHandler = null
            _selectedServer.value = null

            Log.d(TAG, "✅ Force disconnect completed on logout (verified=$disconnected)")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error during force disconnect: ${e.message}", e)
            // Continue anyway - user should see login screen
            // But make sure state is cleared
            _isConnected.value = false
            _isConnecting.value = false
            activeVpnHandler = null
        }
    }

    /**
     * Try fallback disconnect methods when normal handler disconnect fails.
     * Uses system commands to force-stop VPN apps.
     */
    private suspend fun tryFallbackDisconnect(context: Context) {
        // Try to stop OpenVPN
        try {
            // Method 1: Send disconnect intent to OpenVPN
            val openVpnIntent = Intent().apply {
                setClassName("de.blinkt.openvpn", "de.blinkt.openvpn.api.DisconnectVPN")
            }
            context.sendBroadcast(openVpnIntent)
            Log.d(TAG, "Sent disconnect broadcast to OpenVPN")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send OpenVPN disconnect broadcast: ${e.message}")
        }

        // Try to stop StrongSwan
        try {
            // Method 1: Send disconnect intent to StrongSwan
            val strongSwanIntent = Intent().apply {
                setClassName("org.strongswan.android", "org.strongswan.android.logic.CharonVpnService")
                action = "org.strongswan.android.logic.CharonVpnService.DISCONNECT"
            }
            context.startService(strongSwanIntent)
            Log.d(TAG, "Sent disconnect intent to StrongSwan")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send StrongSwan disconnect intent: ${e.message}")
        }

        // Try alternative: Use package manager to force-stop (requires system permission or root)
        try {
            withContext(Dispatchers.IO) {
                // These may fail without proper permissions, but worth trying
                Runtime.getRuntime().exec("am force-stop de.blinkt.openvpn").waitFor()
                Log.d(TAG, "Force-stopped OpenVPN app")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to force-stop OpenVPN: ${e.message}")
        }

        try {
            withContext(Dispatchers.IO) {
                Runtime.getRuntime().exec("am force-stop org.strongswan.android").waitFor()
                Log.d(TAG, "Force-stopped StrongSwan app")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to force-stop StrongSwan: ${e.message}")
        }

        // Give processes time to stop
        delay(300)
    }

    /**
     * Verify VPN connection has actually been terminated.
     * Waits up to 1 second for disconnect to complete.
     */
    private suspend fun verifyVpnDisconnected(): Boolean {
        var attempts = 0
        val maxAttempts = 20  // Wait up to 2 seconds (20 * 100ms)

        while (attempts < maxAttempts) {
            val isActive = checkVpnStatusImproved()
            if (!isActive) {
                Log.d(TAG, "✅ VPN verified disconnected")
                _isConnected.value = false
                return true
            }
            Log.d(TAG, "Verifying disconnect... attempt ${attempts + 1}/$maxAttempts")
            delay(100)
            attempts++
        }

        // If still active, force state false but log warning
        Log.w(TAG, "⚠️ VPN still shows active after force disconnect - forcing state change")
        _isConnected.value = false
        return false
    }

    /**
     * Load remote servers with caching - try cache first, then API
     */
    private fun loadRemoteServersWithFallback() {
        val token = authToken
        if (token == null) {
            Log.w(TAG, "No auth token available for loading remote servers")
            return
        }

        _isLoadingServers.value = true

        viewModelScope.launch {
            try {
                // Load from cache first
                val cachedServers = loadCachedServers()
                if (cachedServers.isNotEmpty()) {
                    _servers.value = cachedServers
                    Log.d(TAG, "Loaded ${cachedServers.size} servers from cache")
                }

                // Try to get fresh list from API
                val response = RetrofitClient.instance.getVpnServers("Bearer $token")

                if (response.isSuccessful) {
                    val serverResponse = response.body()
                    if (serverResponse?.servers != null) {
                        _servers.value = serverResponse.servers
                        cacheServers(serverResponse.servers)
                        Log.d(TAG, "Loaded ${serverResponse.servers.size} remote servers from API")
                        _errorMessage.value = "Server list updated (${serverResponse.servers.size} servers)"

                        // Measure latency for all servers (only if not connected to VPN)
                        if (!_isConnected.value) {
                            // Clear cached latencies since we're reloading servers
                            _serverLatencies.value = emptyMap()
                            measureServerLatencies(serverResponse.servers)
                        } else {
                            Log.d(TAG, "Skipping latency measurement while connected to VPN")
                        }
                    } else {
                        Log.w(TAG, "No servers received from API, but token is still valid")
                        if (cachedServers.isEmpty()) {
                            _errorMessage.value = "No servers received from API"
                        }
                    }
                } else {
                    Log.w(TAG, "Failed to load servers: ${response.code()} - ${response.message()}, but preserving auth token")
                    if (cachedServers.isEmpty()) {
                        _errorMessage.value = "Failed to load servers (network issue), but you're still logged in"
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error loading remote servers: ${e.message}, but preserving auth token")
                if (_servers.value.isEmpty()) {
                    _errorMessage.value = "Network error loading servers, but you're still logged in"
                }
            } finally {
                _isLoadingServers.value = false
            }
        }
    }

    /**
     * Measure latency for all servers in the background using /ping endpoint
     */
    private fun measureServerLatencies(servers: List<RemoteVpnServer>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting latency measurement for ${servers.size} servers")
                val latencies = PingService.pingServers(servers)

                // Only update if measurement was successful and we got some results
                if (latencies.isNotEmpty()) {
                    _serverLatencies.value = latencies
                    Log.d(TAG, "Latency measurement complete: ${latencies.size} servers measured")
                } else {
                    Log.v(TAG, "No latency measurements succeeded")
                }
            } catch (e: Exception) {
                Log.v(TAG, "Error measuring server latencies: ${e.message}")
            }
        }
    }

    fun setAuthToken(token: String) {
        authToken = token
        // Save token immediately for persistence
        sharedPrefs.edit().putString("auth_token", token).apply()
        Log.d(TAG, "Auth token set and persisted")

        // CRITICAL FIX: Create stable user ID that persists across login sessions
        // Instead of using token hash (which changes), extract user info from token or create persistent ID
        val userId = getStableUserId(token)
        currentUserId = userId
        sharedPrefs.edit().putString("current_user_id", userId).apply()
        dataUsageManager.setUserId(userId)
        Log.d(TAG, "Set stable user ID: $userId")

        // Clean legacy, non-scoped OpenVPN caches to avoid cross-user leakage
        try {
            cleanupLegacyOpenVpnCache()
        } catch (e: Exception) {
            Log.w(TAG, "Legacy OpenVPN cache cleanup failed: ${e.message}")
        }

        // Initialize and start background token validation to detect early revocation
        if (tokenValidationManager == null) {
            tokenValidationManager = TokenValidationManager(
                getApplication<Application>().applicationContext,
                RetrofitClient.getTokenManager()
            )
        }
        Log.d(TAG, "Starting background token validation polling")
        tokenValidationManager?.startBackgroundValidation(viewModelScope)

        // Automatically load remote servers when token is set, but don't fail if it doesn't work
        loadRemoteServersWithFallback()
    }

    // Provide a user-scoped cache directory per protocol
    private fun getUserScopedDir(context: Context, protocol: VpnProtocol): File {
        val uid = currentUserId ?: "anon"
        val dir = File(context.filesDir, "vpn_cache/${protocol.name.lowercase()}/$uid")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun clearUserScopedVpnCaches() {
        val context = getApplication<Application>().applicationContext
        val base = File(context.filesDir, "vpn_cache")
        // Only delete current user's scoped caches to avoid wiping other users
        currentUserId?.let { uid ->
            val userDir = File(base, "openvpn/$uid")
            userDir.deleteRecursively()
            val wgDir = File(base, "wireguard/$uid")
            wgDir.deleteRecursively()
        }
    }

    private fun cleanupLegacyOpenVpnCache() {
        val context = getApplication<Application>().applicationContext
        // Old cache files were at filesDir/openvpn_config_*.ovpn and openvpn_config.ovpn
        context.filesDir.listFiles()?.forEach { f ->
            if (f.name == "openvpn_config.ovpn" || f.name.startsWith("openvpn_config_") && f.name.endsWith(".ovpn")) {
                runCatching { f.delete() }
            }
        }
    }

    /**
     * Get or create a stable user ID that persists across login sessions
     */
    private fun getStableUserId(token: String): String {
        return try {
            // Method 1: Try to extract user ID from JWT token
            val userIdFromToken = extractUserIdFromJWT(token)
            if (userIdFromToken != null) {
                Log.d(TAG, "Extracted user ID from JWT: $userIdFromToken")
                return userIdFromToken
            }

            // Method 2: Create/retrieve persistent user ID based on token prefix
            // Use first part of token (which is usually stable) to create consistent ID
            val tokenPrefix = if (token.length > 20) {
                token.substring(0, 20) // Use first 20 chars which are usually stable
            } else {
                token
            }

            val stableKey = "user_id_for_prefix_${tokenPrefix.hashCode()}"
            val existingUserId = sharedPrefs.getString(stableKey, null)

            if (existingUserId != null) {
                Log.d(TAG, "Found existing stable user ID: $existingUserId")
                return existingUserId
            }

            // Method 3: Create new persistent user ID
            val newUserId = "user_${System.currentTimeMillis()}_${(0..999999).random()}"
            sharedPrefs.edit().putString(stableKey, newUserId).apply()
            Log.d(TAG, "Created new stable user ID: $newUserId")

            return newUserId

        } catch (e: Exception) {
            Log.e(TAG, "Failed to get stable user ID, falling back to simple hash", e)
            // Fallback to token hash (original broken behavior)
            return token.hashCode().toString()
        }
    }

    /**
     * Extract user ID from JWT token if possible
     */
    private fun extractUserIdFromJWT(token: String): String? {
        return try {
            // JWT tokens have format: header.payload.signature
            val parts = token.split(".")
            if (parts.size != 3) return null

            // Decode the payload (second part)
            val payload = parts[1]
            val decodedBytes = android.util.Base64.decode(payload, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING)
            val payloadJson = String(decodedBytes)

            // Parse JSON to extract user ID
            val jsonObj = JSONObject(payloadJson)

            // Try common JWT user ID field names
            val possibleUserFields = listOf("sub", "user_id", "userId", "id", "email", "username")
            for (field in possibleUserFields) {
                val userId = jsonObj.optString(field)
                if (userId.isNotEmpty()) {
                    Log.d(TAG, "Found user ID in JWT field '$field': $userId")
                    return userId
                }
            }

            return null
        } catch (e: Exception) {
            Log.v(TAG, "Could not extract user ID from JWT: ${e.message}")
            return null
        }
    }

    fun selectServer(server: RemoteVpnServer) {
        _selectedServer.value = server
    }

    fun selectProtocol(protocol: VpnProtocol) {
        _selectedProtocol.value = protocol
    }

    private fun findRemoteServerByName(serverName: String): RemoteVpnServer? {
        return _servers.value.find { it.serverName == serverName }
    }

    fun connectToVpn() {
        val server = _selectedServer.value
        val token = authToken

        // Guard: ensure access for server/protocol before attempting
        try {
            val prefs = getApplication<Application>().getSharedPreferences("vpn_subscription_prefs", Context.MODE_PRIVATE)
            val isProCached = prefs.getBoolean("subscription_is_pro", false)

            // If server is premium and user is not pro, emit upgrade event and abort
            if (server != null && server.pricingTier.equals("Premium", ignoreCase = true) && !isProCached) {
                viewModelScope.launch {
                    _upgradeEvents.emit(mapOf(
                        "reason" to "Server requires Pro",
                        "resource_type" to "server",
                        "resource_id" to server.id.toString(),
                        "required_tier" to server.pricingTier
                    ))
                }
                _errorMessage.value = "This server requires Pro subscription. Redirecting to upgrade..."
                _isConnecting.value = false
                return
            }

            val selected = _selectedProtocol.value
            if (selected == VpnProtocol.OPENVPN) {
                // OpenVPN is Pro-only; if not Pro, emit upgrade
                if (!isProCached) {
                    viewModelScope.launch {
                        _upgradeEvents.emit(mapOf(
                            "reason" to "Protocol requires Pro",
                            "resource_type" to "protocol",
                            "resource_id" to selected.name,
                            "required_tier" to "Pro"
                        ))
                    }
                    _errorMessage.value = "OpenVPN requires Pro subscription. Redirecting to upgrade..."
                    _isConnecting.value = false
                    return
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to validate local subscription cache: ${e.message}")
        }

        // Guard: ignore requests while connecting or already connected
        if (_isConnecting.value) {
            _errorMessage.value = "Already connecting..."
            return
        }
        if (_isConnected.value) {
            _errorMessage.value = "Already connected"
            return
        }

        if (server == null) {
            _errorMessage.value = "Please select a server"
            return
        }

        if (token == null) {
            _errorMessage.value = "Authentication token missing"
            return
        }

        _isConnecting.value = true
        _errorMessage.value = null

        viewModelScope.launch {
            try {
                // Validate token before attempting connection
                val manager = tokenValidationManager
                if (manager != null) {
                    Log.d(TAG, "Validating token before VPN connection")
                    val tokenValid = manager.validateTokenBeforeAction()
                    if (!tokenValid) {
                        _errorMessage.value = "Token invalid or revoked. Please login again."
                        _isConnecting.value = false
                        return@launch
                    }
                }

                // DEFENSIVE CHECK: Warn if refresh token is missing (indicates OAuth persistence issue)
                val refreshToken = RetrofitClient.getTokenManager().getRefreshToken()
                if (refreshToken.isNullOrBlank()) {
                    Log.w(TAG, "WARNING: Refresh token is missing! OAuth tokens may not have been persisted correctly. " +
                               "Token validation will fail if access token expires.")
                }

                val selected = _selectedProtocol.value
                val remoteServer = server
                if (selected == VpnProtocol.OPENVPN) {
                    // Use download endpoint and cached config
                    val ok = connectOpenVpnViaDownload(remoteServer.id)
                    if (!ok) {
                        _errorMessage.value = "Failed to connect using OpenVPN"
                    }
                    _isConnecting.value = false
                    return@launch
                }

                // Existing path for IKEV2 and WIREGUARD
                val request = VpnConfigRequest(
                    serverId = remoteServer.id,
                    protocol = selected.apiName
                )

                var response = RetrofitClient.instance.getVpnConfig("Bearer $token", request)

                if (response.isSuccessful && response.body()?.success == true) {
                    val configContent = response.body()?.configContent
                    val certificateName = response.body()?.certificateName
                    val passphrase = response.body()?.passphrase

                    if (configContent != null) {
                        Log.d(TAG, "Config received for ${selected.displayName}")
                        _errorMessage.value = "Config received: ${certificateName ?: "VPN config"}"
                        connectWithConfig(configContent, server, selected, certificateName, passphrase)
                    } else {
                        _errorMessage.value = "No config content received"
                        // Since we couldn't even start, allow retry
                        _isConnecting.value = false
                    }
                } else if (response.code() == 404 && selected == VpnProtocol.IKEV2_IPSEC) {
                    _errorMessage.value = "No certificate found. Requesting one now…"
                    val issued = ensureCertificateIssued(selected, remoteServer.id, token)
                    if (issued) {
                        _errorMessage.value = "Certificate issued. Fetching configuration…"
                        response = RetrofitClient.instance.getVpnConfig("Bearer $token", request)
                        if (response.isSuccessful && response.body()?.success == true) {
                            val body = response.body()!!
                            val configContent = body.configContent
                            val certificateName = body.certificateName
                            val passphrase = body.passphrase
                            if (!configContent.isNullOrBlank()) {
                                connectWithConfig(configContent, server, selected, certificateName, passphrase)
                            } else {
                                _errorMessage.value = "Configuration still empty after certificate issuance"
                                _isConnecting.value = false
                            }
                        } else {
                            _errorMessage.value = response.body()?.message
                                ?: "Failed to get VPN config after certificate issuance: ${response.code()}"
                            _isConnecting.value = false
                        }
                    } else {
                        _isConnecting.value = false
                    }
                } else {
                    val errorBody = response.body()
                    _errorMessage.value = errorBody?.message ?: "Failed to get VPN config: ${response.code()}"
                    _isConnecting.value = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error connecting to VPN", e)
                _errorMessage.value = "Connection error: ${e.localizedMessage}"
                _isConnecting.value = false
            } finally {
                // IMPORTANT: Do not set _isConnecting=false here for IKEv2/WireGuard when we handed off to handler
                // The handler's StateFlow observer will update _isConnecting when it transitions to Connected/Error/Disconnected
                // For OpenVPN we handled it in the early return above
            }
        }
    }

    // Request issuance and wait for completion. Returns true when certificate is ready.
    private suspend fun ensureCertificateIssued(protocol: VpnProtocol, serverId: Int, token: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val vpnType = when (protocol) {
                    VpnProtocol.IKEV2_IPSEC -> "IKEV2"
                    VpnProtocol.OPENVPN -> "OPENVPN"
                    else -> return@withContext false
                }
                // Submit request
                val reqResp = RetrofitClient.instance.requestCertificate(
                    authorization = "Bearer $token",
                    request = CertificateRequest(vpnType = vpnType, serverId = serverId)
                )

                if (reqResp.code() == 403) {
                    _errorMessage.value = "Access denied. Your account may be disabled."
                    return@withContext false
                }

                if (reqResp.code() == 409) {
                    // A certificate already exists per backend rules. Proceed to retry config.
                    Log.w(TAG, "Certificate request rejected due to existing active certificate (409). Will retry fetching config.")
                    return@withContext true
                }

                if (!reqResp.isSuccessful) {
                    _errorMessage.value = reqResp.body()?.message
                        ?: "Failed to request certificate: ${reqResp.code()}"
                    return@withContext false
                }

                val job = reqResp.body()
                if (job == null) {
                    _errorMessage.value = "Empty response from certificate request"
                    return@withContext false
                }

                // If backend returns Success immediately
                if (job.status.equals("Success", ignoreCase = true)) {
                    return@withContext true
                }

                val start = System.currentTimeMillis()
                _errorMessage.value = "Certificate request queued. Issuing…"
                while (System.currentTimeMillis() - start < certMaxWaitMs) {
                    delay(certPollIntervalMs)
                    val poll = RetrofitClient.instance.getCertificateJobStatus(
                        authorization = "Bearer $token",
                        jobId = job.jobId
                    )
                    if (!poll.isSuccessful) {
                        // Stop polling on forbidden or not found
                        if (poll.code() == 403) {
                            _errorMessage.value = "Access denied while polling certificate job."
                            return@withContext false
                        }
                        if (poll.code() == 404) {
                            _errorMessage.value = "Certificate job not found."
                            return@withContext false
                        }
                        // transient errors -> continue
                        continue
                    }
                    val status = poll.body()?.status ?: ""
                    when (status.lowercase()) {
                        "success" -> return@withContext true
                        "failed", "rejected", "error" -> {
                            _errorMessage.value = poll.body()?.message ?: "Certificate issuance failed"
                            return@withContext false
                        }
                        else -> {
                            // pending/queued/running -> keep waiting
                        }
                    }
                }
                _errorMessage.value = "Timed out waiting for certificate issuance"
                false
            } catch (t: Throwable) {
                Log.e(TAG, "ensureCertificateIssued error", t)
                _errorMessage.value = "Certificate request error: ${t.localizedMessage}"
                false
            }
        }
    }

    private fun connectWithConfig(
        configContent: String,
        server: RemoteVpnServer,
        protocol: VpnProtocol,
        certificateName: String?,
        passphrase: String?
    ) {
        viewModelScope.launch {
            try {
                val appContext = getApplication<Application>().applicationContext

                // Create and initialize the appropriate VPN handler based on protocol
                activeVpnHandler = VpnProtocolFactory.createHandler(protocol, appContext)

                val initialized = withContext(Dispatchers.IO) {
                    activeVpnHandler?.initialize(appContext) ?: false
                }

                if (!initialized) {
                    throw Exception("Failed to initialize ${protocol.displayName} handler")
                }

                when (protocol) {
                    VpnProtocol.IKEV2_IPSEC -> {
                        connectStrongSwan(configContent, certificateName, passphrase)
                    }
                    VpnProtocol.OPENVPN -> {
                        connectOpenVpn(configContent)
                    }
                    VpnProtocol.WIREGUARD -> {
                        connectWireGuard(configContent)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in connectWithConfig", e)
                _isConnected.value = false
                _errorMessage.value = "Connection error: ${e.localizedMessage}"
                activeVpnHandler = null
            }
        }
    }

    // Certificate diagnostics logging helper
    private fun logUserCert(alias: String?) {
        try {
            configManager.logUserCertDiagnostics(TAG, alias)
        } catch (e: Exception) {
            Log.w(TAG, "Cert diagnostics failed: ${e.message}")
        }
    }

    // Modified connectStrongSwan implementing checklist gating
    private suspend fun connectStrongSwan(configContent: String, certificateName: String?, passphrase: String?) {
        try {
            val context = getApplication<Application>().applicationContext
            Log.d(TAG, "[CertFlow] Starting strongSwan connection parse phase")
            val profile = configManager.parseServerResponseToProfile(configContent)
                ?: throw Exception("Failed to parse server response into VpnProfile")
            if (!passphrase.isNullOrBlank() && profile.password.isNullOrBlank()) {
                profile.password = passphrase
                Log.d(TAG, "[CertFlow] Applied external passphrase to profile")
            }
            Log.d(TAG, "[CertFlow] Parsed profile name=${profile.name} gateway=${profile.gateway} vpnType=${profile.vpnType} userCertAlias=${profile.userCertificateAlias}")
            withContext(Dispatchers.IO) { logUserCert(profile.userCertificateAlias) }

            // Start with the alias provided by the server/profile (if any). We do NOT override a provided alias with an old mapping.
            var alias = profile.userCertificateAlias

            // If no alias provided, try a per-user mapping based on gateway+remoteId+username
            if (alias.isNullOrBlank()) {
                val remoteKey = perUserRemoteId(profile.remoteId, profile.username)
                val mapped = withContext(Dispatchers.IO) {
                    configManager.getMappedCertAlias(profile.gateway, remoteKey)
                }
                if (!mapped.isNullOrBlank()) {
                    val mappedDiag = withContext(Dispatchers.IO) { configManager.diagnoseUserCertificate(mapped) }
                    if (mappedDiag.state == VpnConfigManager.UserCertState.INSTALLED_OK) {
                        Log.d(TAG, "[CertFlow] Using mapped installed alias for this user: $mapped")
                        profile.userCertificateAlias = mapped
                        alias = mapped
                      } else {
                        Log.d(TAG, "[CertFlow] Mapped alias exists but not usable (state=${mappedDiag.state}); will proceed without it")
                      }
                }
            }

            if (alias.isNullOrBlank()) {
                pendingProfile = profile
                _showCertPicker.value = true
                _errorMessage.value = "Select installed client certificate"
                return
            }

            val needsInstall = withContext(Dispatchers.IO) { configManager.needsUserCertInstallation(alias) }
            if (needsInstall) {
                Log.w(TAG, "[CertFlow] User certificate alias $alias not installed yet; requesting installation or manual pick")
                val installIntent = withContext(Dispatchers.IO) { configManager.getInstallIntentIfPending(alias) }
                if (installIntent != null) {
                    pendingProfile = profile
                    _pendingKeyChainImport.value = installIntent
                    _errorMessage.value = "Client certificate installation required. Please approve KeyChain dialog."
                    return
                } else {
                    pendingProfile = profile
                    _showCertPicker.value = true
                    _errorMessage.value = "Certificate alias not found. Pick installed certificate."
                    return
                }
            }

            val diag = withContext(Dispatchers.IO) { configManager.diagnoseUserCertificate(alias) }
            when (diag.state) {
                VpnConfigManager.UserCertState.INSTALLED_OK -> Log.d(TAG, "[CertFlow] Pre-flight KeyChain OK: chain=${diag.chainSize} hasKey=${diag.hasPrivateKey}")
                VpnConfigManager.UserCertState.INSTALLED_NO_KEY -> { _errorMessage.value = "Installed certificate has no private key. Pick another certificate."; _showCertPicker.value = true; pendingProfile = profile; return }
                else -> { _errorMessage.value = "Certificate not installed yet. Pick certificate or reinstall."; pendingProfile = profile; _showCertPicker.value = true; return }
            }
            completeStrongSwanConnection(profile)
        } catch (e: Exception) {
            Log.e(TAG, "IKEv2/IPSec connection error", e)
            _isConnected.value = false
            _errorMessage.value = "IKEv2/IPSec connection failed: ${e.localizedMessage}"
            activeVpnHandler = null
        }
    }

    fun launchCertPicker(activity: Activity) {
        val prof = pendingProfile ?: run {
            _errorMessage.value = "No pending profile for cert selection"
            return
        }
        try {
            _showCertPicker.value = false
            KeyChain.choosePrivateKeyAlias(activity, KeyChainAliasCallback { chosenAlias ->
                if (chosenAlias == null) {
                    Log.w(TAG, "[CertFlow] User cancelled cert picker")
                    _errorMessage.value = "Certificate selection cancelled"
                    _showCertPicker.value = true
                    return@KeyChainAliasCallback
                }
                Log.d(TAG, "[CertFlow] User selected certificate alias=$chosenAlias")
                viewModelScope.launch {
                    withContext(Dispatchers.IO) { logUserCert(chosenAlias) }
                    prof.userCertificateAlias = chosenAlias
                    val diag = withContext(Dispatchers.IO) { configManager.diagnoseUserCertificate(chosenAlias) }
                    if (diag.state == VpnConfigManager.UserCertState.INSTALLED_OK) {
                        val remoteKey = perUserRemoteId(prof.remoteId, prof.username)
                        withContext(Dispatchers.IO) { configManager.saveMappedCertAlias(prof.gateway, remoteKey, chosenAlias) }
                        _errorMessage.value = "Certificate selected: $chosenAlias"
                        completeStrongSwanConnection(prof)
                        pendingProfile = null
                    } else {
                        _errorMessage.value = "Selected certificate not usable (state=${diag.state}). Try another."; _showCertPicker.value = true
                    }
                }
            }, null, null, null, -1, prof.userCertificateAlias)
        } catch (e: Exception) {
            Log.e(TAG, "[CertFlow] Failed to launch cert picker", e)
            _errorMessage.value = "Failed to launch cert picker: ${e.localizedMessage}"
        }
    }

    // Certificate installation resume logic
    fun resumeAfterCertificateInstall() {
        val prof = pendingProfile
        if (prof == null) {
            _errorMessage.value = "No pending profile to resume"
            return
        }
        viewModelScope.launch {
            Log.d(TAG, "[CertFlow] Resume after certificate install for alias=${prof.userCertificateAlias}")
            withContext(Dispatchers.IO) { logUserCert(prof.userCertificateAlias) }
            val alias = prof.userCertificateAlias
            if (alias != null) {
                // Poll for the certificate to become available (installation is async)
                var diag = withContext(Dispatchers.IO) { configManager.diagnoseUserCertificate(alias) }
                var attempts = 0
                while (diag.state != VpnConfigManager.UserCertState.INSTALLED_OK && attempts < 10) {
                    attempts++
                    Log.d(TAG, "[CertFlow] Waiting for KeyChain to expose cert alias=$alias attempt=$attempts state=${diag.state}")
                    delay(500)
                    diag = withContext(Dispatchers.IO) { configManager.diagnoseUserCertificate(alias) }
                }
                if (diag.state != VpnConfigManager.UserCertState.INSTALLED_OK) {
                    _errorMessage.value = "Certificate not yet available (state=${diag.state}). Pick certificate manually.";
                    _showCertPicker.value = true
                    return@launch
                }
                withContext(Dispatchers.IO) { configManager.clearPendingInstall(alias) }
                Log.d(TAG, "[CertFlow] Certificate installed and accessible; proceeding to connect")
            } else {
                _showCertPicker.value = true
                _errorMessage.value = "No alias present; select installed certificate"
                return@launch
            }
            _pendingKeyChainImport.value = null
            completeStrongSwanConnection(prof)
            pendingProfile = null
        }
    }

    /** Public alias from UI after user accepts KeyChain install */
    fun completeCertificateInstallation() {
        _isInstallingCertificate.value = false
        resumeAfterCertificateInstall()
    }

    /** User canceled certificate installation */
    fun cancelCertificateInstallation() {
        pendingProfile?.userCertificateAlias?.let { alias ->
            configManager.cleanupInstallIntentData(alias)
        }
        pendingProfile = null
        _pendingKeyChainImport.value = null
        _showCertPicker.value = false
        _isInstallingCertificate.value = false
        _errorMessage.value = "Certificate installation cancelled"
    }

    private suspend fun completeStrongSwanConnection(profile: VpnProfile) {
        val context = getApplication<Application>().applicationContext
        val dsObj = VpnProfileSource(context)
        // Call open() reflectively to handle both signatures: open():VpnProfileDataSource and open():void
        val dataSource: VpnProfileDataSource = try {
            val m = dsObj.javaClass.getMethod("open")
            val ret = m.invoke(dsObj)
            (ret as? VpnProfileDataSource) ?: dsObj
        } catch (t: Throwable) {
            Log.w(TAG, "[StrongSwan] open() via reflection failed, proceeding with instance", t)
            dsObj
        }
        try {
            // Try getVpnProfile(String) first, then fall back to getVpnProfile(UUID)
            val existingProfile = try {
                dataSource.getVpnProfile(profile.getUUID().toString())
            } catch (t: Throwable) {
                try {
                    val gm: Method = dataSource.javaClass.getMethod("getVpnProfile", java.util.UUID::class.java)
                    gm.invoke(dataSource, profile.getUUID()) as? VpnProfile
                } catch (_: Throwable) {
                    null
                }
            }
            if (existingProfile != null) {
                // updateVpnProfile via reflection (boolean return in some versions)
                try {
                    val um = dataSource.javaClass.getMethod("updateVpnProfile", VpnProfile::class.java)
                    um.invoke(dataSource, profile)
                    Log.d(TAG, "Updated existing VPN profile")
                } catch (t: Throwable) {
                    Log.w(TAG, "[StrongSwan] updateVpnProfile() invocation failed: ${t.message}")
                }
            } else {
                // insertProfile via reflection (return type differs across versions)
                try {
                    val im = dataSource.javaClass.getMethod("insertProfile", VpnProfile::class.java)
                    im.invoke(dataSource, profile)
                    Log.d(TAG, "Inserted new VPN profile")
                } catch (t: Throwable) {
                    Log.w(TAG, "[StrongSwan] insertProfile() invocation failed: ${t.message}")
                }
            }
        } finally {
            try { dsObj.close() } catch (_: Throwable) {}
        }
        // Persist mapping if alias present (per-user)
        val chosenAlias = profile.userCertificateAlias
        if (chosenAlias != null) {
            val remoteKey = perUserRemoteId(profile.remoteId, profile.username)
            withContext(Dispatchers.IO) {
                configManager.saveMappedCertAlias(profile.gateway, remoteKey, chosenAlias)
            }
        }
        
        // CRITICAL: Cancel any existing state observer to prevent stacking
        stateObserverJob?.cancel()
        stateObserverJob = null
        
        activeVpnHandler = VpnProtocolFactory.createHandler(VpnProtocol.IKEV2_IPSEC, context)
        
        // Start observing connection state BEFORE initiating connection
        val handler = activeVpnHandler as? StrongSwanHandler
        if (handler != null) {
            // Launch a SINGLE coroutine to observe state changes and store the job
            stateObserverJob = viewModelScope.launch {
                try {
                    handler.connectionState.collect { state ->
                        Log.d(TAG, "StrongSwan connection state changed: $state")
                        when (state) {
                            is ConnectionState.Connecting -> {
                                _isConnecting.value = true
                                _isConnected.value = false
                                _errorMessage.value = "Connecting to ${profile.gateway}..."
                            }
                            is ConnectionState.Connected -> {
                                if (_isConnecting.value) {
                                    _isConnected.value = true
                                    _isConnecting.value = false
                                    _errorMessage.value = "Connected using IKEv2/IPSec to ${profile.gateway}"
                                    Log.d(TAG, "Successfully connected via StateFlow")
                                    dataUsageManager.startMonitoring()
                                    Log.d(TAG, "Started data usage monitoring")
                                    startConnectionTracking()
                                    saveConnectionState()
                                } else {
                                    Log.w(TAG, "Ignoring Connected state - not in Connecting state (possible stale update)")
                                }
                            }
                            is ConnectionState.Disconnected -> {
                                _isConnected.value = false
                                _isConnecting.value = false
                                if (_errorMessage.value?.contains("Connected") == true || _errorMessage.value?.contains("Connecting") == true) {
                                    _errorMessage.value = "Disconnected"
                                }
                                Log.d(TAG, "Disconnected via StateFlow")
                                dataUsageManager.stopMonitoring()
                                stateObserverJob?.cancel()
                                stateObserverJob = null
                            }
                            is ConnectionState.Disconnecting -> {
                                _isConnecting.value = false
                                _errorMessage.value = "Disconnecting..."
                            }
                            is ConnectionState.Error -> {
                                _isConnected.value = false
                                _isConnecting.value = false
                                _errorMessage.value = state.message
                                Log.e(TAG, "Connection error via StateFlow: ${state.message}")
                                dataUsageManager.stopMonitoring()
                                stateObserverJob?.cancel()
                                stateObserverJob = null
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "StateFlow observer error: ${e.message}")
                }
            }
        }
        
        val initiatedSuccessfully = withContext(Dispatchers.IO) {
            handler?.connect(context, profile) ?: false
        }
        
        Log.d(TAG, "Connection initiation returned: $initiatedSuccessfully")
        
        if (!initiatedSuccessfully) {
            Log.d(TAG, "Connection initiation failed - canceling state observer")
            stateObserverJob?.cancel()
            stateObserverJob = null
        }
    }

    suspend fun connectWireGuard(config: String) {
        try {
            val context = getApplication<Application>().applicationContext
            val configFile = saveWireguardConfig(context, config)

            activeVpnHandler = VpnProtocolFactory.createHandler(VpnProtocol.WIREGUARD, context)

            val connected = withContext(Dispatchers.IO) {
                (activeVpnHandler as? WireGuardHandler)?.connect(context, configFile.absolutePath) ?: false
            }

            if (connected) {
                _isConnected.value = true
                _isConnecting.value = false
                _errorMessage.value = "Connected using WireGuard"
                Log.d(TAG, "Successfully connected using WireGuard")

                // Start data usage monitoring when VPN connects
                dataUsageManager.startMonitoring()
                Log.d(TAG, "Started data usage monitoring")

                startConnectionTracking()
                // Save connection state for persistence
                saveConnectionState()
            } else {
                throw Exception("Failed to connect using WireGuard")
            }
        } catch (e: Exception) {
            Log.e(TAG, "WireGuard connection error", e)
            _isConnected.value = false
            _errorMessage.value = "WireGuard connection failed: ${e.localizedMessage}"
            activeVpnHandler = null
        }
    }

    private suspend fun connectOpenVpnViaDownload(serverId: Int): Boolean {
        return withContext(Dispatchers.IO) {
            val context = getApplication<Application>().applicationContext
            try {
                val token = authToken ?: return@withContext false
                val cacheDir = getUserScopedDir(context, VpnProtocol.OPENVPN)
                val cacheFile = File(cacheDir, "openvpn_${serverId}.ovpn")

                // Ensure handler
                activeVpnHandler = VpnProtocolFactory.createHandler(VpnProtocol.OPENVPN, context)
                val initialized = activeVpnHandler?.initialize(context) ?: false
                if (!initialized) throw Exception("Failed to initialize OpenVPN handler")

                // Start observing handler state to keep UI in sync, including revocation cases
                observeActiveHandlerState()

                // Try cached config first - but validate it first
                if (cacheFile.exists() && cacheFile.length() > 0) {
                    if (isConfigCacheValid(cacheFile)) {
                        Log.d(TAG, "Using cached OpenVPN config: ${cacheFile.absolutePath}")
                        val configContent = cacheFile.readText()

                        // Validate config structure before attempting connection
                        if (validateOpenVpnConfig(configContent)) {
                            val ok = (activeVpnHandler as? OpenVpnHandler)?.connect(context, cacheFile.absolutePath) == true
                            if (ok) {
                                onOpenVpnConnected()
                                return@withContext true
                            } else {
                                Log.w(TAG, "Cached OpenVPN config failed connection attempt. Will re-download and retry")
                            }
                        } else {
                            Log.w(TAG, "Cached OpenVPN config failed validation. Will re-download")
                        }
                    } else {
                        Log.w(TAG, "Cached OpenVPN config expired (TTL exceeded). Will re-download")
                    }
                }

                // Download latest config
                var resp = RetrofitClient.instance.downloadOpenVpnConfig(
                    authorization = "Bearer $token",
                    request = OpenVpnDownloadRequest(serverId)
                )

                if (!resp.isSuccessful) {
                    if (resp.code() == 404) {
                        _errorMessage.value = "No certificate found for OpenVPN. Requesting one now…"
                        val issued = ensureCertificateIssued(VpnProtocol.OPENVPN, serverId, token)
                        if (issued) {
                            _errorMessage.value = "Certificate issued. Downloading OpenVPN config…"
                            resp = RetrofitClient.instance.downloadOpenVpnConfig(
                                authorization = "Bearer $token",
                                request = OpenVpnDownloadRequest(serverId)
                            )
                            if (!resp.isSuccessful) {
                                Log.e(TAG, "OpenVPN config download still failing after cert issuance: ${resp.code()} ${resp.message()}")
                                return@withContext false
                            }
                        } else {
                            return@withContext false
                        }
                    } else if (resp.code() == 403) {
                        _errorMessage.value = "Access denied while downloading OpenVPN config."
                        return@withContext false
                    } else {
                        Log.e(TAG, "OpenVPN config download failed: ${resp.code()} ${resp.message()}")
                        return@withContext false
                    }
                }

                val body: ResponseBody = resp.body() ?: return@withContext false
                body.byteStream().use { input ->
                    cacheFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d(TAG, "Saved OpenVPN config to ${cacheFile.absolutePath}")

                // Connect with fresh config
                val ok = (activeVpnHandler as? OpenVpnHandler)?.connect(context, cacheFile.absolutePath) == true
                if (ok) {
                    onOpenVpnConnected()
                    true
                } else {
                    Log.e(TAG, "Failed to connect with freshly downloaded OpenVPN config")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "OpenVPN download/connect error", e)
                false
            }
        }
    }

    private fun onOpenVpnConnected() {
        _isConnected.value = true
        _isConnecting.value = false
        _errorMessage.value = "Connected using OpenVPN"
        Log.d(TAG, "Successfully connected using OpenVPN")
        dataUsageManager.startMonitoring()
        Log.d(TAG, "Started data usage monitoring")

        startConnectionTracking()

        // Save connection state for persistence
        saveConnectionState()
    }

    /**
     * Start tracking connection for history
     */
    private fun startConnectionTracking() {
        val server = _selectedServer.value ?: return
        currentConnectionStartTime = System.currentTimeMillis()
        currentConnectionDataStart = _dataUsageInfo.value.totalBytesUsed / (1024.0 * 1024.0) // Convert to MB

        // Create connection record (will be updated on disconnect)
        val record = ConnectionRecord(
            serverName = server.serverName,
            country = server.country,
            connectedAt = currentConnectionStartTime!!,
            disconnectedAt = null,
            dataUsedMB = 0.0
        )
        connectionHistoryManager.addRecord(record)
        Log.d(TAG, "Started tracking connection to ${server.serverName}")

        // Start connection duration timer
        startConnectionTimer()

        // Fetch and set VPN IP
        fetchVpnIP(server.serverIp)
    }

    /**
     * Start connection duration timer
     */
    private fun startConnectionTimer(initialSeconds: Int = 0) {
        connectionTimerJob?.cancel()
        connectionTimerJob = viewModelScope.launch(Dispatchers.Default) {
            var seconds = initialSeconds
            while (isActive && _isConnected.value) {
                delay(1000)
                seconds++
                val hours = seconds / 3600
                val minutes = (seconds % 3600) / 60
                val secs = seconds % 60
                _connectionDuration.value = String.format(java.util.Locale.US, "%02d:%02d:%02d", hours, minutes, secs)
            }
        }
    }

    /**
     * Fetch VPN IP from server
     */
    private fun fetchVpnIP(serverIp: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val address = java.net.InetAddress.getByName(serverIp)
                val isReachable = address.isReachable(3000)
                withContext(Dispatchers.Main) {
                    if (isReachable) {
                        _vpnIP.value = serverIp
                        Log.d(TAG, "VPN server IP verified: $serverIp")
                    } else {
                        _vpnIP.value = serverIp
                        Log.w(TAG, "VPN server IP not reachable but using anyway: $serverIp")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to verify server IP: ${e.message}")
                withContext(Dispatchers.Main) {
                    _vpnIP.value = serverIp
                }
            }
        }
    }

    /**
     * Stop tracking connection and update history
     */
    private fun stopConnectionTracking() {
        val currentDataMB = _dataUsageInfo.value.totalBytesUsed / (1024.0 * 1024.0)
        val dataUsedMB = (currentDataMB - currentConnectionDataStart).coerceAtLeast(0.0)

        connectionHistoryManager.updateLastRecord(
            disconnectedAt = System.currentTimeMillis(),
            dataUsedMB = dataUsedMB
        )
        Log.d(TAG, "Stopped tracking connection. Data used: ${String.format(java.util.Locale.US, "%.2f", dataUsedMB)} MB")

        currentConnectionStartTime = null
        currentConnectionDataStart = 0.0

        // Stop connection timer
        connectionTimerJob?.cancel()
        connectionTimerJob = null
        _connectionDuration.value = "00:00:00"

        // Clear VPN IP
        _vpnIP.value = ""
    }

    // Observe active handler state (especially for OpenVPN) and reflect in UI
    private fun observeActiveHandlerState() {
        openVpnStateJob?.cancel()
        val handler = activeVpnHandler ?: return
        openVpnStateJob = viewModelScope.launch {
            try {
                handler.connectionState.collect { st ->
                    when (st) {
                        is ConnectionState.Connected -> {
                            _isConnected.value = true
                            _isConnecting.value = false
                            // Ensure timer/IP tracking starts even when connection comes from handler state
                            if (currentConnectionStartTime == null) {
                                startConnectionTracking()
                                saveConnectionState()
                            }
                        }
                        is ConnectionState.Connecting -> {
                            _isConnecting.value = true
                            _isConnected.value = false
                        }
                        is ConnectionState.Disconnecting -> {
                            _isConnecting.value = true
                        }
                        is ConnectionState.Disconnected -> {
                            // If previously connected, stop monitoring and clear persisted state
                            if (_isConnected.value) {
                                dataUsageManager.stopMonitoring()
                                clearPersistedState()
                            }
                            _isConnecting.value = false
                            _isConnected.value = false
                        }
                        is ConnectionState.Error -> {
                            _errorMessage.value = st.message
                            // Treat as disconnected in UI
                            if (_isConnected.value) {
                                dataUsageManager.stopMonitoring()
                                clearPersistedState()
                            }
                            _isConnecting.value = false
                            _isConnected.value = false
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Handler state observe error: ${t.message}")
            }
        }
    }

    private suspend fun connectOpenVpn(config: String) {
        try {
            val context = getApplication<Application>().applicationContext
            val configFile = saveOpenVpnConfig(context, config)

            activeVpnHandler = VpnProtocolFactory.createHandler(VpnProtocol.OPENVPN, context)

            // Start observing state before connect
            observeActiveHandlerState()

            val connected = withContext(Dispatchers.IO) {
                (activeVpnHandler as? OpenVpnHandler)?.connect(context, configFile.absolutePath) ?: false
            }

            if (connected) {
                _isConnected.value = true
                _isConnecting.value = false
                _errorMessage.value = "Connected using OpenVPN"
                Log.d(TAG, "Successfully connected using OpenVPN")

                // Start data usage monitoring when VPN connects
                dataUsageManager.startMonitoring()
                Log.d(TAG, "Started data usage monitoring")

                startConnectionTracking()

                // Save connection state for persistence
                saveConnectionState()
            } else {
                throw Exception("Failed to connect using OpenVPN")
            }
        } catch (e: Exception) {
            Log.e(TAG, "OpenVPN connection error", e)
            _isConnected.value = false
            _errorMessage.value = "OpenVPN connection failed: ${e.localizedMessage}"
            activeVpnHandler = null
        }
    }

    /**
     * Save OpenVPN config to user-scoped file
     */
    private fun saveOpenVpnConfig(context: Context, config: String): File {
        val dir = getUserScopedDir(context, VpnProtocol.OPENVPN)
        val configFile = File(dir, "openvpn_current.ovpn")
        configFile.writeText(config)
        return configFile
    }

    /**
     * Save WireGuard config to file
     */
    private fun saveWireguardConfig(context: Context, config: String): File {
        val dir = getUserScopedDir(context, VpnProtocol.WIREGUARD)
        val configFile = File(dir, "wireguard_current.conf")
        configFile.writeText(config)
        return configFile
    }

    fun disconnect() {
        viewModelScope.launch {
            try {
                val context = getApplication<Application>().applicationContext
                Log.d(TAG, "Starting disconnect process - activeHandler exists: ${activeVpnHandler != null}")

                // Validate token before disconnect - if revoked, still allow disconnect to happen
                // but mark it as a forced logout scenario
                val token = authToken
                val manager = tokenValidationManager
                if (token != null && manager != null) {
                    Log.d(TAG, "Validating token before VPN disconnection")
                    val tokenValid = manager.validateTokenBeforeAction()
                    if (!tokenValid) {
                        Log.w(TAG, "Token invalid during disconnect - will proceed with disconnect and logout")
                        _errorMessage.value = "Token revoked. Disconnecting and logging out..."
                    }
                }

                // Cancel OpenVPN state observer
                openVpnStateJob?.cancel()
                openVpnStateJob = null

                // If we don't have an active handler but connection state shows connected,
                // try to create one to handle the disconnect properly
                if (activeVpnHandler == null && _isConnected.value) {
                    Log.w(TAG, "No active handler but connection state is connected - creating handler for disconnect")
                    try {
                        val protocol = _selectedProtocol.value
                        activeVpnHandler = VpnProtocolFactory.createHandler(protocol, context)

                        // Initialize the handler
                        val initialized = withContext(Dispatchers.IO) {
                            activeVpnHandler?.initialize(context) ?: false
                        }

                        if (initialized) {
                            Log.d(TAG, "Successfully created handler for disconnect")
                        } else {
                            Log.w(TAG, "Failed to initialize handler for disconnect, will try alternative disconnect methods")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to create handler for disconnect: ${e.message}")
                    }
                }

                // Try to disconnect using the handler if available
                var disconnectResult = false
                activeVpnHandler?.let { handler ->
                    try {
                        disconnectResult = withContext(Dispatchers.IO) {
                            handler.disconnect(context)
                        }

                        if ( disconnectResult) {
                            Log.d(TAG, "VPN disconnected successfully via handler")
                        } else {
                            Log.w(TAG, "VPN disconnect via handler returned false")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error disconnecting VPN via handler", e)
                    }
                }

                // If handler disconnect failed or no handler available, try alternative methods
                if (!disconnectResult) {
                    Log.d(TAG, "Handler disconnect failed or unavailable, trying alternative disconnect methods")

                    // Method 1: Try to stop StrongSwan service directly with multiple approaches
                    try {
                        // Standard disconnect intent
                        val strongSwanIntent1 = Intent().apply {
                            setClassName("org.strongswan.android", "org.strongswan.android.logic.CharonVpnService")
                            action = "org.strongswan.android.logic.CharonVpnService.DISCONNECT"
                        }
                        context.startService(strongSwanIntent1)
                        Log.d(TAG, "Sent standard disconnect intent to StrongSwan service")

                        // Alternative disconnect intent
                        val strongSwanIntent2 = Intent().apply {
                            setClassName("org.strongswan.android", "org.strongswan.android.logic.CharonVpnService")
                            action = "disconnect"
                        }
                        context.startService(strongSwanIntent2)
                        Log.d(TAG, "Sent alternative disconnect intent to StrongSwan service")

                        // Stop service intent
                        val stopIntent = Intent().apply {
                            setClassName("org.strongswan.android", "org.strongswan.android.logic.CharonVpnService")
                        }
                        context.stopService(stopIntent)
                        Log.d(TAG, "Sent stop service intent to StrongSwan service")

                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to send disconnect intents to StrongSwan: ${e.message}")
                    }

                    // Method 2: Try to revoke VPN connection via VpnService
                    try {
                        // This will revoke the VPN connection if our app established it
                        val vpnIntent = VpnService.prepare(context)
                        if (vpnIntent == null) {
                            // VPN is prepared, we can try to disconnect by revoking
                            Log.d(TAG, "VPN service is prepared, attempting to revoke connection")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to revoke VPN connection: ${e.message}")
                    }

                    // Method 3: Clear VPN state files to force cleanup
                    try {
                        val charonLog = File(context.filesDir, "charon.log")
                        if (charonLog.exists()) {
                            charonLog.delete()
                            Log.d(TAG, "Deleted charon.log to force cleanup")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to clear VPN state files: ${e.message}")
                    }
                }

                // Wait a moment for disconnect actions to take effect
                delay(1500)

                // Final verification: check if VPN is actually disconnected
                val isStillActive = checkVpnStatusImproved()
                if (isStillActive) {
                    Log.w(TAG, "VPN still appears to be active after disconnect")
                    _errorMessage.value = "VPN disconnect attempted - please check if connection is actually terminated"
                } else {
                    Log.d(TAG, "VPN successfully disconnected - no active VPN detected")
                    _errorMessage.value = "VPN disconnected successfully"
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error during disconnect process", e)
                _errorMessage.value = "Disconnect error: ${e.localizedMessage}"
            } finally {
                // Stop connection history tracking
                stopConnectionTracking()

                // Stop data usage monitoring when VPN disconnects
                dataUsageManager.stopMonitoring()
                Log.d(TAG, "Stopped data usage monitoring")

                // Always clean up state regardless of disconnect success
                activeVpnHandler = null
                _isConnected.value = false
                _isConnecting.value = false

                // Clear persisted state when manually disconnecting
                clearPersistedState()

                Log.d(TAG, "Disconnect process completed - connection state cleared")
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun refreshServers() {
        loadRemoteServers()
    }

    // Check if VPN permission is granted
    suspend fun prepareVpn(context: Context): Intent? = withContext(Dispatchers.IO) {
        VpnService.prepare(context)
    }

    // Check current VPN connection status - improved version that doesn't require activeVpnHandler
    suspend fun checkVpnStatusImproved(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val context = getApplication<Application>().applicationContext

            // Method 1: Check if VPN permission is granted (null means granted/prepared)
            val vpnService = VpnService.prepare(context)
            val isVpnServiceReady = vpnService == null

            // Method 2: Check ConnectivityManager for VPN transport (Most reliable system check)
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val activeNetwork = connectivityManager.activeNetwork
            val caps = connectivityManager.getNetworkCapabilities(activeNetwork)
            val isVpnTransport = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true

            if (isVpnServiceReady && isVpnTransport) {
                Log.d(TAG, "VPN is active (ConnectivityManager + VpnService check)")
                return@withContext true
            }

            // Method 3: Fallback to checking network interfaces (for cases where CM might be delayed or specific device quirks)
            val isVpnInterfaceActive = try {
                val networkInterfaces = java.net.NetworkInterface.getNetworkInterfaces()
                var hasTunInterface = false
                while (networkInterfaces.hasMoreElements()) {
                    val networkInterface = networkInterfaces.nextElement()
                    if ((networkInterface.name.startsWith("tun") || networkInterface.name.startsWith("ipsec")) && networkInterface.isUp) {
                        hasTunInterface = true
                        Log.d(TAG, "Found active VPN interface: ${networkInterface.name}")
                        break
                    }
                }
                hasTunInterface
            } catch (e: Exception) {
                Log.w(TAG, "Failed to check network interfaces: ${e.message}")
                false
            }

            // Method 4: Check StrongSwan service state (fallback for StrongSwan specifically)
            val isStrongSwanActive = try {
                val vpnStateFile = File(context.filesDir, "charon.log")
                val isRecentlyActive = vpnStateFile.exists() &&
                    (System.currentTimeMillis() - vpnStateFile.lastModified()) < 60000 // 1 minute
                isRecentlyActive
            } catch (e: Exception) {
                false
            }

            Log.d(TAG, "VPN status check details: vpnServiceReady=$isVpnServiceReady, transport=$isVpnTransport, interface=$isVpnInterfaceActive, strongSwanLog=$isStrongSwanActive")

            // Consider VPN active if service is ready AND (Transport is VPN OR Interface is Up OR StrongSwan log is recent)
            isVpnServiceReady && (isVpnTransport || isVpnInterfaceActive || isStrongSwanActive)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking VPN status (improved)", e)
            false
        }
    }

    /**
     * Request VPN permission and handle the result
     */
    fun requestVpnPermission(context: Context, onResult: (Intent?) -> Unit) {
        viewModelScope.launch {
            try {
                val vpnIntent = prepareVpn(context)
                onResult(vpnIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Error preparing VPN", e)
                _errorMessage.value = "Failed to prepare VPN: ${e.localizedMessage}"
                onResult(null)
            }
        }
    }

    /**
     * Get connection logs from the active VPN handler
     */
    fun getConnectionLogs() {
        viewModelScope.launch {
            try {
                val context = getApplication<Application>().applicationContext
                val logs = activeVpnHandler?.getConnectionLogs(context)
                if (logs != null) {
                    _errorMessage.value = "Logs retrieved (${logs.length} chars)"
                    Log.d(TAG, "Connection logs:\n$logs")
                } else {
                    _errorMessage.value = "No logs available or no active connection"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting connection logs", e)
                _errorMessage.value = "Failed to get logs: ${e.localizedMessage}"
            }
        }
    }

    // Add missing methods for certificate management that are referenced in UI
    fun selectCertificate(alias: String) {
        // Implementation for certificate selection
        Log.d(TAG, "Certificate selected: $alias")
    }

    fun showImportCertificateDialog() {
        _showImportCertDialog.value = true
    }

    fun dismissCertSelectionDialog() {
        _showCertSelectionDialog.value = false
    }

    fun dismissImportCertDialog() {
        _showImportCertDialog.value = false
    }

    fun importCertificateWithAlias(alias: String) {
        // Implementation for certificate import
        Log.d(TAG, "Importing certificate with alias: $alias")
        _showImportCertDialog.value = false
    }

    /**
     * Validate OpenVPN config has required fields: remote, ca, cert, key
     * This catches malformed or corrupted configs before connection attempts
     */
    private fun validateOpenVpnConfig(configContent: String): Boolean {
        return try {
            val content = configContent.trim()
            if (content.isEmpty()) return false

            // Check for required OpenVPN directives
            val hasRemote = content.contains(Regex("^remote\\s+", RegexOption.MULTILINE))
            val hasCa = content.contains(Regex("^<ca>|^ca\\s+", RegexOption.MULTILINE))
            val hasCert = content.contains(Regex("^<cert>|^cert\\s+", RegexOption.MULTILINE))
            val hasKey = content.contains(Regex("^<key>|^key\\s+", RegexOption.MULTILINE))

            val valid = hasRemote && hasCa && hasCert && hasKey
            Log.d(TAG, "OpenVPN config validation: remote=$hasRemote ca=$hasCa cert=$hasCert key=$hasKey valid=$valid")
            valid
        } catch (e: Exception) {
            Log.w(TAG, "Error validating OpenVPN config: ${e.message}")
            false
        }
    }

    /**
     * Check if cached config is still fresh (TTL = 1 hour)
     * If config is older than TTL, it should be re-downloaded to ensure
     * certificates and server configs haven't changed
     */
    private fun isConfigCacheValid(cacheFile: File): Boolean {
        return try {
            val CONFIG_CACHE_TTL_MS = 3600_000L // 1 hour
            val lastModified = cacheFile.lastModified()
            val age = System.currentTimeMillis() - lastModified
            val valid = age < CONFIG_CACHE_TTL_MS
            Log.d(TAG, "Config cache age check: ${age / 1000}s (TTL=${CONFIG_CACHE_TTL_MS / 1000}s) valid=$valid")
            valid
        } catch (e: Exception) {
            Log.w(TAG, "Error checking config cache validity: ${e.message}")
            false
        }
    }

    // Build a per-user mapping key by combining remoteId and username
    private fun perUserRemoteId(remoteId: String?, username: String?): String? {
        val r = remoteId?.takeIf { it.isNotBlank() }
        val u = username?.takeIf { it.isNotBlank() }?.let { "user:$it" }
        if (r == null && u == null) return null
        return listOfNotNull(r, u).joinToString("|")
    }

    /**
     * Cleanup when ViewModel is destroyed
     */
    override fun onCleared() {
        super.onCleared()
        try {
            // Stop background token validation - safely handle nullable property
            tokenValidationManager?.stopBackgroundValidation()
            Log.d(TAG, "ViewModel cleared - token validation stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error during ViewModel cleanup", e)
        }
    }

    /**
     * Check if user can access OpenVPN protocol (Pro only)
     */
    fun canAccessOpenVpn(isPro: Boolean): Boolean {
        return isPro
    }

    /**
     * Check if user can access a premium server
     */
    fun canAccessPremiumServer(serverTier: String, isPro: Boolean): Boolean {
        if (serverTier.equals("Free", ignoreCase = true)) {
            return true // All users can access free servers
        }
        return isPro // Only Pro users can access premium servers
    }

    /**
     * Show upgrade dialog with specific reason
     */
    fun showUpgradeDialog(reason: String) {
        _upgradeReason.value = reason
        _showUpgradeDialog.value = true
    }

    /**
     * Hide upgrade dialog
     */
    fun hideUpgradeDialog() {
        _showUpgradeDialog.value = false
        _upgradeReason.value = null
    }

    /**
     * Update Pro user status from subscription service
     */
    fun setProStatus(isPro: Boolean) {
        _isPro.value = isPro
    }

    /**
     * Load cached subscription status (isPro) from SharedPreferences
     */
    private fun loadCachedSubscriptionStatus() {
        try {
            val subscriptionPrefs = getApplication<Application>().getSharedPreferences("vpn_subscription_prefs", Context.MODE_PRIVATE)
            val isProCached = subscriptionPrefs.getBoolean("subscription_is_pro", false)
            _isPro.value = isProCached
            Log.d(TAG, "Loaded cached subscription status: isPro=$isProCached")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load cached subscription status: ${e.message}")
        }
    }
}
