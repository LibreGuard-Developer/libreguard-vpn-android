package com.example.shadowlinkvpn.viewmodel

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.strongswan.android.data.VpnProfile
import org.strongswan.android.data.VpnProfileSource
import java.io.File
import java.io.InputStreamReader
import org.json.JSONObject

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

    private var authToken: String? = null
    private var activeVpnHandler: VpnProtocolHandler? = null
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

    // Update the connectStrongSwan method in VpnViewModel
    private suspend fun connectStrongSwan(configContent: String, certificateName: String?, passphrase: String?) {
        try {
            val context = getApplication<Application>().applicationContext

            Log.d(TAG, "Starting strongSwan connection with config content")

            // Parse the server response directly to create a VpnProfile
            val profile = configManager.parseServerResponseToProfile(configContent)
            if (profile == null) {
                throw Exception("Failed to parse server response into VpnProfile")
            }

            Log.d(TAG, "Created VpnProfile: ${profile.name}")
            Log.d(TAG, "Gateway: ${profile.gateway}")
            Log.d(TAG, "Remote ID: ${profile.remoteId}")
            Log.d(TAG, "VPN Type: ${profile.vpnType}")

            // Store the profile in the data source for strongSwan to access
            val dataSource = VpnProfileSource(context)
            dataSource.open()

            try {
                // Check if profile already exists and update, otherwise insert
                val existingProfile = dataSource.getVpnProfile(profile.uuid.toString())
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

            // Create the handler and connect
            activeVpnHandler = VpnProtocolFactory.createHandler(VpnProtocol.IKEV2_IPSEC, context)

            // Save the JSON server response to a file for the handler to parse
            val configFile = File(context.filesDir, "server_response_${System.currentTimeMillis()}.json")
            configFile.writeText(configContent)

            // Connect using the strongSwan handler with the JSON response file
            val connected = withContext(Dispatchers.IO) {
                activeVpnHandler?.connect(context, configFile.absolutePath) ?: false
            }

            if (connected) {
                _isConnected.value = true
                _errorMessage.value = "Connected using IKEv2/IPSec to ${profile.gateway}"
                Log.d(TAG, "Successfully initiated IKEv2/IPSec connection")
            } else {
                throw Exception("Failed to initiate IKEv2/IPSec connection")
            }

        } catch (e: Exception) {
            Log.e(TAG, "IKEv2/IPSec connection error", e)
            _isConnected.value = false
            _errorMessage.value = "IKEv2/IPSec connection failed: ${e.localizedMessage}"
            activeVpnHandler = null
        }
    }

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

    private suspend fun saveStrongSwanConfigFile(context: Context, config: String): File {
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

}