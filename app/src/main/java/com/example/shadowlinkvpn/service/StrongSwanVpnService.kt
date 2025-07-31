// app/src/main/java/com/example/shadowlinkvpn/service/StrongSwanVpnService.kt
package com.example.shadowlinkvpn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.system.OsConstants
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.shadowlinkvpn.MainActivity
import com.example.shadowlinkvpn.R
import com.example.shadowlinkvpn.service.vpn.StrongSwanHandler
import com.example.shadowlinkvpn.service.vpn.VpnConnectionState
import com.example.shadowlinkvpn.service.vpn.VpnConnectionStatus
import com.example.shadowlinkvpn.util.VpnConfigManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

class StrongSwanVpnService : VpnService() {
    private val CHANNEL_ID = "VPN_SERVICE_CHANNEL"
    private val NOTIFICATION_ID = 1

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val strongSwanHandler = StrongSwanHandler()
    private val configManager by lazy { VpnConfigManager(this) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Initialize VPN handler
        serviceScope.launch {
            if (!strongSwanHandler.initialize(this@StrongSwanVpnService)) {
                stopSelf()
                return@launch
            }

            // Monitor connection state
            strongSwanHandler.connectionState.collectLatest { state ->
                when(state.status) {
                    VpnConnectionStatus.CONNECTED ->
                        updateNotification("Connected", "VPN connection established")
                    VpnConnectionStatus.CONNECTING ->
                        updateNotification("Connecting", "Establishing VPN connection...")
                    VpnConnectionStatus.DISCONNECTED -> {
                        updateNotification("Disconnected", "VPN connection terminated")
                        stopForeground(true)
                        stopSelf()
                    }
                    VpnConnectionStatus.ERROR ->
                        updateNotification("Error", state.errorMessage ?: "Unknown error")
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val configFilePath = intent?.getStringExtra("configFile") ?: return START_NOT_STICKY
        val action = intent.getStringExtra("action") ?: "connect"

        when (action) {
            "connect" -> startVpnConnection(configFilePath)
            "disconnect" -> stopVpnConnection()
        }

        return START_STICKY
    }

    private fun startVpnConnection(configFilePath: String) {
        serviceScope.launch {
            try {
                // Parse configuration
                val configFile = File(configFilePath)
                val config = configManager.parseStrongSwanConfig(configFile)

                if (config == null) {
                    throw Exception("Invalid configuration file")
                }

                // Setup VPN parameters based on the config
                val builder = Builder()
                    .setSession("ShadowLinkVPN")
                    .addAddress("10.0.0.2", 24)  // Could be dynamic from config
                    .addRoute("0.0.0.0", 0)      // Route all traffic
                    .addDnsServer("8.8.8.8")     // Add appropriate DNS servers
                    .allowFamily(OsConstants.AF_INET)
                    .allowFamily(OsConstants.AF_INET6)
                    .setMtu(config.mtu)

                // Establish the VPN interface
                val vpnInterface = builder.establish()
                if (vpnInterface == null) {
                    throw Exception("Failed to establish VPN interface")
                }

                // Start foreground service with connecting status
                updateNotification("Connecting", "Establishing VPN connection...")

                // Connect using the protocol handler
                val params = mapOf(
                    "connection_name" to "shadowlink_vpn",
                    "vpnInterface" to vpnInterface
                )

                strongSwanHandler.connect(configFilePath, params)

            } catch (e: Exception) {
                Log.e("StrongSwanVPN", "Error starting VPN", e)
                updateNotification("Connection Failed", e.localizedMessage ?: "Unknown error")
                stopSelf()
            }
        }
    }

    private fun stopVpnConnection() {
        serviceScope.launch {
            strongSwanHandler.disconnect()
        }
    }

    override fun onDestroy() {
        serviceScope.launch {
            strongSwanHandler.disconnect()
            strongSwanHandler.cleanup()
        }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VPN Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifications for VPN connection status"
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(title: String, content: String) {
        val notification = createNotification(title, content)
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotification(title: String, content: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}