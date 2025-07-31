package com.example.shadowlinkvpn.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shadowlinkvpn.R
import com.example.shadowlinkvpn.network.RetrofitClient
import com.example.shadowlinkvpn.network.VpnConfigRequest
import com.example.shadowlinkvpn.network.RemoteVpnServer
import com.example.shadowlinkvpn.ui.screens.VpnServer
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.InputStreamReader
// in `app/src/main/java/com/example/shadowlinkvpn/viewmodel/ViewModel.kt`
import java.io.File
import android.content.Intent
import com.example.shadowlinkvpn.service.StrongSwanVpnService
import com.example.shadowlinkvpn.service.vpn.StrongSwanHandler
import com.example.shadowlinkvpn.util.VpnConfigManager

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

    fun loadLocalServers(context: Context) {
        try {
            val inputStream = context.resources.openRawResource(R.raw.servers)
            val reader = InputStreamReader(inputStream)
            val serverListType = object : TypeToken<List<VpnServer>>() {}.type
            val serverList: List<VpnServer> = Gson().fromJson(reader, serverListType) ?: emptyList()
            _servers.value = serverList
        } catch (e: Exception) {
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
                        _errorMessage.value = "Server list updated (${serverResponse.servers.size} servers)"
                    } else {
                        _errorMessage.value = "No servers received from API"
                    }
                } else {
                    _errorMessage.value = "Failed to load servers: ${response.code()} - ${response.message()}"
                }
            } catch (e: Exception) {
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

        // Step 4: Check if selected server exists in remote server list
        val remoteServer = findRemoteServerByName(server.name)
        if (remoteServer == null) {
            _errorMessage.value = "Selected server '${server.name}' not found in CA server list. Please refresh server list."
            return
        }

        _isConnecting.value = true
        _errorMessage.value = null

        viewModelScope.launch {
            try {
                // Step 5: Request VPN config using remote server ID
                val request = VpnConfigRequest(
                    serverId = remoteServer.id,
                    protocol = _selectedProtocol.value.apiName
                )

                val response = RetrofitClient.instance.getVpnConfig("Bearer $token", request)

                if (response.isSuccessful && response.body()?.success == true) {
                    val configContent = response.body()?.configContent // Changed from configData
                    val certificateName = response.body()?.certificateName
                    val passphrase = response.body()?.passphrase

                    if (configContent != null) {
                        _errorMessage.value = "Config received: ${certificateName ?: "IKEV2 config"}"
                        connectWithConfig(configContent, server, _selectedProtocol.value, certificateName, passphrase)
                    } else {
                        _errorMessage.value = "No config content received"
                    }
                } else {
                    val errorBody = response.body()
                    _errorMessage.value = errorBody?.message ?: "Failed to get VPN config: ${response.code()}"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Connection error: ${e.localizedMessage}"
            } finally {
                _isConnecting.value = false
            }
        }
    }

    private fun connectWithConfig(configContent: String, server: VpnServer, protocol: VpnProtocol, certificateName: String?, passphrase: String?) {
        when (protocol) {
            VpnProtocol.IKEV2_IPSEC -> {
                connectStrongSwan(configContent, certificateName, passphrase)
            }
            VpnProtocol.OPENVPN -> {
                connectOpenVpn(configContent, certificateName)
            }
            VpnProtocol.WIREGUARD -> {
                connectWireGuard(configContent, certificateName)
            }
        }
    }

    private fun connectStrongSwan(configContent: String, certificateName: String?, passphrase: String?) {
        viewModelScope.launch {
            try {
                // Create the VPN config manager
                val context = getApplication<Application>().applicationContext
                val configManager = VpnConfigManager(context)

                // Save the .sswan file properly formatted
                val configFile = configManager.saveStrongSwanConfig(
                    configContent,
                    certificateName ?: "config.sswan"
                )

                // Start the VPN service - pass the File object directly, not its path
                startVpnService(context, configFile)  // Fixed - pass File object instead of String path

                _isConnected.value = true
                _errorMessage.value = "Connected using IKEv2/IPSec"
            } catch (e: Exception) {
                _isConnected.value = false
                _errorMessage.value = "Failed to connect: ${e.localizedMessage}"
            }
        }
    }

    private fun saveSswanFile(context: Context, configContent: String, certificateName: String?): File {
        val fileName = certificateName ?: "config.sswan"
        val file = File(context.filesDir, fileName)
        file.writeText(configContent)
        return file
    }

    private fun startVpnService(context: Context, sswanFile: File) {
        val intent = Intent(context, StrongSwanVpnService::class.java).apply {
            putExtra("configFile", sswanFile.absolutePath)
        }
        context.startService(intent)
    }

    private fun connectOpenVpn(configData: String, filename: String?) {
        // TODO: Implement OpenVPN connection
        _isConnected.value = true
        _errorMessage.value = "OpenVPN config received: ${filename ?: "config.ovpn"}"
    }

    private fun connectWireGuard(configData: String, filename: String?) {
        // TODO: Implement WireGuard connection
        _isConnected.value = true
        _errorMessage.value = "WireGuard config received: ${filename ?: "config.conf"}"
    }

    fun disconnect() {
        _isConnected.value = false
        _selectedServer.value = null
        _errorMessage.value = "Disconnected"
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun refreshServers() {
        loadRemoteServers()
    }

    // TESTING ONLY
    fun testStrongSwanInitialization() {
        _isLoadingServers.value = true
        _errorMessage.value = null

        viewModelScope.launch {
            try {
                val strongSwanHandler = StrongSwanHandler()
                val result = strongSwanHandler.testInitializationOnly(getApplication())

                if (result) {
                    _errorMessage.value = "StrongSwan initialization successful"
                } else {
                    _errorMessage.value = "StrongSwan initialization failed"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Error testing StrongSwan: ${e.localizedMessage}"
            } finally {
                _isLoadingServers.value = false
            }
        }
    }

}