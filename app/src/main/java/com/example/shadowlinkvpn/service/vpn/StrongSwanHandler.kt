package com.example.shadowlinkvpn.service.vpn

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.strongswan.android.logic.CharonVpnService
import java.io.File
import android.net.VpnService
import com.example.shadowlinkvpn.util.VpnConfigManager
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.UUID
import org.strongswan.android.data.VpnProfile
import org.strongswan.android.data.VpnType
import android.app.ActivityManager
import android.net.Uri
import org.strongswan.android.data.LogContentProvider
class StrongSwanHandler : VpnProtocolHandler() {
    private val TAG = "StrongSwanHandler"
    private lateinit var configManager: VpnConfigManager
    private var currentServerIp: String? = null

    override suspend fun initialize(context: Context): Boolean {
        return try {
            configManager = VpnConfigManager(context)
            Log.d(TAG, "StrongSwan handler initialized")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize StrongSwan handler", e)
            false
        }
    }
// Polu ispravna connect funkcija pre nego sto sam je zamenio sa novom
//    override suspend fun connect(context: Context, configPath: String): Boolean {
//        return try {
//            updateConnectionState(ConnectionState.Connecting)
//            Log.d(TAG, "Starting StrongSwan connection with config: $configPath")
//
//            val configFile = File(configPath)
//            if (!configFile.exists()) {
//                Log.e(TAG, "Config file does not exist: $configPath")
//                updateConnectionState(ConnectionState.Error("Config file not found"))
//                return false
//            }
//
//            // Check VPN permission first
//            val vpnIntent = VpnService.prepare(context)
//            if (vpnIntent != null) {
//                Log.e(TAG, "VPN permission not granted")
//                updateConnectionState(ConnectionState.Error("VPN permission required"))
//                return false
//            }
//
//            // Parse the config using VpnConfigManager
//            val swanConfig = configManager.parseStrongSwanConfig(configFile)
//            if (swanConfig == null) {
//                Log.e(TAG, "Failed to parse StrongSwan config")
//                updateConnectionState(ConnectionState.Error("Invalid StrongSwan config format"))
//                return false
//            }
//
//            Log.d(TAG, "Parsed config - Server: ${swanConfig.serverAddress}, UUID: ${swanConfig.uuid}")
//
//            // Create and persist the VPN profile
//            val profile = createAndPersistProfile(context, swanConfig)
//            if (profile == null) {
//                Log.e(TAG, "Failed to create VPN profile")
//                updateConnectionState(ConnectionState.Error("Failed to create VPN profile"))
//                return false
//            }
//
//            // Import certificates into StrongSwan's managed stores
//            val certImported = importCertificates(context, swanConfig)
//            if (!certImported) {
//                Log.e(TAG, "Failed to import certificates")
//                updateConnectionState(ConnectionState.Error("Certificate import failed"))
//                return false
//            }
//
//            // Start the actual connection
//            val connected = startStrongSwanConnection(context, swanConfig.uuid)
//            if (!connected) {
//                updateConnectionState(ConnectionState.Error("Failed to start StrongSwan connection"))
//                return false
//            }
//
//            // Monitor the connection
//            return monitorConnection(context)
//
//        } catch (e: Exception) {
//            Log.e(TAG, "StrongSwan connection failed", e)
//            updateConnectionState(ConnectionState.Error(e.localizedMessage ?: "Connection failed"))
//            return false
//        }
//    }

    override suspend fun connect(context: Context, configPath: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                updateConnectionState(ConnectionState.Connecting)
                Log.d(TAG, "=== Starting StrongSwan connection ===")
                Log.d(TAG, "Config path: $configPath")

                // Parse the config file
                val configFile = File(configPath)
                if (!configFile.exists()) {
                    Log.e(TAG, "Config file does not exist: $configPath")
                    updateConnectionState(ConnectionState.Error("Config file not found"))
                    return@withContext false
                }
                Log.d(TAG, "Config file exists, size: ${configFile.length()} bytes")
                Log.d(TAG, "Config content preview: ${configFile.readText().take(200)}...")


                // Check VPN permission
                val vpnIntent = VpnService.prepare(context)
                if (vpnIntent != null) {
                    Log.e(TAG, "VPN permission not granted")
                    updateConnectionState(ConnectionState.Error("VPN permission required"))
                    return@withContext false
                }
                Log.d(TAG, "VPN permission granted")

                val swanConfig = configManager.parseStrongSwanConfig(configFile)
                if (swanConfig == null) {
                    Log.e(TAG, "Failed to parse StrongSwan config")
                    updateConnectionState(ConnectionState.Error("Invalid config format"))
                    return@withContext false
                }

                Log.d(TAG, "Config parsed successfully:")
                Log.d(TAG, "  Server: ${swanConfig.serverAddress}")
                Log.d(TAG, "  Server IP: ${swanConfig.serverIp}")
                Log.d(TAG, "  Remote ID: ${swanConfig.remoteId}")
                Log.d(TAG, "  UUID: ${swanConfig.uuid}")


                // assigning the current public IP here
                currentServerIp = swanConfig.serverIp ?: swanConfig.serverAddress

                // Import certificates first
                val certsImported = importCertificates(context, swanConfig)
                if (!certsImported) {
                    Log.e(TAG, "Failed to import certificates")
                    updateConnectionState(ConnectionState.Error("Certificate import failed"))
                    return@withContext false
                }

                // Create VPN profile for StrongSwan
                val profile = createVpnProfile(context, swanConfig)
                Log.d(TAG, "VPN profile created with UUID: ${profile.uuid}")

                Log.d(TAG, "Starting CharonVpnService...")
                // Connect using CharonVpnService with correct constants
                val intent = Intent(context, CharonVpnService::class.java).apply {
                    putExtra("org.strongswan.android.VpnProfileDataSource.KEY_UUID", profile.uuid.toString())
                    profile.name?.let { putExtra("org.strongswan.android.VpnProfileDataSource.KEY_NAME", it) }
                    profile.gateway?.let { putExtra("org.strongswan.android.VpnProfileDataSource.KEY_GATEWAY", it) }
                    profile.vpnType?.identifier?.let { putExtra("org.strongswan.android.VpnProfileDataSource.KEY_VPN_TYPE", it) }
                    profile.remoteId?.let { putExtra("org.strongswan.android.VpnProfileDataSource.KEY_REMOTE_ID", it) }
                    profile.certificateAlias?.let { putExtra("org.strongswan.android.VpnProfileDataSource.KEY_CERTIFICATE", it) }
                    profile.userCertificateAlias?.let { putExtra("org.strongswan.android.VpnProfileDataSource.KEY_USER_CERTIFICATE", it) }
                    profile.dnsServers?.let { putExtra("org.strongswan.android.VpnProfileDataSource.KEY_DNS_SERVERS", it) }
                    profile.port?.let { putExtra("org.strongswan.android.VpnProfileDataSource.KEY_PORT", it) }
                    profile.flags?.let { putExtra("org.strongswan.android.VpnProfileDataSource.KEY_FLAGS", it) }

                    Log.d(TAG, "Intent extras:")
                    Log.d(TAG, "  UUID: ${profile.uuid}")
                    Log.d(TAG, "  Name: ${profile.name}")
                    Log.d(TAG, "  Gateway: ${profile.gateway}")
                    Log.d(TAG, "  VPN Type: ${profile.vpnType?.identifier}")

                }

                try {
                    context.startService(intent)
                    Log.d(TAG, "CharonVpnService start command sent")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start CharonVpnService", e)
                    updateConnectionState(ConnectionState.Error("Failed to start VPN service"))
                    return@withContext false
                }

                // Wait and monitor connection with more detailed checks
                Log.d(TAG, "Monitoring connection establishment...")
                repeat(30) { attempt ->
                    delay(1000)
                    Log.d(TAG, "Connection check attempt ${attempt + 1}/30")

                    // Check if service started
                    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    val services = activityManager.getRunningServices(Integer.MAX_VALUE)
                    val serviceRunning = services.any { it.service.className == "org.strongswan.android.logic.CharonVpnService" }

                    Log.d(TAG, "  CharonVpnService running: $serviceRunning")

                    if (serviceRunning) {
                        Log.d(TAG, "  Service is running, checking connection state...")

                        if (isStrongSwanConnected(context)) {
                            updateConnectionState(ConnectionState.Connected)
                            Log.d(TAG, "=== StrongSwan connection established ===")
                            return@withContext true
                        }
                    } else {
                        Log.w(TAG, "  CharonVpnService not running yet")
                    }
                }

                Log.e(TAG, "Connection timeout - no connection established after 30 seconds")
                updateConnectionState(ConnectionState.Error("Connection timeout"))
                return@withContext false

            } catch (e: Exception) {
                Log.e(TAG, "StrongSwan connection failed with exception", e)
                updateConnectionState(ConnectionState.Error(e.localizedMessage ?: "Connection failed"))
                return@withContext false
            }
        }
    }

    override suspend fun disconnect(context: Context): Boolean {
        return try {
            updateConnectionState(ConnectionState.Disconnecting)

            val intent = Intent(context, CharonVpnService::class.java).apply {
                action = CharonVpnService.DISCONNECT_ACTION
            }
            context.startService(intent)

            delay(2000)
            updateConnectionState(ConnectionState.Disconnected)
            Log.d(TAG, "StrongSwan disconnected")
            true
        } catch (e: Exception) {
            Log.e(TAG, "StrongSwan disconnect failed", e)
            updateConnectionState(ConnectionState.Error(e.localizedMessage ?: "Disconnect failed"))
            false
        }
    }

    private fun createAndPersistProfile(context: Context, config: VpnConfigManager.StrongSwanConfig): Any? {
        return try {
            val profileClass = Class.forName("org.strongswan.android.data.VpnProfile")
            val vpnTypeClass = Class.forName("org.strongswan.android.data.VpnType")
            val dataSourceClass = Class.forName("org.strongswan.android.data.VpnProfileManagedDataSource")

            // Create new VPN profile
            val profile = profileClass.getDeclaredConstructor().newInstance()

            // Set profile properties using reflection
            setProfileProperty(profile, "uuid", config.uuid)
            setProfileProperty(profile, "name", config.serverAddress)
            setProfileProperty(profile, "gateway", config.serverAddress)
            setProfileProperty(profile, "remoteId", config.remoteId)
            setProfileProperty(profile, "localId", config.remoteId)
            setProfileProperty(profile, "userCertificate", config.uuid)
            setProfileProperty(profile, "certificate", config.uuid + "_ca")


            // Set VPN type to IKEV2_EAP
            val ikev2EapType = vpnTypeClass.getField("IKEV2_EAP").get(null)
            setProfileProperty(profile, "mVpnType", ikev2EapType)

            // Use the concrete implementation class
            val dataSource = dataSourceClass.getDeclaredConstructor(Context::class.java).newInstance(context)

            // Persist the profile
            try {
                dataSourceClass.getMethod("open").invoke(dataSource)
                dataSourceClass.getMethod("insertProfile", profileClass).invoke(dataSource, profile)
                Log.d(TAG, "VPN profile persisted with UUID: ${config.uuid}")
            } finally {
                try {
                    dataSourceClass.getMethod("close").invoke(dataSource)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to close data source", e)
                }
            }

            return profile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create and persist VPN profile", e)
            return null
        }
    }

    private fun setProfileProperty(profile: Any, propertyName: String, value: Any?) {
        if (value == null) return

        try {
            // For fields starting with 'm', try direct field access first
            if (propertyName.startsWith("m")) {
                val field = profile::class.java.getDeclaredField(propertyName)
                field.isAccessible = true
                field.set(profile, value)
                return
            }

            // Try setter method for non-m prefixed properties
            val setterName = "set" + propertyName.replaceFirstChar { it.uppercase() }
            val method = profile::class.java.getMethod(setterName, value::class.java)
            method.invoke(profile, value)
        } catch (e: NoSuchFieldException) {
            try {
                // If field access fails, try setter method
                val setterName = if (propertyName.startsWith("m")) {
                    "set" + propertyName.substring(1).replaceFirstChar { it.uppercase() }
                } else {
                    "set" + propertyName.replaceFirstChar { it.uppercase() }
                }

                val method = profile::class.java.getMethod(setterName, value::class.java)
                method.invoke(profile, value)
            } catch (e2: Exception) {
                Log.w(TAG, "Could not set property $propertyName", e2)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error setting property $propertyName", e)
        }
    }

//    private fun setProfileProperty(profile: Any, propertyName: String, value: Any?) {
//        if (value == null) return
//
//        try {
//            // Try setter method first
//            val setterName = "set" + propertyName.replaceFirstChar { it.uppercase() }
//            val method = profile::class.java.getMethod(setterName, value::class.java)
//            method.invoke(profile, value)
//        } catch (e: NoSuchMethodException) {
//            try {
//                // Try direct field access
//                val field = profile::class.java.getDeclaredField(propertyName)
//                field.isAccessible = true
//                field.set(profile, value)
//            } catch (e: Exception) {
//                Log.w(TAG, "Could not set property $propertyName", e)
//            }
//        } catch (e: Exception) {
//            Log.w(TAG, "Error setting property $propertyName", e)
//        }
//    }

    private suspend fun importCertificates(context: Context, swanConfig: VpnConfigManager.StrongSwanConfig): Boolean {
        return withContext(Dispatchers.IO) {
            var serverCertImported = false
            var clientCertImported = false

            try {
                // Import server certificate if present (keeping existing working code)
                if (swanConfig.serverCert.isNotBlank()) {
                    try {
                        val certData = extractBase64Certificate(swanConfig.serverCert)
                        val certBytes = certData.toByteArray()

                        val certificateFactory = CertificateFactory.getInstance("X.509")
                        val certificate = certificateFactory.generateCertificate(certBytes.inputStream()) as X509Certificate

                        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore")
                        keyStore.load(null)

                        val alias = "server_cert_${System.currentTimeMillis()}"
                        keyStore.setCertificateEntry(alias, certificate)

                        Log.d(TAG, "Server certificate imported with alias: $alias")
                        serverCertImported = true
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to import server certificate", e)
                    }
                }
// Import client P12 certificate - prioritize StrongSwan direct file approach
                if (swanConfig.clientP12.isNotBlank() && swanConfig.clientPassword.isNotBlank()) {
                    try {
                        val p12Bytes = extractP12Certificate(swanConfig.clientP12)

                        // Since Android KeyStore loading fails, save P12 file directly for StrongSwan
                        val certsDir = File(context.filesDir, "certs")
                        if (!certsDir.exists()) {
                            certsDir.mkdirs()
                        }

                        val p12File = File(certsDir, "client.p12")
                        p12File.writeBytes(p12Bytes)

                        Log.d(TAG, "P12 certificate saved to: ${p12File.absolutePath}")
                        clientCertImported = true

                        // Optional: Try Android KeyStore as secondary attempt (but don't fail if it doesn't work)
                        try {
                            val keyStore = java.security.KeyStore.getInstance("PKCS12")
                            keyStore.load(p12Bytes.inputStream(), swanConfig.clientPassword.toCharArray())

                            val aliases = keyStore.aliases()
                            if (aliases.hasMoreElements()) {
                                Log.d(TAG, "P12 also loaded in Android KeyStore successfully")
                            }
                        } catch (e: Exception) {
                            Log.d(TAG, "Android KeyStore loading failed (expected), using direct file approach")
                        }

                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing P12 certificate", e)
                        clientCertImported = false
                    }
                }

                val success = if (swanConfig.serverCert.isNotBlank() && swanConfig.clientP12.isNotBlank()) {
                    serverCertImported && clientCertImported
                } else if (swanConfig.serverCert.isNotBlank()) {
                    serverCertImported
                } else if (swanConfig.clientP12.isNotBlank()) {
                    clientCertImported
                } else {
                    true // No certificates to import
                }

                Log.d(TAG, "Certificate import result - Server: $serverCertImported, Client: $clientCertImported, Overall: $success")
                success

            } catch (e: Exception) {
                Log.e(TAG, "Error in importCertificates", e)
                false
            }
        }
    }

    private fun startStrongSwanConnection(context: Context, profileUuid: String): Boolean {
        return try {
            val intent = Intent(context, CharonVpnService::class.java).apply {
                action = "org.strongswan.android.action.START_VPN"
                putExtra("org.strongswan.android.intent.extra.PROFILE_UUID", profileUuid)
                putExtra("VPN_PROFILE_UUID", profileUuid)
            }

            context.startService(intent)
            Log.d(TAG, "Started StrongSwan service with profile UUID: $profileUuid")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start StrongSwan connection", e)
            return false
        }
    }

    private suspend fun monitorConnection(context: Context): Boolean {
        var attempts = 0
        val maxAttempts = 30

        while (attempts < maxAttempts) {
            delay(1000)
            attempts++

            if (checkVpnConnection()) {
                updateConnectionState(ConnectionState.Connected)
                Log.d(TAG, "StrongSwan connected successfully")
                return true
            }

            Log.d(TAG, "Connection attempt $attempts/$maxAttempts")
        }

        updateConnectionState(ConnectionState.Error("Connection timeout"))
        return false
    }

    private suspend fun checkVpnConnection(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                for (iface in interfaces) {
                    if ((iface.name.contains("tun") || iface.name.contains("ipsec")) &&
                        iface.isUp && !iface.isLoopback) {
                        return@withContext true
                    }
                }
                false
            } catch (e: Exception) {
                Log.e(TAG, "Error checking VPN connection", e)
                false
            }
        }
    }

    // Ova funkcija je radila KAKO TREBA, zamenjena da sredi problem sa .p12 imortom fajlova
    private fun extractBase64Certificate(certData: String): String {
        // First decode the base64 to get the full certificate text
        val decodedText = String(android.util.Base64.decode(certData, android.util.Base64.DEFAULT))

        // Find the certificate boundaries
        val beginMarker = "-----BEGIN CERTIFICATE-----"
        val endMarker = "-----END CERTIFICATE-----"

        val startIndex = decodedText.indexOf(beginMarker)
        val endIndex = decodedText.indexOf(endMarker)

        if (startIndex == -1 || endIndex == -1) {
            throw IllegalArgumentException("Invalid certificate format: missing BEGIN/END markers")
        }

        // Extract the certificate including the markers
        return decodedText.substring(startIndex, endIndex + endMarker.length)
    }

    private fun extractP12Certificate(p12Data: String): ByteArray {
        return try {
            // Remove any whitespace and newlines
            val cleanedData = p12Data.replace("\\s".toRegex(), "")

            // Direct Base64 decode - the p12Data should be raw Base64 encoded .p12 file
            android.util.Base64.decode(cleanedData, android.util.Base64.DEFAULT)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode P12 certificate data", e)
            throw IllegalArgumentException("Invalid P12 certificate format", e)
        }
    }

    private fun createVpnProfile(context: Context, swanConfig: VpnConfigManager.StrongSwanConfig): VpnProfile {
        return VpnProfile().apply {
            uuid = UUID.fromString(swanConfig.uuid)
            name = "IKEv2 VPN - ${swanConfig.serverAddress}"
            gateway = swanConfig.serverAddress
            vpnType = VpnType.IKEV2_CERT
            remoteId = swanConfig.remoteId.takeIf { it.isNotBlank() } ?: swanConfig.serverAddress

            // Set certificate aliases if we have them
            if (swanConfig.serverCert.isNotBlank()) {
                certificateAlias = "server_cert_${System.currentTimeMillis()}"
            }

            if (swanConfig.clientP12.isNotBlank()) {
                userCertificateAlias = "client_cert_${System.currentTimeMillis()}"
            }

            // Set DNS servers
            dnsServers = swanConfig.dnsServers.joinToString(",")

            // Set port if needed (default IKEv2 port is 500)
            port = 500

            // Enable certificate-based authentication
            flags = VpnProfile.FLAGS_SUPPRESS_CERT_REQS or VpnProfile.FLAGS_DISABLE_CRL
        }
    }

    private suspend fun isStrongSwanConnected(context: Context): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Check if CharonVpnService is running
                val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val services = activityManager.getRunningServices(Integer.MAX_VALUE)
                val serviceRunning = services.any { it.service.className == "org.strongswan.android.logic.CharonVpnService" }

                Log.d(TAG, "CharonVpnService running: $serviceRunning")

                if (!serviceRunning) {
                    Log.d(TAG, "VPN service not running")
                    return@withContext false
                }

                // Get current public IP and compare with server IP
                val currentPublicIp = getCurrentPublicIp()
                val expectedServerIp = getExpectedServerIp()

                Log.d(TAG, "Current public IP: $currentPublicIp")
                Log.d(TAG, "Expected server IP: $expectedServerIp")

                val ipMatches = currentPublicIp != null && expectedServerIp != null &&
                        currentPublicIp == expectedServerIp

                Log.d(TAG, "IP addresses match: $ipMatches")

                return@withContext serviceRunning && ipMatches

            } catch (e: Exception) {
                Log.e(TAG, "Error checking VPN connection", e)
                false
            }
        }
    }

    private suspend fun getCurrentPublicIp(): String? {
        return try {
            val url = java.net.URL("https://api.ipify.org")
            val connection = url.openConnection()
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.getInputStream().bufferedReader().use { it.readText().trim() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get current public IP", e)
            null
        }
    }

    private fun getExpectedServerIp(): String? {
        // Get the server IP from the current connection config
        // You'll need to store this when connecting
        return currentServerIp // Add this as a class property
    }

    suspend fun getConnectionLogs(context: Context): String? {
        return try {
            // Try direct file access first
            val logFile = File(context.filesDir, "charon.log")
            if (logFile.exists()) {
                logFile.readText()
            } else {
                // Fallback to content provider if file doesn't exist
                val uri = LogContentProvider.createContentUri()
                if (uri != null) {
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        inputStream.bufferedReader().readText()
                    }
                } else {
                    "No logs available - log file not found"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading logs", e)
            "Error reading logs: ${e.localizedMessage}"
        }
    }

}