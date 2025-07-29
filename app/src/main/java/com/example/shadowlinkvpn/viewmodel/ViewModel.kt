package com.example.shadowlinkvpn.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shadowlinkvpn.R
import com.example.shadowlinkvpn.network.RetrofitClient
import com.example.shadowlinkvpn.network.VpnConfigRequest
import com.example.shadowlinkvpn.ui.screens.VpnServer
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.InputStreamReader

enum class VpnProtocol(val displayName: String, val apiName: String) {
    IKEV2_IPSEC("IKEV2/IPSec", "IKEV2_IPSEC"),
    OPENVPN("OpenVPN", "OPENVPN"),
    WIREGUARD("WireGuard", "WIREGUARD")
}

class VpnViewModel : ViewModel() {
    private val _servers = MutableStateFlow<List<VpnServer>>(emptyList())
    val servers: StateFlow<List<VpnServer>> = _servers

    private val _selectedServer = MutableStateFlow<VpnServer?>(null)
    val selectedServer: StateFlow<VpnServer?> = _selectedServer

    private val _selectedProtocol = MutableStateFlow(VpnProtocol.IKEV2_IPSEC)
    val selectedProtocol: StateFlow<VpnProtocol> = _selectedProtocol

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private var authToken: String? = null

    fun loadServers(context: Context) {
        try {
            val inputStream = context.resources.openRawResource(R.raw.servers)
            val reader = InputStreamReader(inputStream)
            val serverListType = object : TypeToken<List<VpnServer>>() {}.type
            val serverList: List<VpnServer> = Gson().fromJson(reader, serverListType) ?: emptyList()
            _servers.value = serverList
        } catch (e: Exception) {
            _errorMessage.value = "Failed to load servers: ${e.localizedMessage}"
        }
    }

    fun setAuthToken(token: String) {
        authToken = token
    }

    fun selectServer(server: VpnServer) {
        _selectedServer.value = server
    }

    fun selectProtocol(protocol: VpnProtocol) {
        _selectedProtocol.value = protocol
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

        _isConnecting.value = true
        _errorMessage.value = null

        viewModelScope.launch {
            try {
                val request = VpnConfigRequest(
                    serverId = server.name, // Using server name as ID
                    protocol = _selectedProtocol.value.apiName,
                    token = token
                )

                val response = RetrofitClient.instance.getVpnConfig(request)

                if (response.isSuccessful && response.body()?.success == true) {
                    val configData = response.body()?.configData
                    if (configData != null) {
                        // TODO: Process the config file and connect
                        connectWithConfig(configData, server, _selectedProtocol.value)
                    } else {
                        _errorMessage.value = "No config data received"
                    }
                } else {
                    _errorMessage.value = response.body()?.message ?: "Failed to get VPN config"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Connection error: ${e.localizedMessage}"
            } finally {
                _isConnecting.value = false
            }
        }
    }

    private fun connectWithConfig(configData: String, server: VpnServer, protocol: VpnProtocol) {
        // For now, just simulate connection
        // TODO: Implement actual VPN connection logic based on protocol
        when (protocol) {
            VpnProtocol.IKEV2_IPSEC -> {
                // Handle .sswan file for StrongSwan
                connectStrongSwan(configData)
            }
            VpnProtocol.OPENVPN -> {
                // Handle .ovpn file for OpenVPN
                connectOpenVpn(configData)
            }
            VpnProtocol.WIREGUARD -> {
                // Handle WireGuard config
                connectWireGuard(configData)
            }
        }
    }

    private fun connectStrongSwan(configData: String) {
        // TODO: Implement StrongSwan connection
        _isConnected.value = true
        _errorMessage.value = "Connected using IKEv2/IPSec"
    }

    private fun connectOpenVpn(configData: String) {
        // TODO: Implement OpenVPN connection
        _isConnected.value = true
        _errorMessage.value = "Connected using OpenVPN"
    }

    private fun connectWireGuard(configData: String) {
        // TODO: Implement WireGuard connection
        _isConnected.value = true
        _errorMessage.value = "Connected using WireGuard"
    }

    fun disconnect() {
        _isConnected.value = false
        _selectedServer.value = null
        _errorMessage.value = "Disconnected"
    }

    fun clearError() {
        _errorMessage.value = null
    }
}