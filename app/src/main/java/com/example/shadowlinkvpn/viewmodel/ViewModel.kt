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
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.strongswan.android.data.VpnProfile
import org.strongswan.android.data.VpnProfileSource
import java.io.File
import java.io.InputStreamReader
import org.json.JSONObject
import android.security.KeyChain
import android.security.KeyChainAliasCallback

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

    private var authToken: String? = null
    private var activeVpnHandler: VpnProtocolHandler? = null
    private var pendingProfile: VpnProfile? = null
    private val configManager by lazy { VpnConfigManager(getApplication()) }

    fun loadLocalServers(context: Context) {
        try {
            val inputStream = context.resources.openRawResource(R.raw.servers)
            val reader = InputStreamReader(inputStream)
            val serverListType = object : TypeToken<List<VpnServer>>() {}.type
            val serverList: List<VpnServer> = Gson().fromJson(reader, serverListType) ?: emptyList()
            _servers.value = serverList
            Log.d(TAG, "Loaded ${serverList.size} local servers")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading local servers", e)
            _errorMessage.value = "Failed to load local servers: ${e.localizedMessage}"
        }
    }

    fun loadRemoteServers() {
        val token = authToken
        if (token == null) {
            _errorMessage.value = "Authentication token missing"
            return
        }

        _isLoadingServers.value = true
        _errorMessage.value = null

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
                        _errorMessage.value = "No servers received from API"
                    }
                } else {
                    _errorMessage.value = "Failed to load servers: ${response.code()} - ${response.message()}"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading remote servers", e)
                _errorMessage.value = "Failed to load remote servers: ${e.localizedMessage}"
            } finally {
                _isLoadingServers.value = false
            }
        }
    }

    fun setAuthToken(token: String) {
        authToken = token
        // Automatically load remote servers when token is set
        loadRemoteServers()
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
                // Request VPN config using remote server ID
                val request = VpnConfigRequest(
                    serverId = remoteServer.id,
                    protocol = _selectedProtocol.value.apiName
                )

                val response = RetrofitClient.instance.getVpnConfig("Bearer $token", request)

                if (response.isSuccessful && response.body()?.success == true) {
                    val configContent = response.body()?.configContent
                    val certificateName = response.body()?.certificateName
                    val passphrase = response.body()?.passphrase

                    if (configContent != null) {
                        Log.d(TAG, "Config received for ${_selectedProtocol.value.displayName}")
                        _errorMessage.value = "Config received: ${certificateName ?: "VPN config"}"
                        connectWithConfig(configContent, server, _selectedProtocol.value, certificateName, passphrase)
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
                _isConnecting.value = false
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
                if (mappedDiag.state == com.example.shadowlinkvpn.util.VpnConfigManager.UserCertState.INSTALLED_OK) {
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
                    if (mappedDiag2.state == com.example.shadowlinkvpn.util.VpnConfigManager.UserCertState.INSTALLED_OK) {
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
                com.example.shadowlinkvpn.util.VpnConfigManager.UserCertState.INSTALLED_OK -> Log.d(TAG, "[CertFlow] Pre-flight KeyChain OK: chain=${diag.chainSize} hasKey=${diag.hasPrivateKey}")
                com.example.shadowlinkvpn.util.VpnConfigManager.UserCertState.INSTALLED_NO_KEY -> { _errorMessage.value = "Installed certificate has no private key. Pick another certificate."; _showCertPicker.value = true; pendingProfile = profile; return }
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
                    if (diag.state == com.example.shadowlinkvpn.util.VpnConfigManager.UserCertState.INSTALLED_OK) {
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
                while (diag.state != com.example.shadowlinkvpn.util.VpnConfigManager.UserCertState.INSTALLED_OK && attempts < 10) {
                    attempts++
                    Log.d(TAG, "[CertFlow] Waiting for KeyChain to expose cert alias=$alias attempt=$attempts state=${diag.state}")
                    delay(500)
                    diag = withContext(Dispatchers.IO) { configManager.diagnoseUserCertificate(alias) }
                }
                if (diag.state != com.example.shadowlinkvpn.util.VpnConfigManager.UserCertState.INSTALLED_OK) {
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
    fun completeCertificateInstallation() { resumeAfterCertificateInstall() }

    /** User canceled certificate installation */
    fun cancelCertificateInstallation() {
        pendingProfile?.userCertificateAlias?.let { alias ->
            configManager.cleanupInstallIntentData(alias)
        }
        pendingProfile = null
        _pendingKeyChainImport.value = null
        _showCertPicker.value = false
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
        val connected = withContext(Dispatchers.IO) {
            (activeVpnHandler as? StrongSwanHandler)?.connect(context, profile) ?: false
        }

        if (connected) {
            _isConnected.value = true
            _errorMessage.value = "Connected using IKEv2/IPSec to ${profile.gateway}"
            Log.d(TAG, "Successfully initiated IKEv2/IPSec connection")
        } else {
            throw Exception("Failed to initiate IKEv2/IPSec connection")
        }
    }

    // Update the connectOpenVpn method if needed
    private suspend fun connectOpenVpn(config: String) {
        try {
            val context = getApplication<Application>().applicationContext
            val configFile = saveOpenVpnConfig(context, config)

            activeVpnHandler = VpnProtocolFactory.createHandler(VpnProtocol.OPENVPN, context)

//            val connected = withContext(Dispatchers.IO) {
//                (activeVpnHandler as OpenVpnHandler).connect(configFile.absolutePath, params)
//            }
//
//            if (connected) {
//                _isConnected.value = true
//                _errorMessage.value = "Connected using OpenVPN"
//                Log.d(TAG, "Successfully connected using OpenVPN")
//            } else {
//                throw Exception("Failed to connect using OpenVPN")
//            }
        } catch (e: Exception) {
            Log.e(TAG, "OpenVPN connection error", e)
            _isConnected.value = false
            _errorMessage.value = "OpenVPN connection failed: ${e.localizedMessage}"
            activeVpnHandler = null
        }
    }

    suspend fun connectWireGuard(config: String) {
        try {
            val context = getApplication<Application>().applicationContext
            val configFile = saveWireguardConfig(context, config)

            activeVpnHandler = VpnProtocolFactory.createHandler(VpnProtocol.WIREGUARD, context)

//            val connected = withContext(Dispatchers.IO) {
//                (activeVpnHandler as WireGuardHandler).connect(configFile.absolutePath, params)
//            }
//
//            if (connected) {
//                _isConnected.value = true
//                _errorMessage.value = "Connected using WireGuard"
//                Log.d(TAG, "Successfully connected using WireGuard")
//            } else {
//                throw Exception("Failed to connect using WireGuard")
//            }
        } catch (e: Exception) {
            Log.e(TAG, "WireGuard connection error", e)
            _isConnected.value = false
            _errorMessage.value = "WireGuard connection failed: ${e.localizedMessage}"
            activeVpnHandler = null
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            try {
                val context = getApplication<Application>().applicationContext
                activeVpnHandler?.let { handler ->
                    val result = withContext(Dispatchers.IO) {
                        handler.disconnect(context)
                    }

                    if (result) {
                        Log.d(TAG, "VPN disconnected successfully")
                    } else {
                        Log.w(TAG, "VPN disconnect returned false")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error disconnecting VPN", e)
            } finally {
                activeVpnHandler = null
                _isConnected.value = false
                _errorMessage.value = "Disconnected"
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

    // Check current VPN connection status
    suspend fun checkVpnStatus(): Boolean = withContext(Dispatchers.IO) {
        activeVpnHandler?.let { handler ->
            return@withContext when (handler.connectionState.value) {
                is ConnectionState.Connected -> true
                else -> false
            }
        } ?: false
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            disconnect()
        }
    }

    private suspend fun getConfigFilePath(context: Context, filename: String): String {
        return withContext(Dispatchers.IO) {
            val configDir = File(context.filesDir, "configs")
            if (!configDir.exists()) {
                configDir.mkdirs()
            }
            return@withContext File(configDir, filename).absolutePath
        }
    }

    private suspend fun saveStrongSwanConfig(context: Context, config: String): File {
        return withContext(Dispatchers.IO) {
            val configFile = File(context.filesDir, "configs/strongswan.sswan")
            if (!configFile.parentFile?.exists()!!) {
                configFile.parentFile?.mkdirs()
            }
            configFile.writeText(config)
            return@withContext configFile
        }
    }

    private suspend fun saveOpenVpnConfig(context: Context, config: String): String {
        return withContext(Dispatchers.IO) {
            val configFile = File(context.filesDir, "configs/openvpn.ovpn")
            if (!configFile.parentFile?.exists()!!) {
                configFile.parentFile?.mkdirs()
            }

            configFile.writeText(config)
            return@withContext configFile.absolutePath
        }
    }

    private suspend fun saveWireguardConfig(context: Context, config: String): String {
        return withContext(Dispatchers.IO) {
            val configFile = File(context.filesDir, "configs/wireguard.conf")
            if (!configFile.parentFile?.exists()!!) {
                configFile.parentFile?.mkdirs()
            }

            configFile.writeText(config)
            return@withContext configFile.absolutePath
        }
    }

    // Add this function to your VpnViewModel class
    fun testStrongSwanInitialization() {
        viewModelScope.launch {
            try {
                val context = getApplication<Application>().applicationContext
                val handler = VpnProtocolFactory.createHandler(VpnProtocol.IKEV2_IPSEC, context)
                val initialized = withContext(Dispatchers.IO) {
                    handler.initialize(context)
                }
                _errorMessage.value = if (initialized) {
                    "StrongSwan initialization successful"
                } else {
                    "StrongSwan initialization failed"
                }
            } catch (e: Exception) {
                _errorMessage.value = "StrongSwan test error: ${e.localizedMessage}"
            }
        }
    }

    private fun updateConnectionState(state: com.example.shadowlinkvpn.service.vpn.ConnectionState) {
        viewModelScope.launch {
            _connectionState.value = state
        }
    }


    fun requestVpnPermission(context: Context, onResult: (Intent?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val vpnIntent = VpnService.prepare(context)
            withContext(Dispatchers.Main) {
                onResult(vpnIntent)
            }
        }
    }

    private fun checkAndRequestVpnPermission(context: Context, onPermissionGranted: () -> Unit) {
        requestVpnPermission(context) { vpnIntent ->
            if (vpnIntent != null) {
                _errorMessage.value = "VPN permission required. Please grant permission in the dialog that appears."
                // The intent should be started by the UI layer
            } else {
                onPermissionGranted()
            }
        }
    }

    fun getConnectionLogs() {
        viewModelScope.launch {
            try {
                val context = getApplication<Application>().applicationContext
                val logs = activeVpnHandler?.getConnectionLogs(context)
                _errorMessage.value = logs?.let { "Logs: ${it.take(200)}..." } ?: "No logs available"
            } catch (e: Exception) {
                _errorMessage.value = "Failed to get logs: ${e.localizedMessage}"
            }
        }
    }

    // Certificate management functions
    fun loadAvailableCertificates() {
        viewModelScope.launch {
            try {
                val context = getApplication<Application>().applicationContext
                val certificates = withContext(Dispatchers.IO) {
                    // Get all available certificates from KeyChain
                    getAllUserCertificates()
                }
                _availableCertificates.value = certificates
                Log.d(TAG, "[CertFlow] Loaded ${certificates.size} available certificates")
            } catch (e: Exception) {
                Log.e(TAG, "[CertFlow] Failed to load available certificates", e)
                _availableCertificates.value = emptyList()
            }
        }
    }

    private suspend fun getAllUserCertificates(): List<String> {
        return withContext(Dispatchers.IO) {
            try {
                val context = getApplication<Application>().applicationContext
                // Use reflection to get all aliases from KeyChain
                val keyChainClass = Class.forName("android.security.KeyChain")
                val method = keyChainClass.getDeclaredMethod("getAliases", android.content.Context::class.java)
                method.isAccessible = true

                @Suppress("UNCHECKED_CAST")
                val aliases = method.invoke(null, context) as? Array<String>

                // Filter out system certificates and return only user certificates
                aliases?.filter { alias ->
                    try {
                        val chain = KeyChain.getCertificateChain(context, alias)
                        val key = KeyChain.getPrivateKey(context, alias)
                        chain != null && chain.isNotEmpty() && key != null
                    } catch (e: Exception) {
                        false
                    }
                }?.toList() ?: emptyList()
            } catch (e: Exception) {
                Log.w(TAG, "[CertFlow] Failed to get certificate aliases via reflection, trying alternative method", e)
                // Fallback: return empty list - user will need to import
                emptyList()
            }
        }
    }

    fun showCertificateSelectionDialog() {
        _showCertPicker.value = false
        _showCertSelectionDialog.value = true
    }

    fun dismissCertSelectionDialog() {
        _showCertSelectionDialog.value = false
    }

    fun showImportCertificateDialog() {
        _showCertSelectionDialog.value = false
        _showImportCertDialog.value = true
    }

    fun dismissImportCertDialog() {
        _showImportCertDialog.value = false
        _showCertSelectionDialog.value = true // Go back to selection dialog
    }

    fun selectCertificate(alias: String) {
        val prof = pendingProfile ?: run {
            _errorMessage.value = "No pending profile for certificate selection"
            return
        }

        viewModelScope.launch {
            Log.d(TAG, "[CertFlow] User selected certificate alias: $alias")
            _showCertSelectionDialog.value = false

            // Validate the selected certificate
            val diag = withContext(Dispatchers.IO) {
                configManager.diagnoseUserCertificate(alias)
            }

            if (diag.state == com.example.shadowlinkvpn.util.VpnConfigManager.UserCertState.INSTALLED_OK) {
                prof.userCertificateAlias = alias

                // Save the mapping for future use
                withContext(Dispatchers.IO) {
                    configManager.saveMappedCertAlias(prof.gateway, prof.remoteId, alias)
                }

                _errorMessage.value = "Certificate selected: $alias"

                // Continue with the connection
                completeStrongSwanConnection(prof)
                pendingProfile = null
            } else {
                _errorMessage.value = "Selected certificate not usable (state=${diag.state}). Try another."
                _showCertSelectionDialog.value = true
            }
        }
    }

    fun importCertificateWithAlias(userAlias: String) {
        val prof = pendingProfile ?: run {
            _errorMessage.value = "No pending profile for certificate import"
            return
        }

        viewModelScope.launch {
            _showImportCertDialog.value = false

            // Check if we have a P12 certificate from the profile that we can import
            val p12Alias = prof.userCertificateAlias
            if (p12Alias != null) {
                Log.d(TAG, "[CertFlow] Attempting to import P12 certificate with user alias: $userAlias")

                // Get the install intent for the P12 certificate
                val installIntent = withContext(Dispatchers.IO) {
                    configManager.getInstallIntentIfPending(p12Alias)
                }

                if (installIntent != null) {
                    // Update the profile to use the user-provided alias
                    prof.userCertificateAlias = userAlias
                    pendingProfile = prof

                    _pendingKeyChainImport.value = installIntent
                    _errorMessage.value = "Installing certificate: $userAlias. Please approve the KeyChain dialog."
                } else {
                    _errorMessage.value = "No certificate data available for import"
                    _showCertSelectionDialog.value = true
                }
            } else {
                _errorMessage.value = "No certificate data found in profile"
                _showCertSelectionDialog.value = true
            }
        }
    }
}
