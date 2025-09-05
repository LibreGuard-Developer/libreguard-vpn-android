package com.example.shadowlinkvpn.service

import android.app.Service
import android.content.Intent
import android.net.VpnService
import android.os.IBinder
import android.util.Log
import org.strongswan.android.logic.CharonVpnService
import org.strongswan.android.data.VpnProfile
import org.strongswan.android.data.VpnProfileSource
import org.strongswan.android.data.VpnProfileDataSource
import com.example.shadowlinkvpn.util.VpnConfigManager

/**
 * VPN service that integrates with strongSwan to establish VPN connections
 * using P12 certificates from the CA server
 */
class ShadowLinkVpnService : VpnService() {

    private val TAG = "ShadowLinkVpnService"
    private lateinit var configManager: VpnConfigManager

    override fun onCreate() {
        super.onCreate()
        configManager = VpnConfigManager(this)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "VPN service start command received")

        when (intent?.action) {
            ACTION_CONNECT -> {
                val configJson = intent.getStringExtra(EXTRA_CONFIG_JSON)
                if (configJson != null) {
                    connectVpnWithConfig(configJson)
                } else {
                    Log.e(TAG, "Missing VPN configuration JSON")
                    stopSelf()
                }
            }
            ACTION_DISCONNECT -> {
                disconnectVpn()
            }
            else -> {
                Log.w(TAG, "Unknown action: ${intent?.action}")
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun connectVpnWithConfig(configJson: String) {
        Log.d(TAG, "Attempting to connect VPN with config")

        try {
            // Parse the server response into a VpnProfile
            val profile = configManager.parseServerResponseToProfile(configJson)
            if (profile == null) {
                Log.e(TAG, "Failed to parse VPN configuration")
                stopSelf()
                return
            }

            Log.d(TAG, "Created VPN profile: ${profile.name}")
            Log.d(TAG, "Gateway: ${profile.gateway}")
            Log.d(TAG, "Remote ID: ${profile.remoteId}")
            Log.d(TAG, "VPN Type: ${profile.vpnType}")

            // Store the profile in strongSwan's database
            val dataSource = VpnProfileSource(this)
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

            // Start the strongSwan CharonVpnService using the correct API
            val charonIntent = Intent(this, CharonVpnService::class.java)
            charonIntent.putExtra(VpnProfileDataSource.KEY_UUID, profile.uuid.toString())
            if (profile.password != null) {
                charonIntent.putExtra(VpnProfileDataSource.KEY_PASSWORD, profile.password)
            }

            Log.d(TAG, "Starting CharonVpnService with profile UUID: ${profile.uuid}")
            startService(charonIntent)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN connection", e)
            stopSelf()
        }
    }

    private fun disconnectVpn() {
        Log.d(TAG, "Disconnecting VPN")

        try {
            // Send disconnect intent to CharonVpnService
            val charonIntent = Intent(this, CharonVpnService::class.java)
            charonIntent.action = "android.net.VpnService.DISCONNECT"
            startService(charonIntent)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to disconnect VPN", e)
        } finally {
            stopSelf()
        }
    }

    companion object {
        const val ACTION_CONNECT = "com.example.shadowlinkvpn.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.example.shadowlinkvpn.ACTION_DISCONNECT"
        const val EXTRA_CONFIG_JSON = "config_json"
    }
}
