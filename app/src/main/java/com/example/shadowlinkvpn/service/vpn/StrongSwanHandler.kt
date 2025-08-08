// app/src/main/java/com/example/shadowlinkvpn/service/vpn/StrongSwanHandler.kt
package com.example.shadowlinkvpn.service.vpn

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.shadowlinkvpn.service.StrongSwanVpnService
import kotlinx.coroutines.delay
import org.strongswan.android.logic.CharonVpnService
import java.io.File
import android.net.VpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.strongswan.android.logic.VpnStateService
import org.json.JSONObject

class StrongSwanHandler : VpnProtocolHandler() {
    private val TAG = "StrongSwanHandler"

    override suspend fun initialize(context: Context): Boolean {
        return try {
            // Initialize StrongSwan components if needed
            Log.d(TAG, "StrongSwan handler initialized")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize StrongSwan handler", e)
            false
        }
    }

    override suspend fun connect(context: Context, configPath: String): Boolean {
        return try {
            updateConnectionState(ConnectionState.Connecting)
            Log.d(TAG, "Starting StrongSwan connection with config: $configPath")

            enableStrongSwanLogging(context)

            val configFile = File(configPath)
            if (!configFile.exists()) {
                Log.e(TAG, "Config file does not exist: $configPath")
                updateConnectionState(ConnectionState.Error("Config file not found"))
                return false
            }

            // Parse the StrongSwan-specific config format
            val configContent = configFile.readText()
            val swanConfig = parseStrongSwanConfig(configContent)
            if (swanConfig == null) {
                Log.e(TAG, "Failed to parse StrongSwan config")
                updateConnectionState(ConnectionState.Error("Invalid StrongSwan config format"))
                return false
            }

            Log.d(TAG, "Parsed config - Server: ${swanConfig.serverAddress}, Type: ${swanConfig.type}")

            // Check VPN permission
            val vpnIntent = VpnService.prepare(context)
            if (vpnIntent != null) {
                Log.e(TAG, "VPN permission not granted")
                updateConnectionState(ConnectionState.Error("VPN permission required"))
                return false
            }

            // Import client certificate
            val certImported = importClientCertificate(context, swanConfig.clientP12, swanConfig.clientPassword)
            if (!certImported) {
                Log.e(TAG, "Failed to import client certificate")
                updateConnectionState(ConnectionState.Error("Certificate import failed"))
                return false
            }

            // Start StrongSwan connection with proper profile
            val connected = startStrongSwanConnection(context, swanConfig)
            if (!connected) {
                updateConnectionState(ConnectionState.Error("Failed to start StrongSwan service"))
                return false
            }

            // Monitor connection with better detection
            return monitorConnection(context, swanConfig.serverAddress)

        } catch (e: Exception) {
            Log.e(TAG, "StrongSwan connection failed", e)
            updateConnectionState(ConnectionState.Error(e.localizedMessage ?: "Connection failed"))
            return false
        }
    }

    override suspend fun disconnect(context: Context): Boolean {
        return try {
            updateConnectionState(ConnectionState.Disconnecting)

            // Send disconnect intent to StrongSwan service
            val intent = Intent(context, CharonVpnService::class.java).apply {
                action = CharonVpnService.DISCONNECT_ACTION
            }
            context.startService(intent)

            // Wait a bit for disconnection
            delay(1000)

            updateConnectionState(ConnectionState.Disconnected)
            Log.d(TAG, "StrongSwan disconnected")
            true
        } catch (e: Exception) {
            Log.e(TAG, "StrongSwan disconnect failed", e)
            updateConnectionState(ConnectionState.Error(e.localizedMessage ?: "Disconnect failed"))
            false
        }
    }

    private fun parseStrongSwanConfig(configContent: String): StrongSwanConnectionConfig? {
        return try {
            val json = JSONObject(configContent)

            val remote = json.getJSONObject("remote")
            val local = json.getJSONObject("local")

            // Handle both hostname and IP address from the config
            val serverAddress = json.optString("server", remote.getString("addr"))
            val serverIp = json.optString("server_ip") // IP fallback if provided

            StrongSwanConnectionConfig(
                uuid = json.getString("uuid"),
                name = json.getString("name"),
                type = json.getString("type"),
                serverAddress = serverAddress, // This can now be hostname or IP
                serverIp = serverIp, // Optional IP fallback
                serverId = remote.getString("id"),
                serverCert = remote.getString("cert"),
                ike = remote.optString("ike", "aes256-sha256-modp2048"),
                esp = remote.optString("esp", "aes256-sha256"),
                clientP12 = local.getString("p12"),
                clientPassword = local.getString("password"),
                dnsServers = json.optJSONArray("dns-servers")?.let { dns ->
                    (0 until dns.length()).map { dns.getString(it) }
                } ?: listOf("1.1.1.1", "8.8.8.8")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse StrongSwan config", e)
            null
        }
    }

    data class StrongSwanConnectionConfig(
        val uuid: String,
        val name: String,
        val type: String,
        val serverAddress: String, // Can be hostname or IP
        val serverIp: String?, // Optional IP fallback
        val serverId: String,
        val serverCert: String,
        val ike: String,
        val esp: String,
        val clientP12: String,
        val clientPassword: String,
        val dnsServers: List<String>
    )

//    private fun parseConfigToProfile(configFile: File): org.strongswan.android.data.VpnProfile? {
//        return try {
//            val configContent = configFile.readText()
//            val json = org.json.JSONObject(configContent)
//
//            val profile = org.strongswan.android.data.VpnProfile().apply {
//                name = "ShadowLink VPN"
//                gatewayAddress = json.getString("server")
//                vpnType = org.strongswan.android.data.VpnType.IKEV2_EAP
//                username = json.optString("username", "")
//                password = json.optString("password", "")
//                certificate = json.optString("certificate", "")
//                userCertificate = json.optString("user_certificate", "")
//                remoteId = json.optString("remote_id", json.getString("server"))
//                localId = json.optString("local_id", "")
//                mtu = json.optInt("mtu", 1400)
//                natTraversal = true
//                splitTunneling = 0
//                flags = 0
//            }
//
//            Log.d(TAG, "Created VPN profile for server: ${profile.gatewayAddress}")
//            profile
//        } catch (e: Exception) {
//            Log.e(TAG, "Failed to parse config file", e)
//            null
//        }
//    }

    private suspend fun checkVpnConnection(context: Context): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Method 1: Check network interfaces for VPN tunnel
                val networkInterfaces = java.net.NetworkInterface.getNetworkInterfaces()
                for (networkInterface in networkInterfaces) {
                    if (networkInterface.name.contains("tun") ||
                        networkInterface.name.contains("ppp") ||
                        networkInterface.name.contains("ipsec")) {
                        if (networkInterface.isUp && !networkInterface.isLoopback) {
                            Log.d(TAG, "VPN interface found: ${networkInterface.name}")
                            return@withContext true
                        }
                    }
                }

                // Method 2: Check if our IP changed by making a quick IP check
                val currentIp = getCurrentPublicIp()
                if (currentIp != null && isVpnIpAddress(currentIp)) {
                    Log.d(TAG, "VPN connection confirmed via IP check: $currentIp")
                    return@withContext true
                }

                false
            } catch (e: Exception) {
                Log.e(TAG, "Error checking VPN connection", e)
                false
            }
        }
    }

    private suspend fun checkVpnInterface(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val process = ProcessBuilder("ip", "link", "show").start()
                val output = process.inputStream.bufferedReader().readText()

                // Look for common VPN interface names
                val vpnInterfaces = listOf("tun", "ppp", "ipsec", "strongswan")
                vpnInterfaces.any { interfaceName ->
                    output.contains(interfaceName, ignoreCase = true)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not check VPN interfaces", e)
                false
            }
        }
    }

    private suspend fun getCurrentPublicIp(): String? {
        return try {
            withContext(Dispatchers.IO) {
                val url = java.net.URL("https://api.ipify.org")
                val connection = url.openConnection()
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.getInputStream().bufferedReader().readText().trim()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not get public IP", e)
            null
        }
    }

    private fun isVpnIpAddress(ip: String): Boolean {
        // This is a simplified check - you should implement proper logic
        // to verify if the IP belongs to your VPN server's range
        return !ip.startsWith("192.168.") &&
                !ip.startsWith("10.") &&
                !ip.startsWith("172.") &&
                !ip.startsWith("217.")
    }

    private fun checkForConnectionErrors(): Boolean {
        // Simple error detection - could be enhanced to check actual logs
        return false
    }

    private fun enableStrongSwanLogging(context: Context) {
        try {
            // Enable StrongSwan logging
            val logFile = File(context.filesDir, "charon.log")
            Log.d(TAG, "StrongSwan log file: ${logFile.absolutePath}")

            // You can check this file for detailed connection logs
            // adb pull /data/data/com.example.shadowlinkvpn/files/charon.log
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup logging", e)
        }
    }

    private fun importClientCertificate(context: Context, p12Base64: String, password: String): Boolean {
        return try {
            // Decode P12 certificate
            val p12Bytes = android.util.Base64.decode(p12Base64, android.util.Base64.DEFAULT)

            // Save to internal storage for StrongSwan to access
            val certFile = File(context.filesDir, "client.p12")
            certFile.writeBytes(p12Bytes)

            Log.d(TAG, "Client certificate imported successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import certificate", e)
            false
        }
    }

    private fun startStrongSwanConnection(context: Context, config: StrongSwanConnectionConfig): Boolean {
        return try {
            // Create proper StrongSwan connection intent
            val intent = Intent(context, CharonVpnService::class.java).apply {
                // Use the actual StrongSwan connection action
                action = "org.strongswan.android.action.START_VPN"
                putExtra("VPN_PROFILE_UUID", config.uuid)
                putExtra("VPN_PROFILE_NAME", config.name)
                putExtra("VPN_GATEWAY", config.serverAddress)
                putExtra("VPN_TYPE", "ikev2-cert")
                putExtra("VPN_USERNAME", "") // Certificate-based auth
            }

            context.startService(intent)
            Log.d(TAG, "Started StrongSwan service for profile: ${config.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start StrongSwan service", e)
            false
        }
    }

    private suspend fun monitorConnection(context: Context, serverAddress: String): Boolean {
        var attempts = 0
        val maxAttempts = 30
        val initialPublicIp = getCurrentPublicIp()

        while (attempts < maxAttempts) {
            delay(1000)
            attempts++

            // Check multiple indicators
            val hasVpnInterface = checkVpnInterface()
            val currentPublicIp = getCurrentPublicIp()
            val ipChanged = currentPublicIp != null &&
                    initialPublicIp != null &&
                    currentPublicIp != initialPublicIp

            Log.d(TAG, "Monitor attempt $attempts: VPN interface=$hasVpnInterface, IP changed=$ipChanged")
            Log.d(TAG, "IP: $initialPublicIp -> $currentPublicIp")

            if (hasVpnInterface || ipChanged) {
                updateConnectionState(ConnectionState.Connected)
                Log.d(TAG, "StrongSwan connected successfully")
                return true
            }

            // Check for errors in StrongSwan logs
            if (attempts > 10) {
                // I commented this out for now, to remove error
//                val errorDetected = checkStrongSwanLogs(context)
                val errorDetected = true
                if (errorDetected) {
                    updateConnectionState(ConnectionState.Error("Authentication or configuration error"))
                    return false
                }
            }
        }

        updateConnectionState(ConnectionState.Error("Connection timeout - check server and certificate"))
        return false
    }

}