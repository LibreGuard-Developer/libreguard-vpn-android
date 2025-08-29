package com.example.shadowlinkvpn.util

import android.content.Context
import android.content.Intent
import android.security.KeyChain
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.strongswan.android.data.VpnProfile
import org.strongswan.android.data.VpnType
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.*
import java.io.ByteArrayInputStream
import java.io.FileOutputStream
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class VpnConfigManager(private val context: Context) {

    private val tag = "VpnConfigManager"
    private val configDir: File = File(context.filesDir, "sswan_configs")

    // Marker directory for pending installs
    private val pendingDir: File by lazy {
        File(context.cacheDir, "p12_pending").apply { if (!exists()) mkdirs() }
    }

    private val prefs: SharedPreferences by lazy { context.getSharedPreferences("vpn_cert_prefs", Context.MODE_PRIVATE) }

    init {
        if (!configDir.exists()) {
            configDir.mkdirs()
        }
        initializeSecurityProviders()
        ensureStrongSwanDatabaseSchema()
    }

    /**
     * Ensure StrongSwan database has the correct schema with uuid column
     */
    private fun ensureStrongSwanDatabaseSchema() {
        try {
            // Check if the database exists and has the correct schema
            val dbPath = File(context.getDatabasePath("strongswan.db").absolutePath)
            if (!dbPath.exists()) {
                Log.d(tag, "StrongSwan database doesn't exist, will be created by StrongSwan on first use")
                return
            }

            val db = SQLiteDatabase.openDatabase(dbPath.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)

            // Check if uuid column exists
            val cursor = db.rawQuery("PRAGMA table_info(vpnprofile)", null)
            var hasUuidColumn = false

            if (cursor.moveToFirst()) {
                do {
                    val columnName = cursor.getString(cursor.getColumnIndex("name"))
                    if (columnName == "uuid") {
                        hasUuidColumn = true
                        break
                    }
                } while (cursor.moveToNext())
            }
            cursor.close()

            if (!hasUuidColumn) {
                Log.i(tag, "Adding missing uuid column to vpnprofile table")
                try {
                    // Add the uuid column if it doesn't exist
                    db.execSQL("ALTER TABLE vpnprofile ADD COLUMN uuid TEXT")

                    // Update existing rows with UUIDs if any exist
                    val updateCursor = db.rawQuery("SELECT _id FROM vpnprofile WHERE uuid IS NULL", null)
                    if (updateCursor.moveToFirst()) {
                        do {
                            val id = updateCursor.getLong(0)
                            val uuid = UUID.randomUUID().toString()
                            db.execSQL("UPDATE vpnprofile SET uuid = ? WHERE _id = ?", arrayOf(uuid, id.toString()))
                        } while (updateCursor.moveToNext())
                    }
                    updateCursor.close()

                    Log.i(tag, "Successfully added uuid column to vpnprofile table")
                } catch (e: Exception) {
                    Log.e(tag, "Failed to add uuid column to vpnprofile table", e)
                }
            } else {
                Log.d(tag, "StrongSwan database schema is correct (uuid column exists)")
            }

            db.close()

        } catch (e: Exception) {
            Log.w(tag, "Could not check/update StrongSwan database schema", e)
            // If we can't modify the database, delete it so StrongSwan recreates it
            try {
                val dbPath = context.getDatabasePath("strongswan.db")
                if (dbPath.exists()) {
                    dbPath.delete()
                    Log.i(tag, "Deleted corrupted StrongSwan database, will be recreated")
                }
            } catch (deleteError: Exception) {
                Log.e(tag, "Could not delete corrupted database", deleteError)
            }
        }
    }

    /**
     * Initialize security providers to handle P12 certificates properly
     */
    private fun initializeSecurityProviders() {
        try {
            val providers = java.security.Security.getProviders()
            Log.d(tag, "Available security providers:")
            providers.forEach { provider ->
                Log.d(tag, "Provider: ${provider.name} - ${provider.info}")
            }

            try {
                val testStore = KeyStore.getInstance("PKCS12")
                Log.d(tag, "PKCS12 KeyStore provider available: ${testStore.provider.name}")
            } catch (e: Exception) {
                Log.w(tag, "PKCS12 KeyStore not available: ${e.message}")
            }

        } catch (e: Exception) {
            Log.e(tag, "Failed to initialize security providers", e)
        }
    }

    fun parseServerResponseToProfile(responseBody: String): VpnProfile? {
        return try {
            val rawTrimmed = responseBody.trimStart()
            if (rawTrimmed.startsWith("conn ")) {
                Log.d(tag, "Input looks like strongSwan .conf, skipping JSON parse")
                return null
            }
            if (!rawTrimmed.startsWith("{") && !rawTrimmed.startsWith("[")) {
                Log.w(tag, "Input does not look like JSON, skipping parse")
                return null
            }
            var raw = rawTrimmed
            var jsonObj: JSONObject? = null
            try {
                jsonObj = JSONObject(raw)
            } catch (e: Exception) {
                Log.w(tag, "Initial JSON parse failed, attempting auto-repair: ${e.message}")
                val repaired = autoRepairJson(raw)
                if (repaired != raw) {
                    Log.d(tag, "Repaired JSON diff length: ${repaired.length - raw.length}")
                }
                jsonObj = JSONObject(repaired)
            }
            createVpnProfile(jsonObj!!)
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse server response to profile", e)
            null
        }
    }

    fun createVpnProfile(json: JSONObject): VpnProfile? {
        return try {
            val profile = VpnProfile()
            Log.d(tag, "JSON keys: ${json.keys().asSequence().toList()}")
            Log.d(tag, "Raw JSON (normalized): ${json.toString(2)}")

            profile.name = json.optString("name", "ShadowLink VPN")
            profile.gateway = json.optString("gateway")

            if (profile.gateway.isBlank()) {
                val remoteObj = json.optJSONObject("remote")
                if (remoteObj != null) {
                    profile.gateway = remoteObj.optString("addr", remoteObj.optString("address"))
                }
                if (profile.gateway.isBlank()) {
                    profile.gateway = json.optString("server", json.optString("host", json.optString("endpoint")))
                }
                Log.i(tag, "Gateway resolved to: ${profile.gateway}")
            }
            if (profile.gateway.isBlank()) {
                profile.gateway = "10.0.2.2"
                Log.w(tag, "Gateway still empty, using default: ${profile.gateway}")
            }

            val vpnTypeString = json.optString("type", "ikev2-eap")
            profile.vpnType = when (vpnTypeString.lowercase()) {
                "ikev2-eap" -> VpnType.IKEV2_EAP
                "ikev2-cert" -> VpnType.IKEV2_CERT
                "ikev2-eap-tls" -> VpnType.IKEV2_EAP_TLS
                "ikev2-cert-eap" -> VpnType.IKEV2_CERT_EAP
                else -> VpnType.IKEV2_EAP
            }
            Log.d(tag, "VPN Type from JSON: $vpnTypeString -> ${profile.vpnType}")

            if (profile.vpnType != VpnType.IKEV2_EAP_TLS && profile.vpnType.has(VpnType.VpnTypeFeature.USER_PASS)) {
                profile.username = json.optString("username")
                profile.password = json.optString("password")
                if (profile.username.isNullOrBlank()) {
                    json.optJSONObject("local")?.let { local ->
                        profile.username = local.optString("id", local.optString("identity"))
                    }
                }
            } else {
                Log.d(tag, "Certificate-based auth selected; skipping direct username/password unless provided for hybrid")
            }

            profile.remoteId = json.optString("remoteId", profile.gateway)
            json.optJSONObject("remote")?.let { remoteObj ->
                remoteObj.optString("id").takeIf { it.isNotBlank() }?.let { profile.remoteId = it }
                remoteObj.optString("ike").takeIf { it.isNotBlank() }?.let { profile.ikeProposal = it }
                remoteObj.optString("esp").takeIf { it.isNotBlank() }?.let { profile.espProposal = it }
            }

            json.optJSONArray("dns-servers")?.let { arr ->
                profile.dnsServers = jsonArrayToSpaceSeparated(arr)
            }

            json.optJSONObject("split-tunneling")?.let { st ->
                var flags = 0
                if (st.optBoolean("block-ipv4", false)) flags = flags or VpnProfile.SPLIT_TUNNELING_BLOCK_IPV4
                if (st.optBoolean("block-ipv6", false)) flags = flags or VpnProfile.SPLIT_TUNNELING_BLOCK_IPV6
                if (flags != 0) profile.splitTunneling = flags
            }

            // Certificates (client) - AUTOMATIC P12 CERTIFICATE IMPORT
            json.optJSONObject("local")?.let { localObj ->
                val p12Base64 = localObj.optString("p12")
                val p12Password = localObj.optString("password")
                if (p12Base64.isNotBlank()) {
                    val certAlias = importP12CertificateAutomatically(p12Base64, p12Password)
                    if (certAlias != null) {
                        profile.userCertificateAlias = certAlias

                        if (profile.vpnType == VpnType.IKEV2_EAP_TLS && p12Password.isNotBlank()) {
                            profile.password = p12Password
                            Log.d(tag, "Set P12 password as profile password for certificate-based auth")
                        }
                        if (profile.vpnType == VpnType.IKEV2_EAP) {
                            profile.vpnType = VpnType.IKEV2_CERT
                        }
                        Log.d(tag, "Successfully imported P12 certificate with alias: $certAlias")
                    } else {
                        Log.e(tag, "Failed to import P12 certificate automatically")
                        if (profile.vpnType == VpnType.IKEV2_EAP_TLS) {
                            throw Exception("Client certificate required but import failed")
                        }
                    }
                }
            }

            // Server certificate / trust anchor
            json.optJSONObject("remote")?.optString("cert")?.let { serverCert ->
                if (serverCert.isNotBlank()) {
                    importServerCertificateToFiles(serverCert)?.let { caAlias ->
                        profile.certificateAlias = caAlias
                    }
                }
            }

            profile
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse server response", e)
            null
        }
    }

    /**
     * Import P12 certificate automatically into Android KeyChain
     * Enhanced to handle problematic P12 encryption by bypassing system KeyChain and using direct certificate handling
     */
    private fun importP12CertificateAutomatically(p12Base64: String, password: String): String? {
        return try {
            Log.d(tag, "Starting P12 certificate import to Android KeyChain (simplified path)")
            Log.d(tag, "Using password from JSON: ${if (password.isEmpty()) "[empty]" else "[***]"}")
            val cleanBase64 = p12Base64.replace("\\s".toRegex(), "")
            // Single decoding strategy to avoid accidental mis-decoding with URL_SAFE altering bytes
            val p12Bytes = try {
                Base64.decode(cleanBase64, Base64.DEFAULT)
            } catch (e: Exception) {
                Log.e(tag, "Base64 decode failed (DEFAULT): ${e.message}")
                return null
            }
            if (p12Bytes.isEmpty()) {
                Log.e(tag, "Decoded P12 bytes empty")
                return null
            }
            val size = p12Bytes.size
            val sha256 = try { java.security.MessageDigest.getInstance("SHA-256").digest(p12Bytes).joinToString("") { String.format("%02x", it) } } catch (e: Exception) { "n/a" }
            Log.d(tag, "P12 decoded size=$size bytes sha256=$sha256 head=${p12Bytes.take(8).joinToString(" ") { String.format("%02X", it) }}")

            val alias = "shadowlink_${System.currentTimeMillis()}"
            // Persist raw P12 for potential strongSwan direct consumption (future) + password file
            saveP12File(alias, p12Bytes)
            saveP12Password(alias, password)
            Log.d(tag, "Saved raw P12 + password for alias $alias")

            // Skip pre-extraction attempts that were failing; rely on KeyChain import to populate system keystore
            val intent = createAndPersistInstallIntent(alias, p12Bytes, password)
            if (intent == null) {
                Log.e(tag, "Failed to create KeyChain install intent")
                return null
            }
            Log.d(tag, "Prepared KeyChain install intent for alias $alias (user action required)")
            alias
        } catch (e: Exception) {
            Log.e(tag, "Failed to import P12 certificate (simplified path)", e)
            null
        }
    }

    /**
     * Create and persist a KeyChain install intent for later launching by UI
     */
    private fun createAndPersistInstallIntent(alias: String, p12Bytes: ByteArray, password: String) : Intent? {
        return try {
            val intent = KeyChain.createInstallIntent().apply {
                putExtra(KeyChain.EXTRA_PKCS12, p12Bytes)
                putExtra(KeyChain.EXTRA_NAME, alias)
            }
            // Persist raw P12 + meta so we can recreate intent if process killed
            val meta = File(pendingDir, "$alias.meta")
            meta.writeText("{\"alias\":\"$alias\",\"password\":\"${password.replace("\"","\\\"")}\"}")
            val p12File = File(pendingDir, "$alias.p12")
            p12File.writeBytes(p12Bytes)
            intent
        } catch (e: Exception) {
            Log.e(tag, "Failed to create/persist install intent", e)
            null
        }
    }

    fun getStoredInstallIntent(alias: String): Intent? {
        return try {
            val p12File = File(pendingDir, "$alias.p12")
            val meta = File(pendingDir, "$alias.meta")
            if (!p12File.exists() || !meta.exists()) return null
            val p12Bytes = p12File.readBytes()
            KeyChain.createInstallIntent().apply {
                putExtra(KeyChain.EXTRA_PKCS12, p12Bytes)
                putExtra(KeyChain.EXTRA_NAME, alias)
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to recreate install intent", e)
            null
        }
    }

    fun clearPendingInstall(alias: String) {
        File(pendingDir, "$alias.p12").delete()
        File(pendingDir, "$alias.meta").delete()
    }

    fun isUserCertificateInstalled(alias: String?): Boolean {
        if (alias.isNullOrBlank()) return false
        return try {
            // KeyChain calls must be off main thread by caller; here just best-effort
            val chain = KeyChain.getCertificateChain(context, alias)
            chain != null && chain.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Enhanced P12 extraction that handles Android encryption incompatibilities
     */
    private fun extractCertificateAndKeyFromP12Direct(p12Bytes: ByteArray, actualPassword: String): Pair<X509Certificate, PrivateKey>? {
        val passwords = listOf(actualPassword, "", "changeit", "password").distinct()

        // Try basic Android KeyStore first
        for (pass in passwords) {
            try {
                Log.d(tag, "Trying Android KeyStore with password: ${if (pass.isEmpty()) "[empty]" else "[***]"}")

                val keyStore = KeyStore.getInstance("PKCS12")
                keyStore.load(ByteArrayInputStream(p12Bytes), pass.toCharArray())

                val result = extractCertificatesFromKeyStore(keyStore, pass)
                if (result != null) {
                    return result
                }
            } catch (e: Exception) {
                Log.v(tag, "Android KeyStore failed with password '$pass': ${e.message}")
            }
        }

        // Try alternative PKCS12 parsing approaches
        val altResult = tryAlternativeP12Methods(p12Bytes, passwords)
        if (altResult != null) {
            return altResult
        }

        Log.w(tag, "All P12 extraction methods failed - strongSwan will need to handle P12 directly")
        Log.d(tag, "This is expected for newer P12 files with incompatible encryption")

        return null
    }

    /**
     * Try alternative methods for P12 parsing when standard approaches fail
     */
    private fun tryAlternativeP12Methods(p12Bytes: ByteArray, passwords: List<String>): Pair<X509Certificate, PrivateKey>? {
        // Method 1: Try with different KeyStore instances
        for (pass in passwords) {
            try {
                Log.d(tag, "Trying alternative KeyStore approach with password: ${if (pass.isEmpty()) "[empty]" else "[***]"}")

                val keyStore = KeyStore.getInstance("PKCS12", "AndroidKeyStore")
                keyStore.load(ByteArrayInputStream(p12Bytes), pass.toCharArray())

                val result = extractCertificatesFromKeyStore(keyStore, pass)
                if (result != null) {
                    Log.d(tag, "Alternative KeyStore method succeeded")
                    return result
                }
            } catch (e: Exception) {
                Log.v(tag, "Alternative KeyStore method failed with password '$pass': ${e.message}")
            }
        }

        // Method 2: Try without specifying provider
        for (pass in passwords) {
            try {
                Log.d(tag, "Trying default provider with password: ${if (pass.isEmpty()) "[empty]" else "[***]"}")

                val keyStore = KeyStore.getInstance("PKCS12")
                val inputStream = ByteArrayInputStream(p12Bytes)
                keyStore.load(inputStream, pass.toCharArray())
                inputStream.close()

                val result = extractCertificatesFromKeyStore(keyStore, pass)
                if (result != null) {
                    Log.d(tag, "Default provider method succeeded")
                    return result
                }
            } catch (e: Exception) {
                Log.v(tag, "Default provider method failed with password '$pass': ${e.message}")
            }
        }

        return null
    }

    /**
     * Extract certificates from a loaded KeyStore
     */
    private fun extractCertificatesFromKeyStore(keyStore: KeyStore, password: String): Pair<X509Certificate, PrivateKey>? {
        val aliases = keyStore.aliases()
        var clientCert: X509Certificate? = null
        var clientKey: PrivateKey? = null
        var rootCert: X509Certificate? = null

        while (aliases.hasMoreElements()) {
            val alias = aliases.nextElement()
            Log.d(tag, "Found alias in P12: $alias")

            val cert = keyStore.getCertificate(alias) as? X509Certificate
            val key = try {
                keyStore.getKey(alias, password.toCharArray()) as? PrivateKey
            } catch (e: Exception) {
                Log.v(tag, "No private key for alias $alias: ${e.message}")
                null
            }

            if (cert != null) {
                Log.d(tag, "Certificate subject: ${cert.subjectDN}")
                Log.d(tag, "Certificate issuer: ${cert.issuerDN}")

                if (key != null) {
                    Log.d(tag, "Found client certificate with private key: ${cert.subjectDN}")
                    Log.d(tag, "Key algorithm: ${key.algorithm}")
                    clientCert = cert
                    clientKey = key
                } else {
                    Log.d(tag, "Found CA certificate (no private key): ${cert.subjectDN}")
                    if (cert.subjectDN == cert.issuerDN) {
                        Log.d(tag, "This appears to be a root CA certificate")
                        rootCert = cert
                    }
                }
            }
        }

        if (clientCert != null && clientKey != null) {
            Log.d(tag, "Successfully extracted client certificate and private key")
            Log.d(tag, "Client cert subject: ${clientCert.subjectDN}")
            Log.d(tag, "Root cert subject: ${rootCert?.subjectDN ?: "None found"}")

            if (rootCert != null) {
                saveRootCertificateFile(rootCert)
            }

            return Pair(clientCert, clientKey)
        }

        return null
    }

    /**
     * Save P12 password to a file for strongSwan to use
     */
    private fun saveP12Password(alias: String, password: String) {
        try {
            val passwordFile = File(configDir, "${alias}_password.txt")
            passwordFile.writeText(password)
            Log.d(tag, "Saved P12 password file: ${passwordFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(tag, "Failed to save P12 password file", e)
        }
    }

    /**
     * Save the root CA certificate separately for strongSwan to use for verification
     */
    private fun saveRootCertificateFile(rootCert: X509Certificate) {
        try {
            val alias = "shadowlink_root_ca_${System.currentTimeMillis()}"
            val certFile = File(configDir, "$alias.crt")
            val certPem = "-----BEGIN CERTIFICATE-----\n" +
                    Base64.encodeToString(rootCert.encoded, Base64.NO_WRAP).chunked(64).joinToString("\n") +
                    "\n-----END CERTIFICATE-----\n"
            certFile.writeText(certPem)

            Log.d(tag, "Root CA certificate saved to: ${certFile.absolutePath}")
            Log.d(tag, "Root CA subject: ${rootCert.subjectDN}")
        } catch (e: Exception) {
            Log.e(tag, "Failed to save root CA certificate", e)
        }
    }

    private fun saveCertificateAndKeyFiles(alias: String, cert: X509Certificate, key: PrivateKey) {
        try {
            val certFile = File(configDir, "$alias.crt")
            val certPem = "-----BEGIN CERTIFICATE-----\n" +
                    Base64.encodeToString(cert.encoded, Base64.NO_WRAP).chunked(64).joinToString("\n") +
                    "\n-----END CERTIFICATE-----\n"
            certFile.writeText(certPem)

            val keyFile = File(configDir, "$alias.key")
            val keyPem = "-----BEGIN PRIVATE KEY-----\n" +
                    Base64.encodeToString(key.encoded, Base64.NO_WRAP).chunked(64).joinToString("\n") +
                    "\n-----END PRIVATE KEY-----\n"
            keyFile.writeText(keyPem)

            Log.d(tag, "Certificate saved to: ${certFile.absolutePath}")
            Log.d(tag, "Private key saved to: ${keyFile.absolutePath}")

        } catch (e: Exception) {
            Log.e(tag, "Failed to save certificate and key files", e)
        }
    }

    private fun saveP12File(alias: String, p12Bytes: ByteArray) {
        try {
            val p12File = File(configDir, "$alias.p12")
            FileOutputStream(p12File).use { fos ->
                fos.write(p12Bytes)
            }
            Log.d(tag, "P12 file saved to: ${p12File.absolutePath}")
        } catch (e: Exception) {
            Log.e(tag, "Failed to save P12 file", e)
        }
    }

    /**
     * Clean up install intent data after successful installation
     */
    fun cleanupInstallIntentData(alias: String) {
        try {
            listOf(
                "${alias}.p12",
                "${alias}_password.txt",
                "${alias}_intent.json"
            ).forEach { filename ->
                val file = File(context.cacheDir, filename)
                if (file.exists()) {
                    file.delete()
                    Log.d(tag, "Cleaned up file: $filename")
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to cleanup install intent data", e)
        }
    }

    /**
     * Clean up old configuration files
     */
    fun cleanupOldConfigs() {
        try {
            configDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(".conf")) {
                    file.delete()
                    Log.d(tag, "Deleted old config: ${file.name}")
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to cleanup old configs", e)
        }
    }

    // -------- Certificate Alias Mapping (user-chosen) --------
    private fun buildAliasKey(gateway: String?, remoteId: String?): String? {
        val g = gateway?.takeIf { it.isNotBlank() }
        val r = remoteId?.takeIf { it.isNotBlank() }
        if (g == null && r == null) return null
        return listOfNotNull(g, r).joinToString("|") { it.trim() }
    }

    fun getMappedCertAlias(gateway: String?, remoteId: String?): String? {
        val key = buildAliasKey(gateway, remoteId) ?: return null
        return prefs.getString("cert_alias_$key", null)
    }

    fun saveMappedCertAlias(gateway: String?, remoteId: String?, alias: String) {
        val key = buildAliasKey(gateway, remoteId) ?: return
        prefs.edit().putString("cert_alias_$key", alias).apply()
        Log.d(tag, "Saved cert alias mapping key=$key alias=$alias")
    }

    private fun autoRepairJson(input: String): String {
        var repaired = input
        repaired = repaired.replace(Regex("(\"cert\"\\s*:\\s*\"[^\"]+\")\\s*(\"(ike|esp)\")"), "\$1, \$2")
        repaired = repaired.replace(Regex("(\"p12\"\\s*:\\s*\"[^\"]+?)(\\s+\"password\")"), "\$1\", \"password\"")
        repaired = repaired.replace(Regex("}(\\s*)(\"[a-zA-Z0-9_]+\"\\s*:)"), "},\$1\$2")
        return repaired
    }

    private fun jsonArrayToSpaceSeparated(array: JSONArray): String {
        val list = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val v = array.optString(i)
            if (!v.isNullOrBlank()) list.add(v)
        }
        return list.joinToString(" ")
    }

    private fun importServerCertificateToFiles(certBase64: String): String? {
        return try {
            Log.d(tag, "Starting server certificate import to files")

            val cleanBase64 = cleanBase64String(certBase64)
            val certBytes = tryMultipleBase64Decoding(cleanBase64) ?: return null

            val cert = parseServerCertificate(certBytes) ?: return null

            val alias = "shadowlink_ca_${System.currentTimeMillis()}"

            val certFile = File(configDir, "$alias.crt")
            val certPem = "-----BEGIN CERTIFICATE-----\n" +
                    Base64.encodeToString(cert.encoded, Base64.NO_WRAP).chunked(64).joinToString("\n") +
                    "\n-----END CERTIFICATE-----\n"
            certFile.writeText(certPem)

            Log.d(tag, "Server certificate saved to: ${certFile.absolutePath}")
            alias
        } catch (e: Exception) {
            Log.e(tag, "Failed to import server certificate", e)
            null
        }
    }

    private fun parseServerCertificate(certBytes: ByteArray): X509Certificate? {
        val approaches = listOf(
            { parseCertWithDefaultFactory(certBytes) },
            { parseCertWithPEMWrapper(certBytes) },
            { parseCertWithDERFormat(certBytes) }
        )

        for (approach in approaches) {
            try {
                val cert = approach()
                if (cert != null) {
                    Log.d(tag, "Successfully parsed certificate")
                    return cert
                }
            } catch (e: Exception) {
                Log.v(tag, "Certificate parsing approach failed: ${e.message}")
            }
        }
        return null
    }

    private fun parseCertWithDefaultFactory(certBytes: ByteArray): X509Certificate? {
        return try {
            val factory = CertificateFactory.getInstance("X.509")
            factory.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
        } catch (e: Exception) {
            null
        }
    }

    private fun parseCertWithPEMWrapper(certBytes: ByteArray): X509Certificate? {
        return try {
            val certString = String(certBytes)
            val pemCert = if (!certString.contains("-----BEGIN CERTIFICATE-----")) {
                "-----BEGIN CERTIFICATE-----\n" + certString + "\n-----END CERTIFICATE-----"
            } else {
                certString
            }

            val factory = CertificateFactory.getInstance("X.509")
            factory.generateCertificate(ByteArrayInputStream(pemCert.toByteArray())) as X509Certificate
        } catch (e: Exception) {
            null
        }
    }

    private fun parseCertWithDERFormat(certBytes: ByteArray): X509Certificate? {
        return try {
            val factory = CertificateFactory.getInstance("X.509")
            factory.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
        } catch (e: Exception) {
            null
        }
    }

    private fun cleanBase64String(base64: String): String {
        return base64.replace("\\s".toRegex(), "")
            .replace("-----BEGIN CERTIFICATE-----", "")
            .replace("-----END CERTIFICATE-----", "")
            .replace("-----BEGIN PKCS12-----", "")
            .replace("-----END PKCS12-----", "")
            .replace("\n", "")
            .replace("\r", "")
    }

    private fun tryMultipleBase64Decoding(base64String: String): ByteArray? {
        // Retained for server certificate parsing; unchanged
        val flags = listOf(
            Base64.DEFAULT,
            Base64.NO_WRAP,
            Base64.URL_SAFE,
            Base64.NO_PADDING
        )
        for (flag in flags) {
            try {
                val decoded = Base64.decode(base64String, flag)
                Log.d(tag, "Successfully decoded Base64 with flag: $flag")
                return decoded
            } catch (e: Exception) {
                Log.v(tag, "Base64 decoding failed with flag $flag: ${e.message}")
            }
        }
        return null
    }

    enum class UserCertState { NOT_IMPORTED, PENDING_INSTALL, INSTALLED_OK, INSTALLED_NO_KEY }

    data class UserCertDiagnostics(
        val alias: String?,
        val state: UserCertState,
        val chainSize: Int = 0,
        val subject0: String? = null,
        val hasPrivateKey: Boolean = false,
        val notes: String = ""
    )

    fun diagnoseUserCertificate(alias: String?): UserCertDiagnostics {
        if (alias.isNullOrBlank()) return UserCertDiagnostics(alias, UserCertState.NOT_IMPORTED, notes = "Alias null/blank")
        // Pending files existing?
        val pending = File(pendingDir, "$alias.p12").exists()
        return try {
            val chain = KeyChain.getCertificateChain(context, alias)
            val key = try { KeyChain.getPrivateKey(context, alias) } catch (e: Exception) { null }
            if (chain == null || chain.isEmpty()) {
                if (pending) UserCertDiagnostics(alias, UserCertState.PENDING_INSTALL, notes = "KeyChain chain empty; pending files exist")
                else UserCertDiagnostics(alias, UserCertState.PENDING_INSTALL, notes = "KeyChain chain empty; no pending files but maybe not installed")
            } else {
                val hasKey = key != null
                UserCertDiagnostics(
                    alias,
                    if (hasKey) UserCertState.INSTALLED_OK else UserCertState.INSTALLED_NO_KEY,
                    chainSize = chain.size,
                    subject0 = try { chain[0].subjectX500Principal.name } catch (e: Exception) { null },
                    hasPrivateKey = hasKey,
                    notes = if (hasKey) "Certificate + key accessible" else "Private key not accessible"
                )
            }
        } catch (e: Exception) {
            UserCertDiagnostics(alias, if (pending) UserCertState.PENDING_INSTALL else UserCertState.PENDING_INSTALL, notes = "Exception: ${e.javaClass.simpleName}:${e.message}")
        }
    }

    fun logUserCertDiagnostics(tag: String, alias: String?) {
        val d = diagnoseUserCertificate(alias)
        Log.d(tag, "UserCertDiag alias=${d.alias} state=${d.state} chainSize=${d.chainSize} subject0=${d.subject0} hasKey=${d.hasPrivateKey} notes=${d.notes}")
    }

    fun needsUserCertInstallation(alias: String?): Boolean {
        val d = diagnoseUserCertificate(alias)
        return when (d.state) {
            UserCertState.PENDING_INSTALL, UserCertState.NOT_IMPORTED -> true
            UserCertState.INSTALLED_NO_KEY -> true // key missing
            UserCertState.INSTALLED_OK -> false
        }
    }

    fun getInstallIntentIfPending(alias: String?): Intent? {
        if (alias.isNullOrBlank()) return null
        if (!needsUserCertInstallation(alias)) return null
        return getStoredInstallIntent(alias)
    }
}
