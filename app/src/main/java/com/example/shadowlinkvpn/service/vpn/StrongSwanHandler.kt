// app/src/main/java/com/example/shadowlinkvpn/service/vpn/StrongSwanHandler.kt
package com.example.shadowlinkvpn.service.vpn
// Add / replace imports at top of StrongSwanHandler.kt
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.strongswan.android.logic.CharonVpnService
import java.io.File
import android.net.VpnService
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
            val certImported = installCertificatesIntoManagedStore(context, swanConfig)
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

    /**
     * Install client .p12 and server cert into StrongSwan managed stores via reflection.
     * This uses several method-name fallbacks so it likely works with small API diffs.
     */
    private fun installCertificatesIntoManagedStore(context: Context, cfg: StrongSwanConnectionConfig): Boolean {
        try {
            val decodedP12 = Base64.decode(cfg.clientP12, Base64.DEFAULT)
            val decodedCert = Base64.decode(cfg.serverCert, Base64.DEFAULT)

            // ---- ManagedUserCertificateInstaller (user cert) ----
            try {
                val cls = Class.forName("org.strongswan.android.logic.ManagedUserCertificateInstaller")
                val instance = cls.getConstructor(Context::class.java).newInstance(context)

                // try various possible method signatures
                var installed = false
                try {
                    val m = cls.getMethod("install", ByteArray::class.java, String::class.java, String::class.java)
                    m.invoke(instance, decodedP12, cfg.clientPassword, cfg.uuid)
                    installed = true
                } catch (_: NoSuchMethodException) { /* try next */ }

                if (!installed) {
                    try {
                        val m = cls.getMethod("install", ByteArray::class.java, String::class.java)
                        m.invoke(instance, decodedP12, cfg.clientPassword)
                        installed = true
                    } catch (_: NoSuchMethodException) { /* try next */ }
                }

                if (!installed) {
                    try {
                        val m = cls.getMethod("installP12", ByteArray::class.java, String::class.java, String::class.java)
                        m.invoke(instance, decodedP12, cfg.clientPassword, cfg.uuid)
                        installed = true
                    } catch (_: NoSuchMethodException) { /* no known signature */ }
                }

                if (!installed) {
                    Log.w(TAG, "ManagedUserCertificateInstaller: no known install method found")
                    return false
                }
            } catch (e: ClassNotFoundException) {
                Log.w(TAG, "ManagedUserCertificateInstaller class not found: ${e.message}")
                return false
            }

            // ---- ManagedTrustedCertificateManager (CA/server cert) ----
            try {
                val cls = Class.forName("org.strongswan.android.logic.ManagedTrustedCertificateManager")
                val instance = cls.getConstructor(Context::class.java).newInstance(context)

                var installed = false
                try {
                    val m = cls.getMethod("install", ByteArray::class.java, String::class.java)
                    m.invoke(instance, decodedCert, cfg.uuid + "_ca")
                    installed = true
                } catch (_: NoSuchMethodException) {}

                if (!installed) {
                    try {
                        val m = cls.getMethod("install", ByteArray::class.java)
                        m.invoke(instance, decodedCert)
                        installed = true
                    } catch (_: NoSuchMethodException) {}
                }

                if (!installed) {
                    Log.w(TAG, "ManagedTrustedCertificateManager: no known install method found")
                    return false
                }
            } catch (e: ClassNotFoundException) {
                Log.w(TAG, "ManagedTrustedCertificateManager class not found: ${e.message}")
                return false
            }

            Log.d(TAG, "Certificates installed into StrongSwan managed stores")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "installCertificatesIntoManagedStore failed", e)
            return false
        }
    }


    /**
     * Create a VpnProfile instance via reflection, populate some common fields and persist it
     * using VpnProfileDataSource (reflection used for compatibility).
     *
     * Returns the profile object (Any) or null on failure.
     */
    private fun createAndPersistProfile(context: Context, cfg: StrongSwanConnectionConfig): Any? {
        try {
            val profileClass = Class.forName("org.strongswan.android.data.VpnProfile")
            val profile = profileClass.getDeclaredConstructor().newInstance()

            // helper to call setter or set field
            fun setStringFieldOrSetter(instance: Any, propName: String, value: String?) {
                if (value == null) return
                val cls = instance::class.java
                val setter = "set" + propName.replaceFirstChar { it.uppercase() }
                try {
                    val m = cls.getMethod(setter, String::class.java)
                    m.invoke(instance, value)
                    return
                } catch (_: NoSuchMethodException) { /* try field */ }
                try {
                    val f = cls.getDeclaredField(propName)
                    f.isAccessible = true
                    f.set(instance, value)
                    return
                } catch (_: NoSuchFieldException) { /* give up silently */ }
            }

            // set the common profile properties (names tuned to common strongSwan versions)
            setStringFieldOrSetter(profile, "uuid", cfg.uuid)
            setStringFieldOrSetter(profile, "name", cfg.name)
            setStringFieldOrSetter(profile, "gatewayAddress", cfg.serverAddress)
            setStringFieldOrSetter(profile, "remoteId", cfg.serverId)
            setStringFieldOrSetter(profile, "userCertificate", cfg.uuid) // alias used during install
            setStringFieldOrSetter(profile, "trustedCertificate", cfg.uuid + "_ca")
            setStringFieldOrSetter(profile, "dnsServers", cfg.dnsServers.joinToString(","))

            // try to set IKE/ESP strings if available
            setStringFieldOrSetter(profile, "ike", cfg.ike)
            setStringFieldOrSetter(profile, "esp", cfg.esp)

            // try to set vpnType enum -> lookup an enum constant that likely exists
            try {
                val vpnTypeClass = Class.forName("org.strongswan.android.data.VpnType")
                val desired = try {
                    // Prefer certificate-based names; try the most common constants
                    when {
                        cfg.type.contains("cert", true) -> vpnTypeClass.getField("IKEV2_CERT").get(null)
                        cfg.type.contains("eap", true) -> vpnTypeClass.getField("IKEV2_EAP").get(null)
                        else -> vpnTypeClass.getField("IKEV2_CERT").get(null)
                    }
                } catch (e: Exception) {
                    // fallback to first enum constant
                    vpnTypeClass.getEnumConstants().firstOrNull()
                }
                if (desired != null) {
                    // try setter setVpnType(...) or field vpnType
                    try {
                        val m = profile::class.java.getMethod("setVpnType", vpnTypeClass)
                        m.invoke(profile, desired)
                    } catch (_: NoSuchMethodException) {
                        try {
                            val f = profile::class.java.getDeclaredField("vpnType")
                            f.isAccessible = true
                            f.set(profile, desired)
                        } catch (_: Exception) { /* ignore */ }
                    }
                }
            } catch (e: ClassNotFoundException) {
                // no VpnType enum available — ignore
            }

            // persist profile with VpnProfileDataSource
            try {
                val dsClass = Class.forName("org.strongswan.android.data.VpnProfileDataSource")
                val ds = dsClass.getConstructor(Context::class.java).newInstance(context)
                // open if exists
                try { dsClass.getMethod("open").invoke(ds) } catch (_: NoSuchMethodException) {}
                var persisted = false
                val tryMethods = arrayOf("insertProfile", "addProfile", "createProfile", "save")
                for (mname in tryMethods) {
                    try {
                        val m = dsClass.getMethod(mname, profileClass)
                        m.invoke(ds, profile)
                        persisted = true
                        break
                    } catch (_: NoSuchMethodException) { /* try next */ }
                }
                try { dsClass.getMethod("close").invoke(ds) } catch (_: NoSuchMethodException) {}
                if (!persisted) {
                    Log.w(TAG, "VpnProfileDataSource: could not find a known insert method; profile not persisted")
                } else {
                    Log.d(TAG, "VpnProfile persisted (uuid=${cfg.uuid})")
                }

                return profile
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist VpnProfile reflectively", e)
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "createAndPersistProfile failed", e)
            return null
        }
    }


    private fun startStrongSwanConnection(context: Context, profileObj: Any): Boolean {
        return try {
            // extract uuid via getter or field
            var uuid: String? = null
            try {
                val m = profileObj::class.java.getMethod("getUuid")
                uuid = m.invoke(profileObj) as? String
            } catch (_: NoSuchMethodException) {}
            if (uuid == null) {
                try {
                    val f = profileObj::class.java.getDeclaredField("uuid")
                    f.isAccessible = true
                    uuid = f.get(profileObj) as? String
                } catch (_: Exception) {}
            }
            if (uuid == null) {
                Log.w(TAG, "Could not extract profile UUID; aborting start")
                return false
            }

            // start service — use CharonVpnService class you already have imported
            val intent = Intent(context, CharonVpnService::class.java).apply {
                // action string used by many official builds
                action = "org.strongswan.android.action.START_VPN"
                putExtra("VPN_PROFILE_UUID", uuid)
                // compatibility extras (some builds read different extras)
                putExtra("org.strongswan.android.intent.extra.PROFILE_UUID", uuid)
            }

            // On modern Android, startForegroundService might be required, but CharonVpnService is inside the same app and will handle start.
            context.startService(intent)
            Log.d(TAG, "Requested CharonVpnService start for profile $uuid")
            true
        } catch (e: Exception) {
            Log.e(TAG, "startStrongSwanConnection failed", e)
            false
        }
    }


    private fun checkStrongSwanLogs(context: Context): Boolean {
        return try {
            val uriClass = try { Class.forName("org.strongswan.android.data.LogContentProvider") } catch (_: Exception) { null }
            if (uriClass != null) {
                try {
                    val contentUriField = uriClass.getDeclaredField("CONTENT_URI")
                    contentUriField.isAccessible = true
                    val contentUri = contentUriField.get(null) as? android.net.Uri
                    if (contentUri != null) {
                        val cursor = context.contentResolver.query(contentUri, null, null, null, null)
                        cursor?.use {
                            val msgIdx = try { it.getColumnIndex("message") } catch(_: Exception) { -1 }
                            while (it.moveToNext()) {
                                if (msgIdx >= 0) {
                                    val msg = it.getString(msgIdx) ?: ""
                                    if (msg.contains("AUTH_FAILED", true) || msg.contains("certificate", true) || msg.contains("no suitable certificate", true)) {
                                        Log.w(TAG, "StrongSwan log detected: $msg")
                                        return true
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "checkStrongSwanLogs reflection/query failed", e)
                }
            }
            false
        } catch (e: Exception) {
            Log.w(TAG, "checkStrongSwanLogs failed", e)
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
                val errorDetected = checkStrongSwanLogs(context)
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