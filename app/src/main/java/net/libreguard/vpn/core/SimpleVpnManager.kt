package net.libreguard.vpn.core

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.util.Log
import net.libreguard.vpn.util.VpnConfigManager
import org.strongswan.android.data.VpnProfile
import org.strongswan.android.logic.CharonVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.strongswan.android.data.VpnProfileSource

/**
 * Simplified VPN Connection Manager
 * Bypasses UI complexities and focuses on core VPN connection functionality
 */
class SimpleVpnManager(private val context: Context) {

    private val tag = "SimpleVpnManager"
    private val configManager = VpnConfigManager(context)
    private var currentProfile: VpnProfile? = null

    /**
     * Connect to VPN using server response JSON
     */
    suspend fun connectToVpn(serverResponseJson: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(tag, "Starting VPN connection with server response")
                Log.d(tag, "Server response: $serverResponseJson")

                // Parse server response to VPN profile
                val profile = configManager.parseServerResponseToProfile(serverResponseJson)
                if (profile == null) {
                    Log.e(tag, "Failed to parse server response")
                    return@withContext false
                }

                currentProfile = profile
                Log.d(tag, "Created VPN profile: ${profile.name}")
                Log.d(tag, "Gateway: ${profile.gateway}")
                Log.d(tag, "Remote ID: ${profile.remoteId}")
                Log.d(tag, "VPN Type: ${profile.vpnType}")

                // Start strongSwan CharonVpnService directly
                val success = startCharonVpnService(profile)
                if (success) {
                    Log.d(tag, "Successfully started CharonVpnService")
                } else {
                    Log.e(tag, "Failed to start CharonVpnService")
                }

                success

            } catch (e: Exception) {
                Log.e(tag, "VPN connection failed", e)
                false
            }
        }
    }

    /**
     * Start strongSwan CharonVpnService with the given profile
     */
    private fun startCharonVpnService(profile: VpnProfile): Boolean {
        return try {
            val intent = Intent(context, CharonVpnService::class.java).apply {
                // Pass profile UUID for CharonVpnService to use
                putExtra("org.strongswan.android.VpnProfileDataSource.KEY_UUID", profile.uuid.toString())
                putExtra("org.strongswan.android.VpnProfileDataSource.KEY_PASSWORD", profile.password ?: "")

                Log.d(tag, "Starting CharonVpnService with UUID: ${profile.uuid}")
            }

            // Store profile in database for CharonVpnService to access
            storeProfileInDatabase(profile)

            // Start the service
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }

            true
        } catch (e: Exception) {
            Log.e(tag, "Failed to start CharonVpnService", e)
            false
        }
    }

    /**
     * Store VPN profile in database for CharonVpnService to access
     */
    private fun storeProfileInDatabase(profile: VpnProfile) {
        try {
            val dataSource = VpnProfileSource(context)
            dataSource.open()

            try {
                // Check if profile exists and update, otherwise insert
                val existingProfile = dataSource.getVpnProfile(profile.uuid.toString())
                if (existingProfile != null) {
                    dataSource.updateVpnProfile(profile)
                    Log.d(tag, "Updated existing VPN profile in database")
                } else {
                    dataSource.insertProfile(profile)
                    Log.d(tag, "Inserted new VPN profile in database")
                }
            } finally {
                dataSource.close()
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to store profile in database", e)
        }
    }

    /**
     * Disconnect VPN
     */
    suspend fun disconnectVpn(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(tag, "Disconnecting VPN")

                // Stop CharonVpnService
                val stopIntent = Intent(context, CharonVpnService::class.java)
                context.stopService(stopIntent)

                // Also send disconnect action
                val disconnectIntent = Intent(context, CharonVpnService::class.java).apply {
                    action = CharonVpnService.DISCONNECT_ACTION
                }
                context.startService(disconnectIntent)

                currentProfile = null
                Log.d(tag, "VPN disconnected")
                true

            } catch (e: Exception) {
                Log.e(tag, "Failed to disconnect VPN", e)
                false
            }
        }
    }

    /**
     * Check VPN permission
     */
    fun checkVpnPermission(): Intent? {
        return VpnService.prepare(context)
    }

    /**
     * Get connection logs
     */
    suspend fun getConnectionLogs(): String {
        return withContext(Dispatchers.IO) {
            try {
                val logFile = java.io.File(context.filesDir, "charon.log")
                if (logFile.exists()) {
                    logFile.readText()
                } else {
                    "Log file not found. Connection may not have been attempted yet."
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to read logs", e)
                "Failed to read logs: ${e.message}"
            }
        }
    }

    /**
     * Get current profile info
     */
    fun getCurrentProfileInfo(): String? {
        return currentProfile?.let { profile ->
            """
            Profile: ${profile.name}
            Gateway: ${profile.gateway}
            Remote ID: ${profile.remoteId}
            VPN Type: ${profile.vpnType?.name}
            UUID: ${profile.uuid}
            """.trimIndent()
        }
    }
}
