package com.example.shadowlinkvpn.service.vpn

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.shadowlinkvpn.util.VpnConfigManager
import kotlinx.coroutines.Dispatchers
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
                        putString("uuid", profile.getUUID().toString())
                        putString("password", "<redacted>")
                        putString("remoteId", "DE-IKEV2-1")
                        // Always pass password if available - StrongSwan needs it for certificate decryption
                        if (!profile.password.isNullOrBlank()) {
                            putString("password", profile.password)
                            Log.d(tag, "Set password for VPN connection: ${profile.password}")
                        } else {
                            Log.w(tag, "No password available for VPN connection")
                        }

                        // Authentication type logging
                        if (profile.vpnType == VpnType.IKEV2_EAP_TLS || profile.vpnType == VpnType.IKEV2_CERT) {
                            Log.d(tag, "Using certificate-based authentication (${profile.vpnType})")
                        } else {
                            Log.d(tag, "Using password-based / EAP authentication")
                        }

                        // If we have a P12 certificate alias, add it
                        profile.userCertificateAlias?.let { alias ->
                            // Use original strongSwan extra key expected by CharonVpnService
                            putString("certificate_alias", alias)
                            Log.d(tag, "Added certificate alias (certificate_alias): $alias")
                        }

                        // Add username if available
                        if (!profile.username.isNullOrBlank()) {
                            putString("username", profile.username)
                            Log.d(tag, "Set username for VPN connection: ${profile.username}")
                        }
                    }
                    putExtras(bundle)
                    Log.d(tag, "Password Just before starting VPN Service ${profile.password}")
                    Log.d(tag, "Starting CharonVpnService with profile UUID: ${profile.getUUID()}")
                    Log.d(tag, "VPN Type: ${profile.vpnType}, Gateway: ${profile.gateway}")
                    Log.d(tag, "Certificate alias: ${profile.userCertificateAlias}")
                }

                // The VpnProfile needs to be in the database for CharonVpnService to find it
                val dataSource = VpnProfileSource(appContext)
                dataSource.open()

                // Clean up profile values to prevent configuration parsing errors
                profile.remoteId = "DE-IKEV2-1"
                profile.gateway = "217.154.229.53"
                profile.name = "IKEV2_client49 VPN"
                // Preserve username (EAP identity) for EAP/EAP-TLS instead of nulling it
                // profile.username was previously nulled which can break identity based auth
                // Do not overwrite if already set
                if (profile.username.isNullOrBlank()) {
                    Log.d(tag, "No explicit username set; leaving as-is (null)")
                } else {
                    Log.d(tag, "Preserving username/EAP identity: ${profile.username}")
                }
                profile.password = "<redacted>"

                // Avoid assigning null to proposal fields; empty string prevents SettingsWriter newline issues
                profile.ikeProposal = profile.ikeProposal ?: ""
                profile.espProposal = profile.espProposal ?: ""
                // Do not force-null certificateAlias/dnsServers; leave existing values if present
                // profile.certificateAlias = null
                // profile.dnsServers = null
                profile.splitTunneling = 0
                profile.mtu = 1400  // Set a safe MTU value
                profile.natKeepAlive = 20
                profile.port = 500

                // Clear any flags that might cause issues
                // DO NOT blindly zero out flags; keep existing behavior unless a specific bit must be cleared.
                // (Previously: profile.flags = 0) Removing this to preserve strongSwan expectations.

                // Ensure password is preserved when saving to database
                Log.d(tag, "Profile password before database operations: ${profile.password}")

                val existingProfile = dataSource.getVpnProfile(profile.getUUID().toString())
                if (existingProfile == null) {
                    Log.d(tag, "Inserting new profile with password: ${profile.password}")
                    dataSource.insertProfile(profile)
                } else {
                    Log.d(tag, "Updating existing profile, preserving password: ${profile.password}")
                    // Ensure password is preserved during update and clean values
                    existingProfile.password = "<redacted>"
                    existingProfile.userCertificateAlias = profile.userCertificateAlias
                    existingProfile.vpnType = profile.vpnType
                    existingProfile.gateway = profile.gateway?.trim()?.replace("\n", "")?.replace("\r", "") ?: ""
                    existingProfile.name = profile.name?.trim()?.replace("\n", "")?.replace("\r", "") ?: "ShadowLink VPN"
                    existingProfile.username = profile.username?.trim()?.replace("\n", "")?.replace("\r", "")
                    existingProfile.remoteId = "DE-IKEV2-1"

                    // Clear any potentially problematic fields that might cause config parsing issues
                    existingProfile.ikeProposal = existingProfile.ikeProposal ?: ""
                    existingProfile.espProposal = existingProfile.espProposal ?: ""
                    // existingProfile.certificateAlias = null  // keep original if set
                    // existingProfile.dnsServers = null       // keep original if set
                    existingProfile.splitTunneling = 0
                    // Preserve existing flags instead of resetting to 0

                    dataSource.updateVpnProfile(existingProfile)
                }

                // Verify password was saved correctly
                val savedProfile = dataSource.getVpnProfile(profile.getUUID().toString())
                Log.d(tag, "Profile password after database save: ${savedProfile?.password}")
                Log.d(tag, "Profile gateway after database save: ${savedProfile?.gateway}")
                Log.d(tag, "Profile remoteId after database save: ${savedProfile?.remoteId}")

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

                var profile: VpnProfile? = configManager.parseServerResponseToProfile(configContent)
                if (profile == null) {
                    Log.d(tag, "Not JSON, attempting .conf parse fallback")
                    profile = parseStrongSwanConf(configContent)
                    if (profile != null) {
                        // Try to upgrade to richer stored profile if exists (same gateway)
                        val ds = VpnProfileSource(appContext)
                        ds.open()
                        try {
                            val existing = ds.allVpnProfiles.firstOrNull { it.gateway == profile.gateway }
                            if (existing != null) {
                                Log.d(tag, "Found existing stored profile for gateway; reusing its auth + type")
                                existing.name = existing.name ?: profile.name
                                profile = existing
                            }
                        } catch (e: Exception) {
                            Log.w(tag, "Failed to lookup existing profiles: ${e.message}")
                        } finally { ds.close() }
                    }
                }
                if (profile == null) throw IllegalArgumentException("Unsupported config format; could not parse configuration")
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

    private fun parseStrongSwanConf(content: String): VpnProfile? {
        return try {
            val lines = content.lines()
            val map = mutableMapOf<String, String>()
            lines.forEach { raw ->
                val line = raw.trim()
                if (line.startsWith("#") || line.isBlank()) return@forEach
                if (line.startsWith("conn ")) {
                    map["conn"] = line.removePrefix("conn").trim()
                    return@forEach
                }
                val parts = line.split('=', limit = 2)
                if (parts.size == 2) {
                    val key = parts[0].trim().lowercase()
                    val value = parts[1].trim()
                    map[key] = value
                }
            }
            val gateway = map["right"] ?: map["righthost"] ?: map["rightaddress"]
            val profile = VpnProfile().apply {
                name = map["conn"].takeUnless { it.isNullOrBlank() } ?: "ShadowLink Config"
                this.gateway = gateway ?: ""
                remoteId = map["rightid"] ?: this.gateway
                username = map["leftid"] ?: map["eap_identity"]
                vpnType = if ((map["leftauth"] ?: "").contains("tls", true)) VpnType.IKEV2_EAP_TLS else VpnType.IKEV2_EAP
            }
            if (profile.gateway.isBlank()) {
                Log.w(tag, ".conf parse produced empty gateway")
            }
            profile
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse .conf: ${e.message}")
            null
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
}
