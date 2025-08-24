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
import org.strongswan.android.logic.CharonVpnService
import java.io.File
import java.io.FileInputStream

class StrongSwanHandler(
    private val appContext: Context
) : VpnProtocolHandler {

    private val tag = "StrongSwanHandler"
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val configManager = VpnConfigManager(appContext)

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _state

    @Volatile
    private var currentProfile: VpnProfile? = null

    override suspend fun initialize(context: Context): Boolean {
        Log.d(tag, "Initializing StrongSwan handler")
        return true
    }

    override suspend fun connect(context: Context, configPath: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (_state.value is ConnectionState.Connecting || _state.value is ConnectionState.Connected) {
                    Log.w(tag, "Already connecting or connected")
                    return@withContext true
                }

                _state.value = ConnectionState.Connecting
                Log.d(tag, "Starting IKEv2 connection with config: $configPath")

                // Read the config file - this should contain the JSON server response, not a StrongSwan config
                val configFile = File(configPath)
                if (!configFile.exists()) {
                    throw IllegalArgumentException("Config file does not exist: $configPath")
                }

                val configContent = configFile.readText()
                Log.d(tag, "Config content length: ${configContent.length}")

                // Parse the server response to create VpnProfile
                val profile = configManager.parseServerResponseToProfile(configContent)
                if (profile == null) {
                    throw IllegalArgumentException("Failed to parse VPN configuration")
                }

                currentProfile = profile
                Log.d(tag, "Created VpnProfile: ${profile.name}, Gateway: ${profile.gateway}, Type: ${profile.vpnType}")

                // Start CharonVpnService with the profile
                val intent = Intent(appContext, CharonVpnService::class.java).apply {
                    // Add profile data as extras
                    val bundle = Bundle().apply {
                        putString(VpnProfileDataSource.KEY_UUID, profile.uuid.toString())
                        putString(VpnProfileDataSource.KEY_PASSWORD, profile.password)
                    }
                    putExtras(bundle)

                    Log.d(tag, "Starting CharonVpnService with profile UUID: ${profile.uuid}")
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }

                // Note: The actual connection status will be updated via VpnStateService callbacks
                // For now, we set it to connecting and let the service update the real status
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

                // Try to read the log content
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null, null, null
                )?.use { cursor ->
                    if (!cursor.moveToFirst()) {
                        Log.w(tag, "No log data available")
                        return@withContext "No log data available"
                    }
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

    /**
     * Update connection state from external sources (e.g., VpnStateService)
     */
    fun updateConnectionState(newState: ConnectionState) {
        Log.d(tag, "Connection state updated to: $newState")
        _state.value = newState
    }

    /**
     * Get the current VPN profile
     */
    fun getCurrentProfile(): VpnProfile? = currentProfile

    companion object {
        const val EXTRA_CONFIG_FILE = "configFile"
    }
}