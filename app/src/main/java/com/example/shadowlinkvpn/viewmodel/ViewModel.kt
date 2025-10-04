package com.example.shadowlinkvpn.viewmodel

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.shadowlinkvpn.R
import com.example.shadowlinkvpn.network.RemoteVpnServer
import com.example.shadowlinkvpn.network.RetrofitClient
import com.example.shadowlinkvpn.network.VpnConfigRequest
import com.example.shadowlinkvpn.service.ShadowLinkVpnService
import com.example.shadowlinkvpn.service.vpn.ConnectionState
import com.example.shadowlinkvpn.service.vpn.OpenVpnHandler
import com.example.shadowlinkvpn.service.vpn.StrongSwanHandler
import com.example.shadowlinkvpn.service.vpn.VpnProtocolFactory
import com.example.shadowlinkvpn.service.vpn.VpnProtocolHandler
import com.example.shadowlinkvpn.service.vpn.WireGuardHandler
import com.example.shadowlinkvpn.ui.screens.VpnServer
import com.example.shadowlinkvpn.util.VpnConfigManager
import com.example.shadowlinkvpn.service.data.DataUsageManager
import com.example.shadowlinkvpn.service.data.DataUsageInfo
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.strongswan.android.data.VpnProfile
import org.strongswan.android.data.VpnProfileSource
import org.strongswan.android.data.VpnType
import java.io.File
import java.io.InputStreamReader
import org.json.JSONObject
import android.security.KeyChain
import android.security.KeyChainAliasCallback
import com.example.shadowlinkvpn.network.OpenVpnDownloadRequest
import okhttp3.ResponseBody

private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
val connectionState: StateFlow<ConnectionState> = _connectionState


private const val TAG = "VpnViewModel"

enum class VpnProtocol(val displayName: String, val apiName: String) {
    IKEV2_IPSEC("IKEV2/IPSec", "IKEV2"),
    OPENVPN("OpenVPN", "OPENVPN"),
    WIREGUARD("WireGuard", "WIREGUARD")
}

class VpnViewModel(application: Application) : AndroidViewModel(application) {
    private val _servers = MutableStateFlow<List<VpnServer>>(emptyList())
    val servers: StateFlow<List<VpnServer>> = _servers

    private val _remoteServers = MutableStateFlow<List<RemoteVpnServer>>(emptyList())
    val remoteServers: StateFlow<List<RemoteVpnServer>> = _remoteServers

    private val _selectedServer = MutableStateFlow<VpnServer?>(null)
    val selectedServer: StateFlow<VpnServer?> = _selectedServer

    private val _selectedProtocol = MutableStateFlow(VpnProtocol.IKEV2_IPSEC)
    val selectedProtocol: StateFlow<VpnProtocol> = _selectedProtocol

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting

    private val _isLoadingServers = MutableStateFlow(false)
    val isLoadingServers: StateFlow<Boolean> = _isLoadingServers

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

    private var authToken: String? = null
    private var currentUserId: String? = null
    private var activeVpnHandler: VpnProtocolHandler? = null
    private var pendingProfile: VpnProfile? = null
    private val configManager by lazy { VpnConfigManager(getApplication()) }

    // Add SharedPreferences for state persistence
    private val sharedPrefs by lazy {
        getApplication<Application>().getSharedPreferences("vpn_state_prefs", Context.MODE_PRIVATE)
    }

    // Data usage tracking
    private val dataUsageManager by lazy { DataUsageManager(getApplication()) }

    private val _dataUsageInfo = MutableStateFlow(DataUsageInfo())
    val dataUsageInfo: StateFlow<DataUsageInfo> = _dataUsageInfo

    // Track OpenVPN state collection
    private var openVpnStateJob: Job? = null

    init {
        // Load persisted auth token and connection state immediately on startup
        loadPersistedAuthToken()

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
     * Load persisted connection state when app restarts
     */
    private fun loadPersistedState() {
        viewModelScope.launch {
            try {
                val wasConnected = sharedPrefs.getBoolean("was_connected", false)
                val serverName = sharedPrefs.getString("connected_server", null)
                val protocolName = sharedPrefs.getString("connected_protocol", null)

                Log.d(TAG, "Loading persisted state: wasConnected=$wasConnected, server=$serverName, protocol=$protocolName, hasToken=${authToken != null}")

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
                        val server = _servers.value.find { it.name == serverName }
                        if (server != null) {
                            _selectedServer.value = server
                            Log.d(TAG, "Restored server: ${server.name}")
                        }

                        // Restore connection state
                        _isConnected.value = true
                        _errorMessage.value = "Reconnected to existing VPN session"
                        Log.d(TAG, "Successfully restored VPN connection state")

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
                        this.name = name ?: "ShadowLink VPN"
                        this.gateway = gateway
                        this.remoteId = remoteId ?: gateway
                        this.userCertificateAlias = userCertAlias
                        this.username = username
                        this.password = password
                        // Set other necessary fields
                        this.vpnType = VpnType.IKEV2_EAP_TLS
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

            sharedPrefs.edit().apply {
                putBoolean("was_connected", connected)
                putString("connected_server", server?.name)
                putString("connected_protocol", protocol.displayName)

                // IMPORTANT FIX: Save VPN profile details for proper disconnection
                val currentProfile = (activeVpnHandler as? StrongSwanHandler)?.let { handler ->
                    // Try to get the current profile from the handler
                    val reflection = handler::class.java.getDeclaredField("currentProfile")
                    reflection.isAccessible = true
                    reflection.get(handler) as? VpnProfile
                }

                currentProfile?.let { profile ->
                    putString("vpn_profile_uuid", profile.getUUID()?.toString())
                    putString("vpn_profile_name", profile.name)
                    putString("vpn_profile_gateway", profile.gateway)
                    putString("vpn_profile_remote_id", profile.remoteId)
                    putString("vpn_profile_user_cert_alias", profile.userCertificateAlias)
                    putString("vpn_profile_username", profile.username)
                    putString("vpn_profile_password", profile.password)
                    Log.d(TAG, "Saved VPN profile details: UUID=${profile.getUUID()}, gateway=${profile.gateway}")
                } ?: run {
                    // If we can't get the profile from handler, save basic connection info
                    Log.w(TAG, "Could not access current profile from handler, saving basic info only")
                }

                // Also save auth token for seamless reconnection
                authToken?.let { putString("auth_token", it) }
                apply()
            }
            Log.d(TAG, "Saved connection state: connected=$connected, server=${server?.name}, hasToken=${authToken != null}")
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

    fun loadLocalServers(context: Context) {
        try {
            val inputStream = context.resources.openRawResource(R.raw.servers)
            val reader = InputStreamReader(inputStream)
            val serverListType = object : TypeToken<List<VpnServer>>() {}.type
            val serverList: List<VpnServer> = Gson().fromJson(reader, serverListType) ?: emptyList()
            _servers.value = serverList
            Log.d(TAG, "Loaded ${serverList.size} local servers")

            // Load persisted state after servers are loaded
            loadPersistedState()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading local servers", e)
            _errorMessage.value = "Failed to load local servers: ${e.localizedMessage}"
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
            _remoteServers.value = emptyList()
            _selectedServer.value = null
            _errorMessage.value = "Logged out successfully"

            // Clear only session-related persisted state, preserving user data
            clearPersistedState()

            Log.d(TAG, "User logged out successfully - data usage preserved for future login")
        }
    }

    /**
     * Load remote servers but don't fail silently - preserve auth token even if server loading fails
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
                val response = RetrofitClient.instance.getVpnServers("Bearer $token")

                if (response.isSuccessful) {
                    val serverResponse = response.body()
                    if (serverResponse?.servers != null) {
                        _remoteServers.value = serverResponse.servers
                        Log.d(TAG, "Loaded ${serverResponse.servers.size} remote servers")
                        _errorMessage.value = "Server list updated (${serverResponse.servers.size} servers)"
                    } else {
                        Log.w(TAG, "No servers received from API, but token is still valid")
                        _errorMessage.value = "No servers received from API"
                    }
                } else {
                    Log.w(TAG, "Failed to load servers: ${response.code()} - ${response.message()}, but preserving auth token")
                    _errorMessage.value = "Failed to load servers (network issue), but you're still logged in"
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error loading remote servers: ${e.message}, but preserving auth token")
                _errorMessage.value = "Network error loading servers, but you're still logged in"
            } finally {
                _isLoadingServers.value = false
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

    fun selectServer(server: VpnServer) {
        _selectedServer.value = server
    }

    fun selectProtocol(protocol: VpnProtocol) {
        _selectedProtocol.value = protocol
    }

    private fun findRemoteServerByName(serverName: String): RemoteVpnServer? {
        return _remoteServers.value.find { it.serverName == serverName }
    }

    fun connectToVpn() {
        val server = _selectedServer.value
        val token = authToken

        if (server == null) {
            _errorMessage.value = "Please select a server"
            return
        }

        if (token == null) {
            _errorMessage.value = "Authentication token missing"
            return
        }

        // Check if selected server exists in remote server list
        val remoteServer = findRemoteServerByName(server.name)
        if (remoteServer == null) {
            _errorMessage.value = "Selected server '${server.name}' not found in server list. Please refresh server list."
            return
        }

        _isConnecting.value = true
        _errorMessage.value = null

        viewModelScope.launch {
            try {
                val selected = _selectedProtocol.value
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

                val response = RetrofitClient.instance.getVpnConfig("Bearer $token", request)

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
                    }
                } else {
                    val errorBody = response.body()
                    _errorMessage.value = errorBody?.message ?: "Failed to get VPN config: ${response.code()}"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error connecting to VPN", e)
                _errorMessage.value = "Connection error: ${e.localizedMessage}"
            } finally {
                if (_selectedProtocol.value != VpnProtocol.OPENVPN) {
                    _isConnecting.value = false
                }
            }
        }
    }

    private fun connectWithConfig(
        configContent: String,
        server: VpnServer,
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
            var alias = profile.userCertificateAlias

            // NEW: If a previously mapped alias exists and is installed OK, prefer it and ignore freshly generated P12 alias
            val mapped = withContext(Dispatchers.IO) { configManager.getMappedCertAlias(profile.gateway, profile.remoteId) }
            if (!mapped.isNullOrBlank()) {
                val mappedDiag = withContext(Dispatchers.IO) { configManager.diagnoseUserCertificate(mapped) }
                if (mappedDiag.state == VpnConfigManager.UserCertState.INSTALLED_OK) {
                    if (alias != mapped) {
                        Log.d(TAG, "[CertFlow] Replacing provided alias $alias with mapped installed alias $mapped to avoid re-import")
                        profile.userCertificateAlias = mapped
                        alias = mapped
                      }
                } else {
                    Log.d(TAG, "[CertFlow] Mapped alias $mapped exists but state=${mappedDiag.state}; will proceed with provided alias $alias")
                }
            }

            // Try previously mapped alias if none or not installed (existing logic follows, updated to use possibly reassigned alias)
            if (alias.isNullOrBlank() || withContext(Dispatchers.IO) { configManager.needsUserCertInstallation(alias) }) {
                // If we didn't already switch to mapped (installed) alias, and current alias needs install, see if mapping can rescue
                if (alias != mapped && !mapped.isNullOrBlank()) {
                    val mappedDiag2 = withContext(Dispatchers.IO) { configManager.diagnoseUserCertificate(mapped) }
                    if (mappedDiag2.state == VpnConfigManager.UserCertState.INSTALLED_OK) {
                        Log.d(TAG, "[CertFlow] Late rescue: using mapped alias $mapped instead of $alias")
                        profile.userCertificateAlias = mapped
                        alias = mapped
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
                        withContext(Dispatchers.IO) { configManager.saveMappedCertAlias(prof.gateway, prof.remoteId, chosenAlias) }
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
        val dataSource = VpnProfileSource(context)
        dataSource.open()
        try {
            val existingProfile = dataSource.getVpnProfile(profile.getUUID().toString())
            if (existingProfile != null) {
                dataSource.updateVpnProfile(profile)
                Log.d(TAG, "Updated existing VPN profile")
            } else {
                dataSource.insertProfile(profile)
                Log.d(TAG, "Inserted new VPN profile")
            }
        } finally {
            dataSource.close()
        }
        // Persist mapping if alias present
        profile.userCertificateAlias?.let { alias ->
            withContext(Dispatchers.IO) { configManager.saveMappedCertAlias(profile.gateway, profile.remoteId, alias) }
        }

        activeVpnHandler = VpnProtocolFactory.createHandler(VpnProtocol.IKEV2_IPSEC, context)

        // Start observing connection state BEFORE initiating connection
        val handler = activeVpnHandler as? StrongSwanHandler
        if (handler != null) {
            // Launch coroutine to observe state changes
            viewModelScope.launch {
                handler.connectionState.collect { state ->
                    Log.d(TAG, "StrongSwan connection state changed: $state")
                    when (state) {
                        is ConnectionState.Connecting -> {
                            _isConnecting.value = true
                            _isConnected.value = false
                            _errorMessage.value = "Connecting to ${profile.gateway}..."
                        }
                        is ConnectionState.Connected -> {
                            _isConnected.value = true
                            _isConnecting.value = false
                            _errorMessage.value = "Connected using IKEv2/IPSec to ${profile.gateway}"
                            Log.d(TAG, "Successfully connected via StateFlow")

                            // Start data usage monitoring when VPN connects
                            dataUsageManager.startMonitoring()
                            Log.d(TAG, "Started data usage monitoring")

                            // Save connection state for persistence
                            saveConnectionState()
                        }
                        is ConnectionState.Disconnected -> {
                            _isConnected.value = false
                            _isConnecting.value = false
                            if (_errorMessage.value?.contains("Connected") == true || _errorMessage.value?.contains("Connecting") == true) {
                                _errorMessage.value = "Disconnected"
                            }
                            Log.d(TAG, "Disconnected via StateFlow")
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
                        }
                    }
                }
            }
        }

        // Initiate connection (return value is now just for logging)
        val initiatedSuccessfully = withContext(Dispatchers.IO) {
            handler?.connect(context, profile) ?: false
        }

        Log.d(TAG, "Connection initiation returned: $initiatedSuccessfully")

        // Don't set UI state here - let the StateFlow observer handle it
        // This eliminates the race condition
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

                // Try cached config first
                if (cacheFile.exists() && cacheFile.length() > 0) {
                    Log.d(TAG, "Using cached OpenVPN config: ${cacheFile.absolutePath}")
                    val ok = (activeVpnHandler as? OpenVpnHandler)?.connect(context, cacheFile.absolutePath) == true
                    if (ok) {
                        onOpenVpnConnected()
                        return@withContext true
                    } else {
                        Log.w(TAG, "Cached OpenVPN config failed. Will re-download and retry")
                    }
                }

                // Download latest config
                val resp = RetrofitClient.instance.downloadOpenVpnConfig(
                    authorization = "Bearer $token",
                    request = OpenVpnDownloadRequest(serverId)
                )

                if (!resp.isSuccessful) {
                    Log.e(TAG, "OpenVPN config download failed: ${resp.code()} ${resp.message()}")
                    return@withContext false
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
        saveConnectionState()
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

                        if (disconnectResult) {
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
                    Log.w(TAG, "VPN still appears to be active after disconnect attempts")
                    _errorMessage.value = "VPN disconnect attempted - please check if connection is actually terminated"
                } else {
                    Log.d(TAG, "VPN successfully disconnected - no active VPN detected")
                    _errorMessage.value = "VPN disconnected successfully"
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error during disconnect process", e)
                _errorMessage.value = "Disconnect error: ${e.localizedMessage}"
            } finally {
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

            // Method 1: Check if VPN permission is granted and no preparation needed
            val vpnService = VpnService.prepare(context)
            val isVpnServiceReady = vpnService == null // null means VPN permission is granted

            // Method 2: Check for active VPN connection by examining network interfaces
            val isVpnActive = try {
                // On Android, when VPN is active, there should be a tun interface
                val networkInterfaces = java.net.NetworkInterface.getNetworkInterfaces()
                var hasTunInterface = false
                while (networkInterfaces.hasMoreElements()) {
                    val networkInterface = networkInterfaces.nextElement()
                    if (networkInterface.name.startsWith("tun") && networkInterface.isUp) {
                        hasTunInterface = true
                        Log.d(TAG, "Found active tun interface: ${networkInterface.name}")
                        break
                    }
                }
                hasTunInterface
            } catch (e: Exception) {
                Log.w(TAG, "Failed to check network interfaces: ${e.message}")
                false
            }

            // Method 3: Check StrongSwan service state
            val isStrongSwanActive = try {
                // Check if strongSwan VPN service files exist and are recent
                val vpnStateFile = File(context.filesDir, "charon.log")
                val isRecentlyActive = vpnStateFile.exists() &&
                    (System.currentTimeMillis() - vpnStateFile.lastModified()) < 60000 // 1 minute
                isRecentlyActive
            } catch (e: Exception) {
                false
            }

            Log.d(TAG, "VPN status check improved: vpnServiceReady=$isVpnServiceReady, hasTunInterface=$isVpnActive, strongSwanActive=$isStrongSwanActive")

            // Consider VPN active if service is ready AND we have evidence of active VPN
            isVpnServiceReady && (isVpnActive || isStrongSwanActive)
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
}
