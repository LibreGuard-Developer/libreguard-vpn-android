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
import kotlinx.coroutines.CancellationException
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
import net.libreguard.vpn.network.EncryptedPassphrasePayload
import net.libreguard.vpn.network.VpnConfigRequest
import net.libreguard.vpn.service.LibreGuardVpnService
import net.libreguard.vpn.service.VpnNotificationManager
import net.libreguard.vpn.service.vpn.ConnectionState
import net.libreguard.vpn.service.vpn.OpenVpnHandler
import net.libreguard.vpn.service.vpn.StrongSwanHandler
import net.libreguard.vpn.service.vpn.VpnProtocolFactory
import net.libreguard.vpn.service.vpn.VpnProtocolHandler
import net.libreguard.vpn.service.vpn.WireGuardHandler
import net.libreguard.vpn.util.TokenValidationManager
import net.libreguard.vpn.util.TokenManager
import net.libreguard.vpn.util.ServerSelectionHelper
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
import net.libreguard.vpn.util.TokenRefreshResult

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

    // Track if user is in Quick Connect mode (true) or manual server selection mode (false)
    private val _isQuickConnectMode = MutableStateFlow(true)
    val isQuickConnectMode: StateFlow<Boolean> = _isQuickConnectMode

    // Auto-Connect feature: automatically connect on app launch
    private val _autoConnectEnabled = MutableStateFlow(false)
    val autoConnectEnabled: StateFlow<Boolean> = _autoConnectEnabled

    // Track if auto-connect has been attempted this session (prevent multiple attempts)
    private var hasAutoConnectedThisSession = false

    // Kill Switch feature: block internet traffic when VPN disconnects unexpectedly
    private val _killSwitchEnabled = MutableStateFlow(false)
    val killSwitchEnabled: StateFlow<Boolean> = _killSwitchEnabled

    // Kill Switch Manager instance
    private val killSwitchManager by lazy {
        net.libreguard.vpn.service.KillSwitchManager.getInstance(getApplication())
    }

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

    // CRITICAL FIX: Use SubscriptionViewModel as single source of truth for isPro status
    // instead of maintaining separate cached state in VpnViewModel
    private val subscriptionViewModel by lazy { SubscriptionViewModel(getApplication()) }
    val isPro: StateFlow<Boolean> get() = subscriptionViewModel.isPro

    private var authToken: String? = null
    private var currentUserId: String? = null
    private var activeVpnHandler: VpnProtocolHandler? = null
    private var pendingProfile: VpnProfile? = null
    private val configManager by lazy { VpnConfigManager(getApplication()) }

    // Track the StateFlow observer job to prevent stacking observers
    private var stateObserverJob: Job? = null
    private var subscriptionStateObserverJob: Job? = null

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

    // Statistics refresh trigger - increments when connection history is updated
    // Statistics screen observes this to refresh data in real-time
    private val _statisticsRefreshTrigger = MutableStateFlow(0L)
    val statisticsRefreshTrigger: StateFlow<Long> = _statisticsRefreshTrigger

    // Track when connection was established (for grace period in ghost detection)
    private var connectionEstablishedTimestamp: Long? = null

    // Connection duration tracking (persists across navigation)
    private val _connectionDuration = MutableStateFlow("00:00:00")
    val connectionDuration: StateFlow<String> = _connectionDuration
    private var connectionTimerJob: Job? = null

    // VPN IP tracking (persists across navigation)
    private val _vpnIP = MutableStateFlow("")
    val vpnIP: StateFlow<String> = _vpnIP

    // User's real IP (before VPN connection) - captured before connecting
    private val _userIP = MutableStateFlow("Loading...")
    val userIP: StateFlow<String> = _userIP

    // Token validation for early revocation detection
    private var tokenValidationManager: TokenValidationManager? = null

    // Polling configuration for certificate issuance
    private val certPollIntervalMs = 2000L
    private val certMaxWaitMs = 120000L // 2 minutes

    // Track OpenVPN state collection
    private var openVpnStateJob: Job? = null

    // Background VPN state monitoring to detect ghost connections
    private var vpnStateMonitorJob: Job? = null

    // Periodic quota refresh when connected (every 60 seconds)
    private var quotaRefreshJob: Job? = null

    // Upgrade events for UI navigation
    private val _upgradeEvents = MutableSharedFlow<Map<String, String?>>(replay = 0)
    val upgradeEvents: SharedFlow<Map<String, String?>> = _upgradeEvents

    /**
     * Emitted when the app needs the user to approve VPN permissions (VpnService.prepare returned an Intent).
     * UI layer must launch the Intent via Activity Result API.
     */
    private val _vpnPermissionRequests = kotlinx.coroutines.flow.MutableSharedFlow<Intent>(extraBufferCapacity = 1)
    val vpnPermissionRequests: kotlinx.coroutines.flow.SharedFlow<Intent> = _vpnPermissionRequests

    // When true, a connect attempt is waiting on VPN consent. We'll retry once consent is granted.
    @Volatile
    private var pendingConnectAfterVpnConsent: Boolean = false
    private var activeConnectJob: Job? = null
    private var activeConnectSessionId: Long = 0L

    private fun beginConnectSession(): Long {
        activeConnectJob?.cancel()
        activeConnectSessionId += 1L
        return activeConnectSessionId
    }

    private fun isConnectSessionActive(sessionId: Long): Boolean {
        return activeConnectSessionId == sessionId
    }

    private fun ensureConnectSessionActive(sessionId: Long) {
        if (!isConnectSessionActive(sessionId)) {
            throw CancellationException("VPN connection attempt cancelled")
        }
    }

    private fun isHandlerStateRelevant(sessionId: Long): Boolean {
        return _isConnected.value || activeConnectSessionId == sessionId
    }

    private fun clearPendingConnectArtifacts() {
        pendingConnectAfterVpnConsent = false
        pendingProfile?.userCertificateAlias?.let { alias ->
            runCatching { configManager.cleanupInstallIntentData(alias) }
        }
        pendingProfile = null
        _pendingKeyChainImport.value = null
        _showCertPicker.value = false
        _showCertSelectionDialog.value = false
        _showImportCertDialog.value = false
        _isInstallingCertificate.value = false
    }

    private fun restoreActiveVpnUiState(message: String) {
        val wasConnected = _isConnected.value
        _isConnected.value = true
        _isConnecting.value = false
        if (connectionEstablishedTimestamp == null) {
            connectionEstablishedTimestamp = System.currentTimeMillis()
        }
        _errorMessage.value = message
        dataUsageManager.startMonitoring()
        Log.d(TAG, "Started data usage monitoring")
        if (currentConnectionStartTime == null) {
            startConnectionTracking()
        }
        saveConnectionState()
        notifyKillSwitchConnected()
        if (!wasConnected) {
            Log.d(TAG, "Recovered connected UI state from verified active VPN tunnel")
        }
    }

    private fun cancelPendingConnectAttempt(message: String? = null) {
        activeConnectSessionId += 1L
        activeConnectJob?.cancel()
        activeConnectJob = null
        clearPendingConnectArtifacts()
        if (message != null) {
            _errorMessage.value = message
        }
    }

    init {
        // Load persisted auth token and connection state immediately on startup
        loadPersistedAuthToken()
        // Restore any previously active VPN session (e.g., after process death or app swipe-away)
        loadPersistedState()

        // REMOVED: loadCachedSubscriptionStatus() - now handled by SubscriptionViewModel

        // Load auto-connect preference
        loadAutoConnectPreference()

        // Load kill switch preference
        loadKillSwitchPreference()

        // Load default protocol preference
        loadDefaultProtocol()

        // Start observing data usage
        startDataUsageObservation()

        // Start background VPN state monitoring to detect ghost connections
        startVpnStateMonitoring()

        // Fetch user's real IP on app startup (only if not connected)
        fetchUserIP()

        // Observe subscription state changes
        observeSubscriptionState()
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
     * Start background VPN state monitoring to detect and clear ghost connections
     * Runs periodically when UI state shows connected to verify actual VPN is active
     */
    private fun startVpnStateMonitoring() {
        vpnStateMonitorJob?.cancel()
        vpnStateMonitorJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(15000) // Check every 15 seconds

                // Only verify if UI state shows connected
                if (_isConnected.value) {
                    try {
                        val actuallyConnected = checkVpnStatusImproved()

                        if (!actuallyConnected) {
                            Log.w(TAG, "⚠️ VPN State Monitor: Ghost connection detected (UI shows connected but VPN inactive)")

                            withContext(Dispatchers.Main) {
                                // Clear all connection state
                                _isConnected.value = false
                                _connectionState.value = ConnectionState.Disconnected
                                _isConnecting.value = false
                                activeVpnHandler = null

                                // Stop tracking
                                stopConnectionTracking()

                                // Clear persisted state
                                clearPersistedState()

                                _errorMessage.value = null

                                Log.d(TAG, "VPN State Monitor: Ghost connection state cleared automatically")
                            }
                        } else {
                            // Redacted connection verification log
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "VPN State Monitor: Error checking status")
                    }
                }
            }
        }
        Log.d(TAG, "Started background VPN state monitoring (15s interval)")
    }

    /**
     * Sync data usage quota from server
     * Call this on app launch and periodically to refresh quota display
     */
    fun syncServerQuota() {
        viewModelScope.launch {
            dataUsageManager.syncQuotaFromServer()
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
            Log.d(TAG, "Cached VPN servers list")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cache servers")
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
                Log.d(TAG, "Loaded VPN servers from cache")
                servers
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load cached servers")
            emptyList()
        }
    }

    /**
     * Load persisted auth token immediately on startup
     * NOTE: We no longer clear expired tokens here - let MainActivity handle refresh
     * This allows the refresh flow to work properly on app resume
     */
    private fun loadPersistedAuthToken() {
        try {
            val savedAuthToken = sharedPrefs.getString("auth_token", null)
            if (!savedAuthToken.isNullOrBlank()) {
                val tokenManager = RetrofitClient.getTokenManager()

                // Check if token is expired - but DON'T clear it here
                // MainActivity will attempt refresh on startup
                if (tokenManager.isTokenExpired()) {
                    // Check if refresh token is available for recovery
                    val refreshToken = tokenManager.getRefreshToken()
                    if (refreshToken.isNullOrBlank() || tokenManager.isRefreshTokenExpired()) {
                        // Both tokens expired - must re-login
                        Log.w(TAG, "Persisted auth tokens expired or missing, clearing")
                        sharedPrefs.edit().remove("auth_token").apply()
                        tokenManager.clearTokens()
                        return
                    }
                    // Access token expired but refresh token available
                    // Keep the token - MainActivity will refresh it
                    Log.d(TAG, "Persisted auth token is expired but refresh token available - keeping for refresh")
                }

                authToken = savedAuthToken
                // Restore stable user id for scoping caches
                currentUserId = sharedPrefs.getString("current_user_id", null)
                Log.d(TAG, "Restored auth token from persistent storage (expired=${tokenManager.isTokenExpired()})")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load persisted auth token on init")
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
                        Log.d(TAG, "Loaded cached servers during state restore")
                    }
                }

                Log.d(TAG, "Loading persisted state (connectedAt=$connectedAt)")

                if (wasConnected && serverName != null && protocolName != null) {
                    Log.d(TAG, "Restoring connection state: $protocolName")

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
                            Log.d(TAG, "Restored server: ${server.serverName.take(8)}...")
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
                                                Log.d(TAG, "Successfully restored VPN profile in handler")
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Failed to restore VPN profile in handler")
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

                        // CRITICAL FIX: Re-verify VPN status after a delay to catch race conditions
                        // This only applies to RESTORED connections (after force-close/restart)
                        // Fresh connections have connectionEstablishedTimestamp set, so background monitor respects grace period
                        viewModelScope.launch {
                            delay(3000) // Wait 3 seconds for system state to stabilize

                            val isStillActive = checkVpnStatusImproved()
                            if (!isStillActive && _isConnected.value) {
                                Log.w(TAG, "⚠️ VPN connection verification FAILED after delay - clearing ghost state")

                                // Clear all connection state
                                _isConnected.value = false
                                _connectionState.value = ConnectionState.Disconnected
                                _isConnecting.value = false
                                activeVpnHandler = null

                                // Stop tracking
                                stopConnectionTracking()

                                // Clear persisted state
                                clearPersistedState()

                                _errorMessage.value = null
                                Log.d(TAG, "Ghost connection state cleared - VPN was not actually active")
                            } else if (isStillActive) {
                                Log.d(TAG, "✅ VPN connection verified active after delay - state is correct")
                            }
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
            Log.d(TAG, "Saved connection state: connected=$connected, protocol=${protocol.displayName}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save connection state")
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

                // Clear VPN profile data (prevents partial restoration)
                remove("vpn_profile_uuid")
                remove("vpn_profile_name")
                remove("vpn_profile_gateway")
                remove("vpn_profile_remote_id")
                remove("vpn_profile_user_cert_alias")

                // Preserve auth token
                if (currentAuthToken != null) {
                    putString("auth_token", currentAuthToken)
                }
                apply()
            }

            // Also clear connection tracking data
            currentConnectionStartTime = null
            currentConnectionDataStart = 0.0

            Log.d(TAG, "Cleared persisted connection state and profile data")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear persisted state")
        }
    }

    /**
     * Clear ALL persisted state including auth token (for full logout)
     */
    fun clearAllPersistedState() {
        try {
            sharedPrefs.edit().clear().apply()
            authToken = null
            Log.d(TAG, "Cleared ALL persisted state")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear all persisted state")
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

            // Stop background token refresh
            tokenValidationManager?.stopBackgroundTokenRefresh()

            // Disconnect VPN if connected
            if (_isConnected.value) {
                disconnect()
            }

            // Clear user data properly - this preserves the user's total data usage
            dataUsageManager.clearUser()

            // Clear user-specific connection history context (preserves data, clears user context)
            connectionHistoryManager.clearUser()

            // Clear user-scoped cached VPN configs
            try {
                clearUserScopedVpnCaches()
            } catch (e: Exception) {
                Log.w(TAG, "Failed clearing user-scoped caches on logout")
            }

            // Reset protocol to default (IKEv2) on logout to prevent free users inheriting Pro protocol
            _selectedProtocol.value = VpnProtocol.IKEV2_IPSEC

            // Reset Auto-Connect and Kill-Switch in-memory state (persisted data stays intact per user)
            _autoConnectEnabled.value = false
            _killSwitchEnabled.value = false
            killSwitchManager.setEnabled(false)
            Log.d(TAG, "Reset Auto-Connect and Kill-Switch state on logout")

            // CRITICAL: Clear subscription data to reset isPro status for next user
            subscriptionViewModel.clearSubscriptionData()
            Log.d(TAG, "Cleared subscription data on logout")

            // Clear auth token and all related state
            authToken = null
            currentUserId = null
            _selectedServer.value = null
            _isQuickConnectMode.value = true  // Reset to Quick Connect mode for next login
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
            tokenValidationManager?.stopBackgroundTokenRefresh()
            Log.d(TAG, "Stopped token validation and refresh background jobs")

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
                    Log.d(TAG, "Handler disconnect triggered")
                } catch (e: Exception) {
                    Log.w(TAG, "Handler disconnect failed")
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
                Log.d(TAG, "Sent ACTION_DISCONNECT to service")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send ACTION_DISCONNECT to service")
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
            Log.w(TAG, "Failed to send OpenVPN disconnect broadcast")
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
            Log.w(TAG, "Failed to send StrongSwan disconnect intent")
        }

        // Try alternative: Use package manager to force-stop (requires system permission or root)
        try {
            withContext(Dispatchers.IO) {
                // These may fail without proper permissions, but worth trying
                Runtime.getRuntime().exec("am force-stop de.blinkt.openvpn").waitFor()
                Log.d(TAG, "Force-stopped OpenVPN app")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to force-stop OpenVPN")
        }

        try {
            withContext(Dispatchers.IO) {
                Runtime.getRuntime().exec("am force-stop org.strongswan.android").waitFor()
                Log.d(TAG, "Force-stopped StrongSwan app")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to force-stop StrongSwan")
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
        // TokenManager is the source of truth; avoid using a stale ViewModel authToken.
        val token = RetrofitClient.getTokenManager().getAccessToken()
        if (token.isNullOrBlank()) {
            Log.w(TAG, "No auth token available for loading remote servers")
            return
        }

        _isLoadingServers.value = true

        viewModelScope.launch {
            try {
                val cachedServers = loadCachedServers()
                if (cachedServers.isNotEmpty()) {
                    _servers.value = cachedServers
                    Log.d(TAG, "Loaded ${cachedServers.size} servers from cache")
                }

                // Always re-read token right before the call (it may have been refreshed).
                val freshToken = RetrofitClient.getTokenManager().getAccessToken()
                if (freshToken.isNullOrBlank()) {
                    Log.w(TAG, "No auth token available for loading remote servers (after cache)")
                    return@launch
                }

                val response = RetrofitClient.instance.getVpnServers("Bearer $freshToken")

                if (response.isSuccessful) {
                    val serverResponse = response.body()
                    if (serverResponse?.servers != null) {
                        _servers.value = serverResponse.servers
                        cacheServers(serverResponse.servers)
                        Log.d(TAG, "Loaded ${serverResponse.servers.size} remote servers from API")
                        _errorMessage.value = "Server list updated (${serverResponse.servers.size} servers)"

                        if (!_isConnected.value) {
                            _serverLatencies.value = emptyMap()
                            measureServerLatencies(serverResponse.servers)
                        } else {
                            Log.d(TAG, "Skipping latency measurement while connected to VPN")
                        }
                    } else {
                        Log.w(TAG, "No servers received from API")
                        if (cachedServers.isEmpty()) {
                            _errorMessage.value = "No servers received from API"
                        }
                    }
                } else {
                    Log.w(TAG, "Failed to load servers: ${response.code()}")
                    if (cachedServers.isEmpty()) {
                        _errorMessage.value = "Failed to load servers (network issue)"
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error loading remote servers")
                if (_servers.value.isEmpty()) {
                    _errorMessage.value = "Network error loading servers"
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
                Log.v(TAG, "Error measuring server latencies")
            }
        }
    }

    fun setAuthToken(token: String) {
        // Check if this is a NEW login vs just a re-render with the same token
        val isNewLogin = authToken != token

        authToken = token
        // Save token immediately for persistence
        sharedPrefs.edit().putString("auth_token", token).apply()
        Log.d(TAG, "Auth token updated and persisted")

        // CRITICAL FIX: Create stable user ID that persists across login sessions
        // Instead of using token hash (which changes), extract user info from token or create persistent ID
        val userId = getStableUserId(token)
        currentUserId = userId
        sharedPrefs.edit().putString("current_user_id", userId).apply()
        dataUsageManager.setUserId(userId)

        // CRITICAL: Set user ID for connection history manager (per-user stats isolation)
        connectionHistoryManager.setUserId(userId)

        // CRITICAL: Only reset to Quick Connect mode on FRESH login, not on screen navigation re-renders
        if (isNewLogin) {
            _isQuickConnectMode.value = true
            Log.d(TAG, "Reset to Quick Connect mode for new user")
        }

        // Pass auth token to DataUsageManager for server quota sync
        dataUsageManager.setAuthToken(token)

        Log.d(TAG, "Set stable user ID (masked)")

        // CRITICAL FIX: Set auth token in SubscriptionViewModel with user ID for cache scoping
        subscriptionViewModel.setAuthToken(token, userId)

        // Only perform full initialization on NEW login, not on screen navigation re-renders
        if (!isNewLogin) {
            Log.d(TAG, "Skipping full initialization - same token (screen navigation)")
            return
        }

        // Clean legacy, non-scoped OpenVPN caches to avoid cross-user leakage
        try {
            cleanupLegacyOpenVpnCache()
        } catch (e: Exception) {
            Log.w(TAG, "Legacy OpenVPN cache cleanup failed")
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

        // CRITICAL: Start proactive background token refresh to keep session alive
        // This refreshes the token before it expires, preventing logout after inactivity
        Log.d(TAG, "Starting background token refresh polling")
        tokenValidationManager?.startBackgroundTokenRefresh(viewModelScope)

        // CRITICAL FIX: Stagger API requests to prevent simultaneous 401s
        // Load servers first, then subscription fetch, then quota sync after delays
        viewModelScope.launch {
            // Load remote servers immediately
            loadRemoteServersWithFallback()

            // CRITICAL FIX: Trigger subscription status fetch immediately after login
            // This ensures isPro is updated proactively instead of waiting for screens to trigger it
            delay(500L)
            try {
                subscriptionViewModel.fetchSubscriptionStatus()
                Log.d(TAG, "Subscription status fetch triggered after login")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch subscription status")
            }

            // CRITICAL: Reload user-specific settings AFTER subscription status is known
            // This ensures Pro-only features are enforced correctly
            loadDefaultProtocol()
            loadAutoConnectPreference()
            loadKillSwitchPreference()
            Log.d(TAG, "Reloaded user-specific settings")

            // Wait before loading quota to stagger requests
            delay(1500L)
        }
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
                Log.d(TAG, "Extracted user ID from JWT")
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
                Log.d(TAG, "Found existing stable user ID")
                return existingUserId
            }

            // Method 3: Create new persistent user ID
            val newUserId = "user_${System.currentTimeMillis()}_${(0..999999).random()}"
            sharedPrefs.edit().putString(stableKey, newUserId).apply()
            Log.d(TAG, "Created new stable user ID")

            return newUserId

        } catch (e: Exception) {
            Log.e(TAG, "Failed to get stable user ID, falling back to simple hash")
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
                    Log.d(TAG, "Found user ID in JWT field '$field'")
                    return userId
                }
            }

            return null
        } catch (e: Exception) {
            Log.v(TAG, "Could not extract user ID from JWT")
            return null
        }
    }

    fun selectServer(server: RemoteVpnServer) {
        _selectedServer.value = server
        _isQuickConnectMode.value = false // Switch to manual mode
    }

    /**
     * Clear manual server selection and return to Quick Connect mode
     */
    fun clearServerSelection() {
        _selectedServer.value = null
        _isQuickConnectMode.value = true
    }

    fun selectProtocol(protocol: VpnProtocol) {
        saveDefaultProtocol(protocol)
    }

    private fun getPendingUpgradeProtocolPrefsKey(): String {
        return if (currentUserId != null) {
            "pending_upgrade_protocol_${currentUserId}"
        } else {
            "pending_upgrade_protocol"
        }
    }

    fun rememberPendingUpgradeProtocolSelection(protocol: VpnProtocol) {
        sharedPrefs.edit()
            .putString(getPendingUpgradeProtocolPrefsKey(), protocol.displayName)
            .apply()
        Log.d(TAG, "Saved pending protocol selection for upgrade: ${protocol.displayName}")
    }

    private fun consumePendingUpgradeProtocolSelection(): VpnProtocol? {
        return try {
            val prefsKey = getPendingUpgradeProtocolPrefsKey()
            val protocolName = sharedPrefs.getString(prefsKey, null)
            sharedPrefs.edit().remove(prefsKey).apply()
            VpnProtocol.values().find { it.displayName == protocolName }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to consume pending upgrade protocol selection")
            null
        }
    }

    private fun applyPendingUpgradeSelectionsAfterPurchase() {
        val pendingProtocol = consumePendingUpgradeProtocolSelection() ?: return
        saveDefaultProtocol(pendingProtocol)
        Log.d(TAG, "Applied pending protocol selection after successful upgrade: ${pendingProtocol.displayName}")
    }

    private fun findRemoteServerByName(serverName: String): RemoteVpnServer? {
        return _servers.value.find { it.serverName == serverName }
    }

    private fun normalizeProtocolForSubscription(protocol: VpnProtocol, isPro: Boolean): VpnProtocol {
        return if (!isPro && protocol == VpnProtocol.OPENVPN) {
            VpnProtocol.IKEV2_IPSEC
        } else {
            protocol
        }
    }

    private fun ensureProtocolMatchesSubscription(isPro: Boolean): VpnProtocol {
        val currentProtocol = _selectedProtocol.value
        val normalizedProtocol = normalizeProtocolForSubscription(currentProtocol, isPro)

        if (normalizedProtocol != currentProtocol) {
            Log.w(TAG, "OpenVPN selected for a free account - switching to IKEV2/IPSec")
            saveDefaultProtocol(normalizedProtocol)
        }

        return normalizedProtocol
    }

    private fun observeSubscriptionState() {
        subscriptionStateObserverJob?.cancel()
        subscriptionStateObserverJob = viewModelScope.launch {
            subscriptionViewModel.isPro.collect { isPro ->
                ensureProtocolMatchesSubscription(isPro)
            }
        }
    }

    /**
     * Quick Connect - Automatically select and connect to the best available server
     *
     * Selects server based on:
     * 1. User subscription tier (Free users: Free servers only, Pro users: all servers)
     * 2. Latency (primary factor - 70% weight)
     * 3. Server load (25% weight, 50% if load > 70%)
     * 4. Pro server preference for Pro users (10% bonus)
     */
    fun quickConnect() {
        // Guard: ignore if already connecting or connected
        if (_isConnecting.value) {
            _errorMessage.value = "Already connecting..."
            return
        }
        if (_isConnected.value) {
            _errorMessage.value = "Already connected"
            return
        }

        val servers = _servers.value
        if (servers.isEmpty()) {
            _errorMessage.value = "No servers available. Please refresh the server list."
            return
        }

        val latencies = _serverLatencies.value
        if (latencies.isEmpty()) {
            // Latency measurement not complete - trigger it and inform user
            _errorMessage.value = "Measuring server latencies, please wait..."
            Log.d(TAG, "Quick Connect: No latency data available, measuring now...")

            viewModelScope.launch {
                // Trigger latency measurement
                measureServerLatencies(servers)

                // Wait a bit for measurements to complete
                delay(3000)

                // Try again if we got some measurements
                if (_serverLatencies.value.isNotEmpty()) {
                    quickConnect()
                } else {
                    _errorMessage.value = "Unable to measure server latencies. Please try manual selection."
                }
            }
            return
        }

        // Get user subscription status
        val isProCached = subscriptionViewModel.isPro.value

        // Select best server using helper
        val bestServer = ServerSelectionHelper.selectBestServer(
            servers = servers,
            serverLatencies = latencies,
            isPro = isProCached
        )

        if (bestServer == null) {
            _errorMessage.value = "No suitable server found. All servers may be overloaded or unavailable."
            Log.w(TAG, "Quick Connect: Could not find suitable server")
            return
        }

        // Auto-select the best server
        Log.d(TAG, "Quick Connect: Auto-selected ${bestServer.serverName.take(8)}...")
        _selectedServer.value = bestServer
        _isQuickConnectMode.value = true // Ensure Quick Connect mode is active
        _errorMessage.value = "Connecting to ${bestServer.serverName}..."

        // Connect to the selected server
        connectToVpn()
    }

    fun connectToVpn() {
        val server = _selectedServer.value

        // IMPORTANT: Capture user's real IP BEFORE connecting to VPN
        // This ensures "Your IP" displays the actual user IP, not the VPN server IP
        fetchUserIP()

        // Avoid capturing authToken early; token may be refreshed.
        val initialToken = RetrofitClient.getTokenManager().getAccessToken()

        // Guard: ensure access for server/protocol before attempting
        try {
            val isProCached = subscriptionViewModel.isPro.value
            val selected = ensureProtocolMatchesSubscription(isProCached)

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

            if (selected == VpnProtocol.OPENVPN && !isProCached) {
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
        } catch (e: Exception) {
            Log.w(TAG, "Failed to validate local subscription cache")
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

        if (initialToken.isNullOrBlank()) {
            _errorMessage.value = "Authentication token missing"
            return
        }

        val connectSessionId = beginConnectSession()
        _isConnecting.value = true
        _errorMessage.value = null

        // Before any network/config/handler work, ensure VPN permission is granted.
        // If not granted, request UI to launch the consent Intent.
        try {
            val context = getApplication<Application>().applicationContext
            val vpnIntent = android.net.VpnService.prepare(context)
            if (vpnIntent != null) {
                pendingConnectAfterVpnConsent = true
                _isConnecting.value = false
                _errorMessage.value = "VPN permission required"
                _vpnPermissionRequests.tryEmit(vpnIntent)
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "VpnService.prepare failed")
            // Continue; handler/connect will surface errors if permission truly missing.
        }

        var connectJob: Job? = null
        connectJob = viewModelScope.launch {
            try {
                ensureConnectSessionActive(connectSessionId)
                val tokenManager = RetrofitClient.getTokenManager()
                Log.d(TAG, "Checking token refresh before connection")
                val refreshSuccess = tokenManager.refreshTokenIfNeeded(RetrofitClient.authApiService)
                ensureConnectSessionActive(connectSessionId)

                if (!refreshSuccess) {
                    Log.w(TAG, "Token refresh failed - session expired")
                    _errorMessage.value = "Your session has expired. Please login again."
                    _isConnecting.value = false

                    // Trigger proper logout sequence: disconnect VPN, call API, clear tokens, redirect
                    viewModelScope.launch {
                        try {
                            // Force disconnect VPN if connected
                            if (_isConnected.value) {
                                forceDisconnectVpn(getApplication<Application>().applicationContext)
                            }

                            // Use LogoutManager for proper logout (API call + clear tokens)
                            net.libreguard.vpn.util.LogoutManager.logout()
                        } catch (e: Exception) {
                            Log.w(TAG, "Error during logout sequence")
                        }

                        // Broadcast logout event to redirect to login screen
                        val logoutIntent = Intent("net.libreguard.vpn.ACTION_LOGOUT")
                        logoutIntent.setPackage(getApplication<Application>().packageName)
                        getApplication<Application>().sendBroadcast(logoutIntent)
                    }
                    return@launch
                }

                Log.d(TAG, "Token is valid or successfully refreshed")

                // Re-validate actual VPN status
                val actuallyConnected = checkVpnStatusImproved()
                ensureConnectSessionActive(connectSessionId)

                // If VPN is already connected, just update the state and return
                if (actuallyConnected) {
                    Log.d(TAG, "VPN already connected, skipping connect")
                    _isConnected.value = true
                    _isConnecting.value = false
                    return@launch
                }

                // Re-read token AFTER refresh.
                var token: String = tokenManager.getAccessToken() ?: ""
                if (token.isBlank()) {
                    Log.w(TAG, "Access token missing after refresh")
                    _errorMessage.value = "Authentication token missing"
                    _isConnecting.value = false
                    return@launch
                }

                // Validate token before attempting connection
                val manager = tokenValidationManager
                if (manager != null) {
                    Log.d(TAG, "Validating token before VPN connection")
                    val tokenValid = manager.validateTokenBeforeAction()
                    ensureConnectSessionActive(connectSessionId)
                    if (!tokenValid) {
                        _errorMessage.value = "Token invalid or revoked. Please login again."
                        _isConnecting.value = false
                        return@launch
                    }
                }

                // Pre-flight data usage check - verify user hasn't exceeded quota
                Log.d(TAG, "Checking data usage quota before VPN connection")
                val canConnectResult = dataUsageManager.checkCanConnect()
                ensureConnectSessionActive(connectSessionId)
                if (canConnectResult != null && !canConnectResult.allowed) {
                    Log.w(TAG, "Data usage quota exceeded")
                    _errorMessage.value = canConnectResult.message ?: "Data limit exceeded. Upgrade to Pro for unlimited data."
                    _isConnecting.value = false

                    // Show persistent notification for data limit exceeded
                    VpnNotificationManager.showDataLimitExceeded(getApplication<Application>().applicationContext)

                    // Emit upgrade event for data limit exceeded
                    _upgradeEvents.emit(mapOf(
                        "reason" to "Data limit exceeded while connected",
                        "resource_type" to "data_quota",
                        "resource_id" to null,
                        "required_tier" to "Pro",
                        "message" to canConnectResult.message,
                        "reset_date" to canConnectResult.resetDate
                    ))
                    return@launch
                }
                Log.d(TAG, "Data usage check passed")

                // DEFENSIVE CHECK: Warn if refresh token is missing (indicates OAuth persistence issue)
                val refreshToken = RetrofitClient.getTokenManager().getRefreshToken()
                if (refreshToken.isNullOrBlank()) {
                    Log.w(TAG, "WARNING: Refresh token missing! session may expire prematurely.")
                }

                val selected = _selectedProtocol.value
                val remoteServer = server
                if (selected == VpnProtocol.OPENVPN) {
                    // Use download endpoint and cached config
                    val ok = connectOpenVpnViaDownload(remoteServer.id)
                    if (!ok && isConnectSessionActive(connectSessionId)) {
                        _errorMessage.value = "Failed to connect using OpenVPN"
                    }
                    if (isConnectSessionActive(connectSessionId)) {
                        _isConnecting.value = false
                    }
                    return@launch
                }

                // Existing path for IKEV2 and WIREGUARD
                val request = VpnConfigRequest(
                    serverId = remoteServer.id,
                    protocol = selected.apiName
                )

                // IMPORTANT: use fresh token for config request
                var response = fetchVpnConfigWithRecovery(request, tokenManager)
                ensureConnectSessionActive(connectSessionId)
                tokenManager.getAccessToken()?.takeIf { it.isNotBlank() }?.let { token = it }

                if (response.isSuccessful && response.body()?.success == true) {
                    val preparedConfig = prepareVpnConfigForConnection(
                        protocol = selected,
                        request = request,
                        tokenManager = tokenManager,
                        body = response.body() ?: throw IllegalStateException("VPN config response body missing")
                    )
                    ensureConnectSessionActive(connectSessionId)

                    if (preparedConfig.configContent.isNotBlank()) {
                        Log.d(TAG, "Config received for ${selected.displayName}")
                        _errorMessage.value = "Config received: ${preparedConfig.certificateName ?: "VPN config"}"
                        connectWithConfig(preparedConfig.configContent, server, selected, preparedConfig.certificateName, preparedConfig.passphrase, connectSessionId)
                    } else {
                        _errorMessage.value = "No config content received"
                        // Since we couldn't even start, allow retry
                        _isConnecting.value = false
                    }
                } else if (response.code() == 404 && selected == VpnProtocol.IKEV2_IPSEC) {
                    _errorMessage.value = "No certificate found. Requesting one now…"
                    val issued = ensureCertificateIssued(selected, remoteServer.id, token)
                    ensureConnectSessionActive(connectSessionId)
                    if (issued) {
                        _errorMessage.value = "Certificate issued. Fetching configuration…"
                        response = fetchVpnConfigWithRecovery(request, tokenManager)
                        ensureConnectSessionActive(connectSessionId)
                        if (response.isSuccessful && response.body()?.success == true) {
                            val preparedConfig = prepareVpnConfigForConnection(
                                protocol = selected,
                                request = request,
                                tokenManager = tokenManager,
                                body = response.body() ?: throw IllegalStateException("VPN config response body missing")
                            )
                            ensureConnectSessionActive(connectSessionId)
                            if (preparedConfig.configContent.isNotBlank()) {
                                connectWithConfig(preparedConfig.configContent, server, selected, preparedConfig.certificateName, preparedConfig.passphrase, connectSessionId)
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
                    _errorMessage.value = extractApiMessage(response) ?: "Failed to get VPN config: ${response.code()}"
                    _isConnecting.value = false
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Connection attempt cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Error connecting to VPN", e)
                _errorMessage.value = "Connection error"
                _isConnecting.value = false
            } finally {
                if (activeConnectJob === connectJob) {
                    activeConnectJob = null
                }
                // IMPORTANT: Do not set _isConnecting=false here for IKEv2/WireGuard when we handed off to handler
                // The handler's StateFlow observer will update _isConnecting when it transitions to Connected/Error/Disconnected
                // For OpenVPN we handled it in the early return above
            }
        }
        activeConnectJob = connectJob
    }

    private fun extractApiMessage(response: retrofit2.Response<*>): String? {
        return runCatching {
            val raw = response.errorBody()?.string().orEmpty()
            if (raw.isBlank()) null else JSONObject(raw).optString("message").takeIf { it.isNotBlank() } ?: raw
        }.getOrNull()
    }

    private fun buildRetryAfterMessage(retryAfterSeconds: Int?): String {
        return if (retryAfterSeconds != null && retryAfterSeconds > 0) {
            "Too many requests. Please wait ${retryAfterSeconds}s and try again."
        } else {
            "Too many requests. Please try again shortly."
        }
    }

    private data class PreparedVpnConfig(
        val configContent: String,
        val certificateName: String?,
        val passphrase: String?
    )

    private suspend fun prepareVpnConfigForConnection(
        protocol: VpnProtocol,
        request: VpnConfigRequest,
        tokenManager: TokenManager,
        body: net.libreguard.vpn.network.VpnConfigResponse,
        allowKeyRecovery: Boolean = true
    ): PreparedVpnConfig {
        val rawConfigContent = body.configContent ?: throw IllegalStateException("VPN config is missing config content")
        if (protocol != VpnProtocol.IKEV2_IPSEC) {
            return PreparedVpnConfig(rawConfigContent, body.certificateName, null)
        }

        val payload = body.encryptedPassphrase ?: throw IllegalStateException("VPN config is missing encrypted passphrase")
        val boundKeyId = tokenManager.getBoundDeviceKeyId()
        val currentKeyId = withContext(Dispatchers.IO) { net.libreguard.vpn.util.DeviceKeyManager.currentPublicKeyId() }
        Log.d(
            TAG,
            "IKEV2 key state before decrypt: boundKeyId=${boundKeyId?.take(8)}... currentKeyId=${currentKeyId?.take(8)}... payloadKeyId=${payload.keyId?.take(8)}..."
        )

        val decryptedPassphrase = try {
            withContext(Dispatchers.IO) { net.libreguard.vpn.util.PassphraseDecryptor.decrypt(payload) }
        } catch (e: IllegalArgumentException) {
            if (allowKeyRecovery && isRecoverableKeyMismatch(e)) {
                Log.w(TAG, "IKEV2 encrypted passphrase key mismatch. Retrying config fetch.")
                val refreshedToken = forceRefreshWithDeviceKeyRebind(tokenManager, rotateLocalKey = false)
                val retryResponse = RetrofitClient.instance.getVpnConfig("Bearer $refreshedToken", request)
                if (!retryResponse.isSuccessful || retryResponse.body()?.success != true) {
                    throw IllegalStateException(extractApiMessage(retryResponse) ?: "Failed to refresh VPN config after key mismatch")
                }

                return prepareVpnConfigForConnection(
                    protocol = protocol,
                    request = request,
                    tokenManager = tokenManager,
                    body = retryResponse.body() ?: throw IllegalStateException("VPN config response body missing after key mismatch retry"),
                    allowKeyRecovery = false
                )
            }
            throw IllegalStateException(e.message ?: "Invalid encrypted passphrase payload", e)
        } catch (e: Exception) {
            if (!allowKeyRecovery || !isRecoverablePassphraseFailure(e)) {
                throw IllegalStateException("Failed to decrypt VPN passphrase")
            }

            Log.w(TAG, "IKEv2 passphrase decrypt failed, retrying with device key rotation", e)
            val refreshedToken = forceRefreshWithDeviceKeyRebind(tokenManager, rotateLocalKey = true)
            val retryResponse = RetrofitClient.instance.getVpnConfig("Bearer $refreshedToken", request)
            if (!retryResponse.isSuccessful || retryResponse.body()?.success != true) {
                throw IllegalStateException(extractApiMessage(retryResponse) ?: "Failed to refresh VPN config after rotating device keys")
            }

            return prepareVpnConfigForConnection(
                protocol = protocol,
                request = request,
                tokenManager = tokenManager,
                body = retryResponse.body() ?: throw IllegalStateException("VPN config response body missing after key rotation"),
                allowKeyRecovery = false
            )
        }

        Log.d(
            TAG,
            "IKEV2 passphrase decrypted successfully; injecting into config"
        )

        return PreparedVpnConfig(
            configContent = injectIkev2Passphrase(rawConfigContent, decryptedPassphrase),
            certificateName = body.certificateName,
            passphrase = decryptedPassphrase
        )
    }

    private fun injectIkev2Passphrase(configContent: String, passphrase: String): String {
        val json = JSONObject(configContent)
        val local = json.optJSONObject("local") ?: JSONObject().also { json.put("local", it) }
        local.put("password", passphrase)
        return json.toString()
    }

    private fun isRecoverablePassphraseFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            when (current) {
                is javax.crypto.IllegalBlockSizeException,
                is javax.crypto.BadPaddingException,
                is java.security.InvalidAlgorithmParameterException,
                is java.security.InvalidKeyException,
                is java.security.UnrecoverableKeyException -> return true
            }
            if (current.javaClass.name == "android.security.KeyStoreException") {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun isRecoverableKeyMismatch(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is IllegalArgumentException && current.message?.contains("key mismatch", ignoreCase = true) == true) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private suspend fun forceRefreshWithDeviceKeyRebind(
        tokenManager: TokenManager,
        rotateLocalKey: Boolean
    ): String {
        _errorMessage.value = "Refreshing device encryption keys..."
        if (rotateLocalKey) {
            runCatching { net.libreguard.vpn.util.DeviceKeyManager.rotateKeyPair() }
                .getOrElse { throw IllegalStateException("Failed to rotate device encryption key") }
            Log.d(TAG, "Rotated device encryption key")
        } else {
            Log.d(TAG, "Rebinding current device encryption key")
        }

        return when (val refreshResult = tokenManager.refreshTokenWithResult(RetrofitClient.authApiService, forceRefresh = true)) {
            is TokenRefreshResult.Success -> refreshResult.accessToken
            is TokenRefreshResult.RateLimited -> throw IllegalStateException(buildRetryAfterMessage(refreshResult.retryAfterSeconds))
            is TokenRefreshResult.Failure -> {
                if (refreshResult.statusCode == 403 && refreshResult.errorCode.equals("DEVICE_NOT_REGISTERED", ignoreCase = true)) {
                    forceLogoutForDeviceRegistration("This device key changed and the session must be re-authenticated. Please sign in again.")
                }
                throw IllegalStateException(refreshResult.message ?: "Failed to rebind rotated device key", null)
            }
            is TokenRefreshResult.ExceptionFailure -> throw IllegalStateException(
                refreshResult.throwable.localizedMessage ?: "Failed to rebind rotated device key",
                refreshResult.throwable
            )
            TokenRefreshResult.Skipped -> tokenManager.getAccessToken()
                ?: throw IllegalStateException("Authentication token missing after device key rotation")
        }
    }

    private suspend fun forceLogoutForDeviceRegistration(message: String): Nothing {
        _errorMessage.value = message
        _isConnecting.value = false

        try {
            net.libreguard.vpn.util.LogoutManager.logout()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to perform logout after device registration error")
        }

        val logoutIntent = Intent("net.libreguard.vpn.ACTION_LOGOUT")
        logoutIntent.setPackage(getApplication<Application>().packageName)
        getApplication<Application>().sendBroadcast(logoutIntent)
        throw IllegalStateException(message)
    }

    private suspend fun fetchVpnConfigWithRecovery(
        request: VpnConfigRequest,
        tokenManager: TokenManager
    ): retrofit2.Response<net.libreguard.vpn.network.VpnConfigResponse> {
        var token = tokenManager.getAccessToken()
            ?: throw IllegalStateException("Authentication token missing")

        val boundKeyId = tokenManager.getBoundDeviceKeyId()
        val currentKeyId = withContext(Dispatchers.IO) { runCatching { net.libreguard.vpn.util.DeviceKeyManager.publicKeyId() }.getOrNull() }
        Log.d(
            TAG,
            "Fetching VPN config: boundKeyId=${boundKeyId?.take(8)}... currentKeyId=${currentKeyId?.take(8)}..."
        )
        if (!boundKeyId.isNullOrBlank() && !currentKeyId.isNullOrBlank() && boundKeyId != currentKeyId) {
            Log.w(TAG, "Bound device key id differs from local; forcing refresh")
            token = forceRefreshWithDeviceKeyRebind(tokenManager, rotateLocalKey = false)
        }

        var response = RetrofitClient.instance.getVpnConfig("Bearer $token", request)

        if (response.code() == 409) {
            val errorStr = response.errorBody()?.string().orEmpty()
            val errorJson = runCatching { JSONObject(errorStr) }.getOrNull()
            val errorCode = errorJson?.optString("errorCode")

            if (errorCode.equals("DEVICE_KEY_REQUIRED", ignoreCase = true)) {
                Log.d(TAG, "Device key required, forcing refresh")
                _errorMessage.value = "Updating secure device session..."

                when (val refreshResult = tokenManager.refreshTokenWithResult(RetrofitClient.authApiService, forceRefresh = true)) {
                    is TokenRefreshResult.Success -> {
                        token = refreshResult.accessToken
                        response = RetrofitClient.instance.getVpnConfig("Bearer $token", request)
                    }
                    is TokenRefreshResult.RateLimited -> {
                        throw IllegalStateException(buildRetryAfterMessage(refreshResult.retryAfterSeconds))
                    }
                    is TokenRefreshResult.Failure -> {
                        if (refreshResult.statusCode == 403 && refreshResult.errorCode.equals("DEVICE_NOT_REGISTERED", ignoreCase = true)) {
                            forceLogoutForDeviceRegistration("This device is no longer registered. Please sign in again.")
                        }

                        throw IllegalStateException(
                            refreshResult.message ?: "Failed to refresh secure device session. Please sign in again."
                        )
                    }
                    is TokenRefreshResult.ExceptionFailure -> {
                        throw IllegalStateException(
                            refreshResult.throwable.localizedMessage ?: "Failed to refresh secure device session",
                            refreshResult.throwable
                        )
                    }
                    TokenRefreshResult.Skipped -> {
                        token = tokenManager.getAccessToken()
                            ?: throw IllegalStateException("Authentication token missing")
                        response = RetrofitClient.instance.getVpnConfig("Bearer $token", request)
                    }
                }
            }
        }

        if (response.code() == 403) {
            val errorStr = response.errorBody()?.string().orEmpty()
            val errorJson = runCatching { JSONObject(errorStr) }.getOrNull()
            if (errorJson?.optString("errorCode").equals("DEVICE_NOT_REGISTERED", ignoreCase = true)) {
                forceLogoutForDeviceRegistration("This device is not registered anymore. Please sign in again.")
            }
            if (errorJson?.optString("message")?.isNotBlank() == true) {
                throw IllegalStateException(errorJson.optString("message"))
            }
        }

        if (response.code() == 429) {
            val retryAfter = response.headers()["Retry-After"]?.toIntOrNull()
            throw IllegalStateException(buildRetryAfterMessage(retryAfter))
        }

        if (response.isSuccessful && response.body()?.success == true) {
            val body = response.body()
            Log.d(
                TAG,
                "VPN config fetch succeeded for protocol=${request.protocol}"
            )
        }

        return response
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
                    Log.w(TAG, "Certificate already exists (409)")
                    return@withContext true
                }

                if (!reqResp.isSuccessful) {
                    _errorMessage.value = reqResp.body()?.message
                        ?: "Failed to request certificate"
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
                Log.e(TAG, "ensureCertificateIssued error")
                _errorMessage.value = "Certificate request error"
                false
            }
        }
    }

    private suspend fun connectWithConfig(
        configContent: String,
        server: RemoteVpnServer,
        protocol: VpnProtocol,
        certificateName: String?,
        passphrase: String?,
        connectSessionId: Long
    ) {
        try {
            ensureConnectSessionActive(connectSessionId)
            val appContext = getApplication<Application>().applicationContext

            // Create and initialize the appropriate VPN handler based on protocol
            activeVpnHandler = VpnProtocolFactory.createHandler(protocol, appContext)

            val initialized = withContext(Dispatchers.IO) {
                activeVpnHandler?.initialize(appContext) ?: false
            }

            ensureConnectSessionActive(connectSessionId)
            if (!initialized) {
                throw Exception("Failed to initialize ${protocol.displayName} handler")
            }

            when (protocol) {
                VpnProtocol.IKEV2_IPSEC -> {
                    connectStrongSwan(configContent, certificateName, passphrase, connectSessionId)
                }
                VpnProtocol.OPENVPN -> {
                    connectOpenVpn(configContent)
                }
                VpnProtocol.WIREGUARD -> {
                    connectWireGuard(configContent)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error in connectWithConfig", e)
            _isConnected.value = false
            _errorMessage.value = "Connection error"
            activeVpnHandler = null
        }
    }

    // Certificate diagnostics logging helper
    private fun logUserCert(alias: String?) {
        try {
            configManager.logUserCertDiagnostics(TAG, alias)
        } catch (e: Exception) {
            Log.w(TAG, "Cert diagnostics failed")
        }
    }

    // Modified connectStrongSwan implementing checklist gating
    private suspend fun connectStrongSwan(configContent: String, certificateName: String?, passphrase: String?, connectSessionId: Long) {
        try {
            ensureConnectSessionActive(connectSessionId)
            val context = getApplication<Application>().applicationContext
            Log.d(TAG, "[CertFlow] Starting strongSwan connection parse phase")
            val profile = configManager.parseServerResponseToProfile(configContent)
                ?: throw Exception("Failed to parse server response into VpnProfile")
            if (!passphrase.isNullOrBlank() && profile.password.isNullOrBlank()) {
                profile.password = passphrase
                Log.d(TAG, "[CertFlow] Applied external passphrase to profile")
            }
            Log.d(TAG, "[CertFlow] Parsed profile gateway=${profile.gateway} vpnType=${profile.vpnType}")
            withContext(Dispatchers.IO) { logUserCert(profile.userCertificateAlias) }
            ensureConnectSessionActive(connectSessionId)

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
                        Log.d(TAG, "[CertFlow] Using mapped installed alias for this user")
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
            ensureConnectSessionActive(connectSessionId)
            completeStrongSwanConnection(profile, connectSessionId)
        } catch (e: CancellationException) {
            throw e
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
        val connectSessionId = activeConnectSessionId
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
                    try {
                        ensureConnectSessionActive(connectSessionId)
                        withContext(Dispatchers.IO) { logUserCert(chosenAlias) }
                        prof.userCertificateAlias = chosenAlias
                        val diag = withContext(Dispatchers.IO) { configManager.diagnoseUserCertificate(chosenAlias) }
                        ensureConnectSessionActive(connectSessionId)
                        if (diag.state == VpnConfigManager.UserCertState.INSTALLED_OK) {
                            val remoteKey = perUserRemoteId(prof.remoteId, prof.username)
                            withContext(Dispatchers.IO) { configManager.saveMappedCertAlias(prof.gateway, remoteKey, chosenAlias) }
                            _errorMessage.value = "Certificate selected: $chosenAlias"
                            completeStrongSwanConnection(prof, connectSessionId)
                            pendingProfile = null
                        } else {
                            _errorMessage.value = "Selected certificate not usable (state=${diag.state}). Try another."; _showCertPicker.value = true
                        }
                    } catch (e: CancellationException) {
                        Log.d(TAG, "Certificate selection ignored because the connection attempt was cancelled")
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
        val connectSessionId = activeConnectSessionId
        viewModelScope.launch {
            try {
                ensureConnectSessionActive(connectSessionId)
                Log.d(TAG, "[CertFlow] Resume after certificate install for alias=${prof.userCertificateAlias}")
                withContext(Dispatchers.IO) { logUserCert(prof.userCertificateAlias) }
                val alias = prof.userCertificateAlias
                if (alias != null) {
                    // Poll for the certificate to become available (installation is async)
                    var diag = withContext(Dispatchers.IO) { configManager.diagnoseUserCertificate(alias) }
                    var attempts = 0
                    while (diag.state != VpnConfigManager.UserCertState.INSTALLED_OK && attempts < 10) {
                        ensureConnectSessionActive(connectSessionId)
                        attempts++
                        Log.d(TAG, "[CertFlow] Waiting for KeyChain to expose cert alias=$alias attempt=$attempts state=${diag.state}")
                        delay(500)
                        diag = withContext(Dispatchers.IO) { configManager.diagnoseUserCertificate(alias) }
                    }
                    ensureConnectSessionActive(connectSessionId)
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
                completeStrongSwanConnection(prof, connectSessionId)
                pendingProfile = null
            } catch (e: CancellationException) {
                Log.d(TAG, "Certificate-install resume ignored because the connection attempt was cancelled")
            }
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

    private suspend fun completeStrongSwanConnection(profile: VpnProfile, connectSessionId: Long) {
        ensureConnectSessionActive(connectSessionId)
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
        ensureConnectSessionActive(connectSessionId)

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
                        if (!isHandlerStateRelevant(connectSessionId)) {
                            Log.d(TAG, "Ignoring stale StrongSwan state after connection cancel: $state")
                            return@collect
                        }
                        Log.d(TAG, "StrongSwan connection state changed: $state")
                        when (state) {
                            is ConnectionState.Connecting -> {
                                _isConnecting.value = true
                                _isConnected.value = false
                                _errorMessage.value = "Connecting to ${profile.gateway}..."
                            }
                            is ConnectionState.Connected -> {
                                val recoveredFromUiRace = if (!_isConnecting.value && !_isConnected.value) {
                                    checkVpnStatusImproved()
                                } else {
                                    false
                                }
                                if (_isConnecting.value || _isConnected.value || recoveredFromUiRace) {
                                    if (recoveredFromUiRace && !_isConnecting.value && !_isConnected.value) {
                                        Log.w(TAG, "Accepting Connected state after UI race because the VPN tunnel is verified active")
                                    }
                                    restoreActiveVpnUiState("Connected using IKEv2/IPSec to ${profile.gateway}")
                                    Log.d(TAG, "Successfully connected via StateFlow")
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

                                // Notify Kill Switch of unexpected disconnect
                                notifyKillSwitchDisconnected(isManual = false)
                                dataUsageManager.stopMonitoring()
                                stateObserverJob?.cancel()
                                stateObserverJob = null
                            }
                            is ConnectionState.Disconnecting -> {
                                _isConnecting.value = false
                                _errorMessage.value = "Disconnecting..."
                            }
                            is ConnectionState.Error -> {
                                val wasConnected = _isConnected.value
                                _isConnected.value = false
                                _isConnecting.value = false
                                _errorMessage.value = state.message
                                Log.e(TAG, "Connection error via StateFlow: ${state.message}")

                                // Show notification for data limit exceeded
                                if (state.message.contains("Traffic limit exceeded", ignoreCase = true) ||
                                    state.message.contains("Data limit", ignoreCase = true)) {
                                    Log.w(TAG, "Data limit exceeded detected - showing notification")
                                    VpnNotificationManager.showDataLimitExceeded(getApplication<Application>().applicationContext)

                                    // Also emit upgrade event
                                    viewModelScope.launch {
                                        _upgradeEvents.emit(mapOf(
                                            "reason" to "Data limit exceeded while connected",
                                            "resource_type" to "data_quota",
                                            "resource_id" to null,
                                            "required_tier" to "Pro",
                                            "message" to "Your monthly data limit has been reached. VPN has been disconnected.",
                                            "reset_date" to dataUsageManager.dataUsage.value.resetDate
                                        ))
                                    }
                                }

                                // Notify Kill Switch of unexpected disconnect if we were connected
                                if (wasConnected) {
                                    Log.w(TAG, "Connection lost unexpectedly: ${state.message}")
                                    notifyKillSwitchDisconnected(isManual = false)
                                }

                                dataUsageManager.stopMonitoring()
                                stateObserverJob?.cancel()
                                stateObserverJob = null

                                // Clear persisted state since connection failed
                                clearPersistedState()
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    Log.d(TAG, "StrongSwan state observer cancelled")
                } catch (e: Exception) {
                    Log.e(TAG, "StateFlow observer error: ${e.message}")
                }
            }
        }
        ensureConnectSessionActive(connectSessionId)

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
                // Set connection establishment timestamp for grace period
                connectionEstablishedTimestamp = System.currentTimeMillis()
                _errorMessage.value = "Connected using WireGuard"
                Log.d(TAG, "Successfully connected using WireGuard")

                // Start data usage monitoring when VPN connects
                dataUsageManager.startMonitoring()
                Log.d(TAG, "Started data usage monitoring")

                startConnectionTracking()

                // Save connection state for persistence
                saveConnectionState()

                // Notify Kill Switch that VPN connected
                notifyKillSwitchConnected()
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
                val tokenManager = RetrofitClient.getTokenManager()
                val token = tokenManager.getAccessToken() ?: return@withContext false
                val cacheDir = getUserScopedDir(context, VpnProtocol.OPENVPN)
                val cacheFile = File(cacheDir, "openvpn_${serverId}.ovpn")

                // Ensure handler
                activeVpnHandler = VpnProtocolFactory.createHandler(VpnProtocol.OPENVPN, context)
                val initialized = activeVpnHandler?.initialize(context) ?: false
                if (!initialized) throw Exception("Failed to initialize OpenVPN handler")

                // Start observing state before connect
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
                            // Re-read token (could have been refreshed by background auth)
                            val refreshedToken = tokenManager.getAccessToken() ?: token
                            resp = RetrofitClient.instance.downloadOpenVpnConfig(
                                authorization = "Bearer $refreshedToken",
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

        // Notify Kill Switch that VPN connected
        notifyKillSwitchConnected()
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

        // Trigger statistics refresh for real-time UI updates
        _statisticsRefreshTrigger.value = System.currentTimeMillis()

        // Start connection duration timer
        startConnectionTimer()

        // Fetch and set VPN IP
        fetchVpnIP(server.serverIp)

        // Start periodic quota refresh (every 60 seconds)
        startQuotaRefreshTimer()
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
     * Fetch user's real IP address BEFORE connecting to VPN.
     * This captures the user's actual IP so it can be displayed as "Your IP" while connected.
     * Should only be called when VPN is disconnected to get the true IP.
     */
    fun fetchUserIP() {
        // Only fetch if VPN is not connected to avoid getting VPN server IP
        if (_isConnected.value) {
            Log.d(TAG, "Skipping user IP fetch - VPN is connected")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val url = java.net.URL("https://api.ipify.org?format=json")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonObject = org.json.JSONObject(response)
                    val ip = jsonObject.getString("ip")
                    withContext(Dispatchers.Main) {
                        _userIP.value = ip
                        Log.d(TAG, "User's real IP captured: $ip")
                    }
                } else {
                    Log.w(TAG, "Failed to fetch user IP: HTTP ${connection.responseCode}")
                    withContext(Dispatchers.Main) {
                        if (_userIP.value == "Loading...") {
                            _userIP.value = "Unknown"
                        }
                    }
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch user IP: ${e.message}")
                withContext(Dispatchers.Main) {
                    if (_userIP.value == "Loading...") {
                        _userIP.value = "Unknown"
                    }
                }
            }
        }
    }

    /**
     * Stop tracking connection and update history
     */
    private fun stopConnectionTracking() {
        val sessionDataMB = _dataUsageInfo.value.sessionBytesUsed / (1024.0 * 1024.0)
        val dataUsedMB = sessionDataMB.coerceAtLeast(0.0)

        connectionHistoryManager.updateLastRecord(
            disconnectedAt = System.currentTimeMillis(),
            dataUsedMB = dataUsedMB
        )
        Log.d(
            TAG,
            "Stopped tracking connection. Session data used: ${String.format(java.util.Locale.US, "%.2f", dataUsedMB)} MB"
        )

        // Trigger statistics refresh for real-time UI updates
        _statisticsRefreshTrigger.value = System.currentTimeMillis()

        currentConnectionStartTime = null
        currentConnectionDataStart = 0.0

        // Clear connection establishment timestamp
        connectionEstablishedTimestamp = null

        // Stop connection timer
        connectionTimerJob?.cancel()
        connectionTimerJob = null
        _connectionDuration.value = "00:00:00"

        // Clear VPN IP
        _vpnIP.value = ""

        // Stop quota refresh timer
        stopQuotaRefreshTimer()
    }

    /**
     * Start periodic quota refresh timer (every 60 seconds)
     */
    private fun startQuotaRefreshTimer() {
        quotaRefreshJob?.cancel()
        quotaRefreshJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive && _isConnected.value) {
                delay(60000) // Wait 60 seconds
                if (_isConnected.value) {
                    Log.d(TAG, "Refreshing quota from server (60s timer)")
                    dataUsageManager.syncQuotaFromServer()
                }
            }
        }
        Log.d(TAG, "Started quota refresh timer (60s interval)")
    }

    /**
     * Stop periodic quota refresh timer
     */
    private fun stopQuotaRefreshTimer() {
        quotaRefreshJob?.cancel()
        quotaRefreshJob = null
        Log.d(TAG, "Stopped quota refresh timer")
    }

    // Observe active handler state (especially for OpenVPN) and reflect in UI
    private fun observeActiveHandlerState() {
        openVpnStateJob?.cancel()
        val handler = activeVpnHandler ?: return
        val connectSessionId = activeConnectSessionId
        openVpnStateJob = viewModelScope.launch {
            try {
                handler.connectionState.collect { st ->
                    if (!isHandlerStateRelevant(connectSessionId)) {
                        Log.d(TAG, "Ignoring stale handler state after connection cancel: $st")
                        return@collect
                    }
                    when (st) {
                        is ConnectionState.Connected -> {
                            _isConnected.value = true
                            _isConnecting.value = false
                            // Set connection establishment timestamp for grace period
                            connectionEstablishedTimestamp = System.currentTimeMillis()
                            // Ensure timer/IP tracking starts even when connection comes from handler state
                            if (currentConnectionStartTime == null) {
                                startConnectionTracking()
                                saveConnectionState()
                            }
                            // Notify Kill Switch of successful connection
                            notifyKillSwitchConnected()
                        }
                        is ConnectionState.Connecting -> {
                            _isConnecting.value = true
                            _isConnected.value = false
                        }
                        is ConnectionState.Disconnecting -> {
                            _isConnecting.value = false
                            _isConnected.value = false
                            _errorMessage.value = "Cancelling connection..."
                        }
                        is ConnectionState.Disconnected -> {
                            val wasConnected = _isConnected.value
                            // If previously connected, stop monitoring and clear persisted state
                            if (wasConnected) {
                                dataUsageManager.stopMonitoring()
                                clearPersistedState()
                                // Notify Kill Switch of unexpected disconnect
                                notifyKillSwitchDisconnected(isManual = false)
                            }
                            _isConnecting.value = false
                            _isConnected.value = false
                        }
                        is ConnectionState.Error -> {
                            _errorMessage.value = st.message
                            val wasConnected = _isConnected.value

                            // Show notification for data limit exceeded
                            if (st.message.contains("Traffic limit exceeded", ignoreCase = true) ||
                                st.message.contains("Data limit", ignoreCase = true)) {
                                Log.w(TAG, "Data limit exceeded detected - showing notification")
                                VpnNotificationManager.showDataLimitExceeded(getApplication<Application>().applicationContext)

                                // Also emit upgrade event
                                viewModelScope.launch {
                                    _upgradeEvents.emit(mapOf(
                                        "reason" to "Data limit exceeded while connected",
                                        "resource_type" to "data_quota",
                                        "resource_id" to null,
                                        "required_tier" to "Pro",
                                        "message" to "Your monthly data limit has been reached. VPN has been disconnected.",
                                        "reset_date" to dataUsageManager.dataUsage.value.resetDate
                                    ))
                                }
                            }

                            // Treat as disconnected in UI
                            if (wasConnected) {
                                Log.w(TAG, "Connection error from handler: ${st.message}")
                                dataUsageManager.stopMonitoring()
                                clearPersistedState()
                                // Notify Kill Switch of unexpected disconnect
                                notifyKillSwitchDisconnected(isManual = false)
                            }
                            _isConnecting.value = false
                            _isConnected.value = false
                        }
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) {
                    Log.d(TAG, "Handler state observer cancelled")
                } else {
                    Log.w(TAG, "Handler state observe error: ${t.message}")
                }
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
                // Set connection establishment timestamp for grace period
                connectionEstablishedTimestamp = System.currentTimeMillis()
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
            var disconnectSessionId = activeConnectSessionId
            var vpnStillActiveAfterDisconnect = false
            try {
                val context = getApplication<Application>().applicationContext
                val cancelInProgress = _isConnecting.value && !_isConnected.value
                Log.d(TAG, "Starting disconnect process - activeHandler exists: ${activeVpnHandler != null}")

                if (cancelInProgress) {
                    Log.d(TAG, "Cancelling active VPN connection attempt")
                    cancelPendingConnectAttempt("Cancelling connection...")
                    _isConnecting.value = false
                    _isConnected.value = false
                } else {
                    clearPendingConnectArtifacts()
                }
                disconnectSessionId = activeConnectSessionId

                // Validate token before disconnect with SHORT timeout (2 seconds)
                // If revoked, still allow disconnect to happen but mark it as a forced logout scenario
                // If validation times out (server unresponsive), skip it and proceed with disconnect
                val token = authToken
                val manager = tokenValidationManager
                if (!cancelInProgress && token != null && manager != null) {
                    Log.d(TAG, "Validating token before VPN disconnection (2 second timeout)")
                    val tokenValid = manager.validateTokenWithTimeout(timeoutMs = 2000L)
                    if (!tokenValid) {
                        Log.w(TAG, "Token invalid during disconnect - will proceed with disconnect and logout")
                        _errorMessage.value = "Token revoked. Disconnecting and logging out..."
                    }
                }

                // Cancel OpenVPN state observer
                openVpnStateJob?.cancel()
                openVpnStateJob = null

                // Cancel StrongSwan state observer as well; disconnect() updates UI state explicitly.
                stateObserverJob?.cancel()
                stateObserverJob = null

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

                if (!isConnectSessionActive(disconnectSessionId)) {
                    Log.d(TAG, "Skipping disconnect verification because a newer connection attempt started")
                    return@launch
                }

                // Final verification: check if VPN is actually disconnected
                vpnStillActiveAfterDisconnect = checkVpnStatusImproved()
                if (vpnStillActiveAfterDisconnect) {
                    Log.w(TAG, "VPN still appears to be active after disconnect")
                    restoreActiveVpnUiState("Connected using ${_selectedProtocol.value.displayName}")
                } else {
                    Log.d(TAG, "VPN successfully disconnected - no active VPN detected")
                    _errorMessage.value = if (cancelInProgress) "Connection cancelled" else "VPN disconnected successfully"
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error during disconnect process", e)
                _errorMessage.value = "Disconnect error: ${e.localizedMessage}"
            } finally {
                if (!isConnectSessionActive(disconnectSessionId)) {
                    Log.d(TAG, "Skipping disconnect cleanup because a newer connection attempt is active")
                } else if (vpnStillActiveAfterDisconnect) {
                    Log.d(TAG, "Skipping disconnect cleanup because the VPN tunnel is still active")
                } else {
                // Stop connection history tracking
                    stopConnectionTracking()

                // Stop data usage monitoring when VPN disconnects
                    dataUsageManager.stopMonitoring()
                    Log.d(TAG, "Stopped data usage monitoring")

                // Always clean up state regardless of disconnect success
                    activeVpnHandler = null
                    activeConnectJob = null
                    _isConnected.value = false
                    _isConnecting.value = false

                // Notify Kill Switch of manual disconnect (don't block traffic)
                    notifyKillSwitchDisconnected(isManual = true)

                // Reset auto-connect session flag so it can trigger again on next app launch
                    resetAutoConnectSession()

                // Clear persisted state when manually disconnecting
                    clearPersistedState()

                    Log.d(TAG, "Disconnect process completed - connection state cleared")
                }
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

            // Method 3: Check network interfaces for active VPN tunnels
            val isVpnInterfaceActive = try {
                val networkInterfaces = java.net.NetworkInterface.getNetworkInterfaces()
                var hasTunInterface = false
                while (networkInterfaces.hasMoreElements()) {
                    val networkInterface = networkInterfaces.nextElement()
                    // Check for common VPN interface prefixes: tun (OpenVPN/WireGuard), ipsec (StrongSwan/IPSec)
                    if ((networkInterface.name.startsWith("tun") ||
                         networkInterface.name.startsWith("ipsec") ||
                         networkInterface.name.startsWith("wg")) &&
                        networkInterface.isUp) {
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

            Log.d(TAG, "VPN status check details: vpnServiceReady=$isVpnServiceReady, transport=$isVpnTransport, interface=$isVpnInterfaceActive")

            // SMART VALIDATION: Require service ready AND (transport OR interface)
            // This prevents false positives from stale state while handling platform variations
            // where transport detection may be delayed or unreliable on some Android versions/OEMs
            val isActive = isVpnServiceReady && (isVpnTransport || isVpnInterfaceActive)

            if (isActive) {
                Log.d(TAG, "✅ VPN is actively connected (validation passed: transport=$isVpnTransport, interface=$isVpnInterfaceActive)")
            } else {
                Log.d(TAG, "❌ VPN is NOT active (validation failed)")
            }

            isActive
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
     * CRITICAL FIX: Delegates to SubscriptionViewModel to maintain single source of truth
     */
    fun updateSubscriptionStatus() {
        Log.d(TAG, "updateSubscriptionStatus() called - triggering subscription refresh via SubscriptionViewModel")
        subscriptionViewModel.markPurchaseVerified()
        applyPendingUpgradeSelectionsAfterPurchase()
        subscriptionViewModel.subscriptionUpdated()

        viewModelScope.launch {
            dataUsageManager.forceQuotaRefresh()
        }
    }

    // ===== AUTO-CONNECT FEATURE =====

    /**
     * Load auto-connect preference from SharedPreferences
     * Now user-scoped: each user has their own auto-connect preference
     * Pro-only enforcement: Free users cannot have auto-connect enabled
     */
    private fun loadAutoConnectPreference() {
        try {
            // Use user-scoped key if user ID is available
            val prefsKey = if (currentUserId != null) {
                "auto_connect_enabled_${currentUserId}"
            } else {
                "auto_connect_enabled"
            }

            var enabled = sharedPrefs.getBoolean(prefsKey, false)

            // CRITICAL: Enforce Pro-only feature
            // If user has Auto-Connect enabled but is not Pro, reset to disabled
            if (enabled && !isPro.value) {
                Log.w(TAG, "User has Auto-Connect enabled but is not Pro - resetting to disabled")
                enabled = false
                // Save the corrected preference
                sharedPrefs.edit().putBoolean(prefsKey, false).apply()
            }

            _autoConnectEnabled.value = enabled
            Log.d(TAG, "Loaded Auto-Connect preference: $enabled (user: ${currentUserId ?: "global"})")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load auto-connect preference")
            _autoConnectEnabled.value = false
        }
    }

    /**
     * Enable or disable auto-connect on app launch
     * Persists preference to SharedPreferences
     * Now user-scoped: each user has their own auto-connect preference
     */
    fun setAutoConnect(enabled: Boolean) {
        _autoConnectEnabled.value = enabled

        // Use user-scoped key if user ID is available
        val prefsKey = if (currentUserId != null) {
            "auto_connect_enabled_${currentUserId}"
        } else {
            "auto_connect_enabled"
        }

        sharedPrefs.edit()
            .putBoolean(prefsKey, enabled)
            .apply()
        Log.d(TAG, "Auto-Connect preference set to: $enabled (user: ${currentUserId ?: "global"})")
    }

    /**
     * Attempt auto-connect on app launch (if enabled)
     * SECURITY: Validates token, checks servers, ensures proper authentication
     *
     * @return true if auto-connect was attempted, false otherwise
     */
    suspend fun attemptAutoConnect(): Boolean {
        // Guard 1: Check if already auto-connected this session
        if (hasAutoConnectedThisSession) {
            Log.d(TAG, "Auto-Connect: Already attempted this session, skipping")
            return false
        }

        // Guard 2: Check if auto-connect is enabled
        if (!_autoConnectEnabled.value) {
            Log.d(TAG, "Auto-Connect: Disabled by user, skipping")
            return false
        }

        // Guard 3: Check if already connected or connecting
        // CRITICAL FIX: Verify actual VPN status, not just cached state
        // This prevents skipping auto-connect due to ghost connection state
        if (_isConnected.value || _isConnecting.value) {
            Log.d(TAG, "Auto-Connect: State shows connected/connecting, verifying actual VPN status...")

            // Re-validate actual VPN status
            val actuallyConnected = checkVpnStatusImproved()

            if (actuallyConnected) {
                Log.d(TAG, "Auto-Connect: VPN verified active, skipping")
                hasAutoConnectedThisSession = true
                return false
            } else {
                // Ghost connection state detected - clear it and proceed with auto-connect
                Log.w(TAG, "⚠️ Auto-Connect: Ghost connection detected (UI shows connected but VPN inactive)")
                _isConnected.value = false
                _connectionState.value = ConnectionState.Disconnected
                _isConnecting.value = false
                activeVpnHandler = null
                clearPersistedState()
                Log.d(TAG, "Auto-Connect: Cleared ghost state, proceeding with auto-connect")
                // Continue to next guards
            }
        }

        // Guard 4: Verify authentication token exists
        if (authToken.isNullOrBlank()) {
            Log.w(TAG, "Auto-Connect: No auth token available, cannot connect")
            return false
        }

        // Guard 5: Verify refresh token exists (for token rotation)
        val refreshToken = RetrofitClient.getTokenManager().getRefreshToken()
        if (refreshToken.isNullOrBlank()) {
            Log.w(TAG, "Auto-Connect: No refresh token available, skipping for security")
            return false
        }

        // Guard 6: Check if servers are loaded
        if (_servers.value.isEmpty()) {
            Log.d(TAG, "Auto-Connect: No servers available, loading servers first...")
            // Try to load servers
            loadRemoteServers()
            delay(2000) // Wait for servers to load

            if (_servers.value.isEmpty()) {
                Log.w(TAG, "Auto-Connect: Failed to load servers, cannot connect")
                return false
            }
        }

        // Guard 7: Validate token before connecting (optional but recommended)
        // This prevents auto-connecting with an expired/revoked token
        try {
            tokenValidationManager?.let { manager ->
                val tokenValid = manager.validateTokenBeforeAction()
                if (!tokenValid) {
                    Log.w(TAG, "Auto-Connect: Token validation failed, cannot connect")
                    return false
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Auto-Connect: Token validation error: ${e.message}, proceeding anyway")
        }

        // Mark as attempted for this session
        hasAutoConnectedThisSession = true

        // Determine which mode to use: Quick Connect or Manual
        Log.d(TAG, "Auto-Connect: Starting connection (mode: ${if (_isQuickConnectMode.value) "Quick" else "Manual"})")

        if (_isQuickConnectMode.value) {
            // Use Quick Connect algorithm
            quickConnect()
        } else {
            // Use last manually selected server
            val lastServer = _selectedServer.value
            if (lastServer != null) {
                Log.d(TAG, "Auto-Connect: Using last server: ${lastServer.serverName}")
                connectToVpn()
            } else {
                // Fallback to Quick Connect if no manual server
                Log.d(TAG, "Auto-Connect: No manual server selected, using Quick Connect")
                quickConnect()
            }
        }

        return true
    }

    /**
     * Reset auto-connect session flag
     * Called on app restart or when user manually disconnects
     */
    fun resetAutoConnectSession() {
        hasAutoConnectedThisSession = false
        Log.d(TAG, "Auto-Connect session flag reset")
    }

    // ===== KILL SWITCH FEATURE =====

    /**
     * Load Kill Switch preference from SharedPreferences
     * Now user-scoped: each user has their own kill switch preference
     * Pro-only enforcement: Free users cannot have kill switch enabled
     */
    private fun loadKillSwitchPreference() {
        try {
            // Use user-scoped key if user ID is available
            val prefsKey = if (currentUserId != null) {
                "kill_switch_enabled_${currentUserId}"
            } else {
                "kill_switch_enabled"
            }

            var enabled = sharedPrefs.getBoolean(prefsKey, false)

            // CRITICAL: Enforce Pro-only feature
            // If user has Kill Switch enabled but is not Pro, reset to disabled
            if (enabled && !isPro.value) {
                Log.w(TAG, "User has Kill Switch enabled but is not Pro - resetting to disabled")
                enabled = false
                // Save the corrected preference
                sharedPrefs.edit().putBoolean(prefsKey, false).apply()
            }

            _killSwitchEnabled.value = enabled
            killSwitchManager.setEnabled(enabled)
            Log.d(TAG, "Loaded Kill Switch preference: $enabled (user: ${currentUserId ?: "global"})")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load kill switch preference")
            _killSwitchEnabled.value = false
            killSwitchManager.setEnabled(false)
        }
    }

    /**
     * Enable or disable Kill Switch
     * Persists preference to SharedPreferences
     * Now user-scoped: each user has their own kill switch preference
     */
    fun setKillSwitch(enabled: Boolean) {
        _killSwitchEnabled.value = enabled

        // Use user-scoped key if user ID is available
        val prefsKey = if (currentUserId != null) {
            "kill_switch_enabled_${currentUserId}"
        } else {
            "kill_switch_enabled"
        }

        sharedPrefs.edit()
            .putBoolean(prefsKey, enabled)
            .apply()
        killSwitchManager.setEnabled(enabled)
        Log.d(TAG, "Kill Switch preference set to: $enabled (user: ${currentUserId ?: "global"})")
    }

    /**
     * Notify Kill Switch when VPN connects
     * Should be called after successful connection
     */
    private fun notifyKillSwitchConnected() {
        if (_killSwitchEnabled.value) {
            killSwitchManager.onVpnConnected()
            Log.d(TAG, "Notified Kill Switch: VPN connected")
        }
    }

    /**
     * Notify Kill Switch when VPN disconnects
     * @param isManual true if user manually disconnected, false if unexpected
     */
    private fun notifyKillSwitchDisconnected(isManual: Boolean) {
        if (_killSwitchEnabled.value) {
            killSwitchManager.onVpnDisconnected(isManual)
            Log.d(TAG, "Notified Kill Switch: VPN disconnected (manual=$isManual)")
        }
    }

    /**
     * Load default protocol preference from SharedPreferences
     * Now user-scoped: each user has their own protocol preference
     * Enforces Pro-only protocols: if user is not Pro and has OpenVPN saved, reset to IKEv2
     */
    private fun loadDefaultProtocol() {
        try {
            // Use user-scoped key if user ID is available
            val prefsKey = if (currentUserId != null) {
                "default_protocol_${currentUserId}"
            } else {
                "default_protocol"
            }

            val protocolName = sharedPrefs.getString(prefsKey, VpnProtocol.IKEV2_IPSEC.displayName)
            var protocol = VpnProtocol.values().find { it.displayName == protocolName } ?: VpnProtocol.IKEV2_IPSEC

            // CRITICAL: Enforce Pro-only protocols
            // If user has OpenVPN saved but is not Pro, reset to IKEv2
            if (protocol == VpnProtocol.OPENVPN && !isPro.value) {
                Log.w(TAG, "User has OpenVPN saved but is not Pro - resetting to IKEv2")
                protocol = VpnProtocol.IKEV2_IPSEC
                // Save the corrected preference
                sharedPrefs.edit().putString(prefsKey, protocol.displayName).apply()
            }

            _selectedProtocol.value = protocol
            Log.d(TAG, "Loaded Default Protocol preference: ${protocol.displayName} (user: ${currentUserId ?: "global"})")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load default protocol preference")
            _selectedProtocol.value = VpnProtocol.IKEV2_IPSEC
        }
    }

    /**
     * Save default protocol preference to SharedPreferences
     * Now user-scoped: each user has their own protocol preference
     * Called when user selects a protocol in Settings
     */
    fun saveDefaultProtocol(protocol: VpnProtocol) {
        _selectedProtocol.value = protocol

        // Use user-scoped key if user ID is available
        val prefsKey = if (currentUserId != null) {
            "default_protocol_${currentUserId}"
        } else {
            "default_protocol"
        }

        sharedPrefs.edit()
            .putString(prefsKey, protocol.displayName)
            .apply()
        Log.d(TAG, "Default Protocol preference set to: ${protocol.displayName} (user: ${currentUserId ?: "global"})")
    }

    /**
     * Called by UI after the VPN consent activity returns.
     * resultOk=true means the user granted permission.
     */
    fun onVpnPermissionResult(resultOk: Boolean) {
        if (!resultOk) {
            pendingConnectAfterVpnConsent = false
            _isConnecting.value = false
            _errorMessage.value = "VPN permission denied"
            return
        }

        // User granted permission; retry pending connect if needed.
        if (pendingConnectAfterVpnConsent) {
            pendingConnectAfterVpnConsent = false
            // Kick off the normal connect flow again.
            connectToVpn()
        }
    }

    /**
     * Get current session data usage in MB
     * Used by Statistics screen for real-time "live session" display
     */
    fun getCurrentSessionDataMB(): Double {
        return _dataUsageInfo.value.sessionBytesUsed / (1024.0 * 1024.0)
    }

    /**
     * Get the connection history manager for the Statistics screen
     * This ensures the Statistics screen uses the same user-scoped instance
     */
    fun getHistoryManager(): ConnectionHistoryManager = connectionHistoryManager
}

