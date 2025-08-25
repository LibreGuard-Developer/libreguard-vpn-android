package com.example.shadowlinkvpn.service.vpn

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import com.example.shadowlinkvpn.util.VpnConfigManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.strongswan.android.data.LogContentProvider
import org.strongswan.android.data.VpnProfile
import org.strongswan.android.data.VpnProfileDataSource
import org.strongswan.android.data.VpnProfileSource
import org.strongswan.android.data.VpnType
import org.strongswan.android.logic.CharonVpnService
import java.io.File
import java.io.FileInputStream

class StrongSwanHandler(
    private val appContext: Context
) : VpnProtocolHandler {

    private val tag = "StrongSwanHandler"
    private val configManager = VpnConfigManager(appContext)

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _state

    @Volatile
    private var currentProfile: VpnProfile? = null

    override suspend fun initialize(context: Context): Boolean {
        Log.d(tag, "Initializing StrongSwan handler")
        return true
    }

    suspend fun connect(context: Context, profile: VpnProfile): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (_state.value is ConnectionState.Connecting || _state.value is ConnectionState.Connected) {
                    Log.w(tag, "Already connecting or connected")
                    return@withContext true
                }

                _state.value = ConnectionState.Connecting
                Log.d(tag, "Starting IKEv2 connection with VpnProfile object")

                currentProfile = profile
                Log.d(tag, "Using VpnProfile: ${profile.name}, Gateway: ${profile.gateway}, Type: ${profile.vpnType}")

                // Start CharonVpnService with the profile
                val intent = Intent(appContext, CharonVpnService::class.java).apply {
                    val bundle = Bundle().apply {
                        putString(VpnProfileDataSource.KEY_UUID, profile.getUUID().toString())

                        // For ikev2-eap-tls, we don't pass a password since it uses certificates
                        if (profile.vpnType == VpnType.IKEV2_EAP_TLS || profile.vpnType == VpnType.IKEV2_CERT) {
                            Log.d(tag, "Using certificate-based authentication (${profile.vpnType})")
                        } else {
                            putString(VpnProfileDataSource.KEY_PASSWORD, profile.password)
                            Log.d(tag, "Using password-based / EAP authentication")
                        }

                        // If we have a P12 certificate alias, add it
                        profile.userCertificateAlias?.let { alias ->
                            putString(VpnProfileDataSource.KEY_USER_CERTIFICATE, alias)
                            Log.d(tag, "Added certificate alias: $alias")
                        }
                    }
                    putExtras(bundle)

                    Log.d(tag, "Starting CharonVpnService with profile UUID: ${profile.getUUID()}")
                    Log.d(tag, "VPN Type: ${profile.vpnType}, Gateway: ${profile.gateway}")
                    Log.d(tag, "Certificate alias: ${profile.userCertificateAlias}")
                }

                // The VpnProfile needs to be in the database for CharonVpnService to find it
                val dataSource = VpnProfileSource(appContext)
                dataSource.open()
                if (dataSource.getVpnProfile(profile.getUUID().toString()) == null) {
                    dataSource.insertProfile(profile)
                } else {
                    dataSource.updateVpnProfile(profile)
                }
                dataSource.close()


                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }

                Log.d(tag, "CharonVpnService started, waiting for connection...")
                true

            } catch (ex: Exception) {
                Log.e(tag, "Connect failed", ex)
                _state.value = ConnectionState.Error("Connect failed: ${ex.message}")
                _state.value = ConnectionState.Disconnected
                currentProfile = null
                false
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

                // Parse the server response to create VpnProfile
                val profile: VpnProfile = if (configContent.trim().startsWith("{")) {
                    // This is a JSON server response file
                    Log.d(tag, "Parsing JSON server response")
                    configManager.parseServerResponseToProfile(configContent)
                        ?: throw IllegalArgumentException("Failed to parse JSON VPN configuration")
                } else {
                    // Fallback: parse as strongSwan .conf file
                    Log.d(tag, "Config not JSON, attempting to parse strongSwan .conf format")
                    parseStrongSwanConf(configContent)
                        ?: throw IllegalArgumentException("Unsupported config format; could not parse strongSwan .conf file")
                }
                // now call the other connect method
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

    override suspend fun disconnect(context: Context): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (_state.value is ConnectionState.Disconnected || _state.value is ConnectionState.Disconnecting) {
                    Log.w(tag, "Already disconnected or disconnecting")
                    return@withContext true
                }

                _state.value = ConnectionState.Disconnecting
                Log.d(tag, "Disconnecting from VPN")

                // Stop CharonVpnService
                val stopIntent = Intent(appContext, CharonVpnService::class.java)
                appContext.stopService(stopIntent)

                // Also send disconnect action
                val disconnectIntent = Intent(appContext, CharonVpnService::class.java).apply {
                    action = CharonVpnService.DISCONNECT_ACTION
                }
                appContext.startService(disconnectIntent)

                _state.value = ConnectionState.Disconnected
                currentProfile = null
                Log.d(tag, "VPN disconnected")
                true

            } catch (ex: Exception) {
                Log.e(tag, "Disconnect failed", ex)
                _state.value = ConnectionState.Error("Disconnect failed: ${ex.message}")
                _state.value = ConnectionState.Disconnected
                currentProfile = null
                false
            }
        }
    }

    override suspend fun getConnectionLogs(context: Context): String? {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(tag, "Retrieving connection logs")

                val uri: Uri = LogContentProvider.createContentUri() ?: run {
                    Log.w(tag, "LogContentProvider returned null URI")
                    return@withContext "LogContentProvider not available"
                }

                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    val logs = readAll(pfd)
                    Log.d(tag, "Retrieved ${logs.length} characters of logs")
                    return@withContext logs
                } ?: run {
                    Log.w(tag, "Could not open log file descriptor")
                    return@withContext "Could not access log file"
                }

            } catch (ex: Exception) {
                Log.e(tag, "Failed to read logs", ex)
                "Failed to read logs: ${ex.message}"
            }
        }
    }

    private fun readAll(pfd: ParcelFileDescriptor): String {
        return try {
            FileInputStream(pfd.fileDescriptor).use { fis ->
                String(fis.readBytes(), Charsets.UTF_8)
            }
        } catch (e: Exception) {
            Log.e(tag, "Error reading log file", e)
            "Error reading log file: ${e.message}"
        }
    }

    private fun parseStrongSwanConf(content: String): VpnProfile? {
        return try {
            val lines = content.lines()
            val map = mutableMapOf<String, String>()
            lines.forEach { raw ->
                val line = raw.trim()
                if (line.startsWith("#") || line.isBlank()) return@forEach
                val parts = line.split('=', limit = 2)
                if (parts.size == 2) {
                    val key = parts[0].trim().lowercase()
                    val value = parts[1].trim()
                    if (!key.startsWith("conn ")) {
                        map[key] = value
                    }
                }
            }
            val gateway = map["right"] ?: map["righthost"] ?: map["rightaddress"]
            if (gateway.isNullOrBlank()) {
                Log.w(tag, "Could not find gateway (right=) in .conf")
            }
            val remoteId = map["rightid"] ?: gateway
            val leftId = map["leftid"] ?: map["eap_identity"]
            val authLeft = map["leftauth"] ?: "eap-mschapv2"
            val profile = VpnProfile().apply {
                name = map["conn"] ?: "ShadowLink Config"
                this.gateway = gateway ?: ""
                this.remoteId = remoteId
                this.username = leftId
                this.password = null // password not stored in .conf for eap-mschapv2 (leftid used as identity)
                vpnType = if (authLeft.contains("tls", ignoreCase = true)) VpnType.IKEV2_EAP_TLS else VpnType.IKEV2_EAP
            }
            if (profile.gateway.isBlank()) {
                Log.e(tag, "Parsed .conf but gateway is empty")
            }
            profile
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse strongSwan .conf", e)
            null
        }
    }
}
