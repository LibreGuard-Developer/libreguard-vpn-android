package com.example.shadowlinkvpn.service.vpn

// app/src/main/java/com/example/shadowlinkvpn/service/vpn/StrongSwanHandler.kt

import android.content.Context
import android.util.Log
import com.example.shadowlinkvpn.service.StrongSwanBridge
import com.example.shadowlinkvpn.util.VpnConfigManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File

class StrongSwanHandler : VpnProtocolHandler {
    private val TAG = "StrongSwanHandler"
    private val bridge = StrongSwanBridge()

    override val connectionState = MutableStateFlow(VpnConnectionState(VpnConnectionStatus.DISCONNECTED))
    private var connectionName: String? = null

    override suspend fun initialize(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            connectionState.value = VpnConnectionState(VpnConnectionStatus.DISCONNECTED)
            return@withContext bridge.initializeCharon()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize StrongSwan", e)
            connectionState.value = VpnConnectionState(
                VpnConnectionStatus.ERROR,
                "Failed to initialize: ${e.localizedMessage}"
            )
            return@withContext false
        }
    }

    override suspend fun connect(configPath: String, params: Map<String, Any>?): Boolean =
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting StrongSwan connection with config: $configPath")
                connectionState.value = VpnConnectionState(VpnConnectionStatus.CONNECTING)

                // Load the VPN configuration
                val configFile = File(configPath)
                if (!configFile.exists()) {
                    throw Exception("Config file not found")
                }

                // Extract connection name from params or generate one
                connectionName = params?.get("connection_name") as? String ?: "shadowlink_vpn"

                // Load the configuration into StrongSwan
                if (!bridge.loadConfig(configPath)) {
                    throw Exception("Failed to load configuration")
                }

                // Start the connection
                if (!bridge.startConnection(connectionName!!)) {
                    throw Exception("Failed to start connection")
                }

                connectionState.value = VpnConnectionState(VpnConnectionStatus.CONNECTED)
                return@withContext true

            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect with StrongSwan", e)
                connectionState.value = VpnConnectionState(
                    VpnConnectionStatus.ERROR,
                    "Connection failed: ${e.localizedMessage}"
                )
                return@withContext false
            }
        }

    // In StrongSwanHandler.kt
    fun testInitializationOnly(context: Context): Boolean {
        return try {
            Log.d(TAG, "Testing StrongSwan library initialization only")
            val bridge = com.example.shadowlinkvpn.service.StrongSwanBridge()
            val result = bridge.initializeCharon()
            Log.d(TAG, "StrongSwan initialization result: $result")

            if (result) {
                // Clean up immediately since we're just testing
                bridge.cleanup()
            }

            return result
        } catch (e: Exception) {
            Log.e(TAG, "StrongSwan initialization test failed", e)
            false
        }
    }

    override suspend fun disconnect(): Boolean = withContext(Dispatchers.IO) {
        try {
            connectionName?.let { bridge.stopConnection(it) }
            connectionState.value = VpnConnectionState(VpnConnectionStatus.DISCONNECTED)
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disconnect StrongSwan", e)
            return@withContext false
        }
    }

    override fun cleanup() {
        try {
            bridge.cleanup()
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
}