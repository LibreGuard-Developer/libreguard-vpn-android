package net.libreguard.vpn.util

import android.content.Context
import android.content.Intent
import android.security.KeyChain
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.strongswan.android.data.VpnProfile
import org.strongswan.android.data.VpnType
import org.strongswan.android.logic.TrustedCertificateManager
import org.strongswan.android.security.LocalCertificateStore
import org.strongswan.android.security.LocalCertificateKeyStoreManager
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Provider
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.*
import java.io.ByteArrayInputStream
import java.io.FileOutputStream
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import net.libreguard.vpn.R
import net.libreguard.vpn.service.vpn.InternalDnsPolicy
import net.libreguard.vpn.util.CrashlyticsReporter

class VpnConfigManager(private val context: Context) {

    private val tag = "VpnConfigManager"
    private val configDir: File = File(context.filesDir, "sswan_configs")

    // Marker directory for pending installs
    private val pendingDir: File by lazy {
        File(context.cacheDir, "p12_pending").apply { if (!exists()) mkdirs() }
    }

    // StrongSwan CA certificates directory
    private val cacertsDir: File by lazy {
        File(context.filesDir, "cacerts").apply {
            if (!exists()) {
                mkdirs()
                Log.d(tag, "Created cacerts directory: $absolutePath")
            }
        }
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("vpn_cert_prefs", Context.MODE_PRIVATE)
    }

    // LocalCertificateKeyStoreManager for one-click certificate handling
    private val localCertManager: LocalCertificateKeyStoreManager by lazy {
        LocalCertificateKeyStoreManager(context)
    }

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
            val dbPath = File(context.getDatabasePath("strongswan.db").absolutePath)
            if (!dbPath.exists()) {
                Log.d(tag, "StrongSwan database doesn't exist, will be created by StrongSwan on first use")
                return
            }

            val db = SQLiteDatabase.openDatabase(dbPath.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)

            val cursor = db.rawQuery("PRAGMA table_info(vpnprofile)", null)
            var hasUuidColumn = false

            if (cursor.moveToFirst()) {
                do {
                    val nameIndex = cursor.getColumnIndex("name")
                    val columnName = if (nameIndex >= 0) cursor.getString(nameIndex) else null
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
                    db.execSQL("ALTER TABLE vpnprofile ADD COLUMN uuid TEXT")

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

            val jsonObj = try {
                JSONObject(rawTrimmed)
            } catch (e: Exception) {
                Log.w(tag, "Initial JSON parse failed, attempting auto-repair: ${e.message}")
                val repaired = autoRepairJson(rawTrimmed)
                JSONObject(repaired)
            }

            createVpnProfile(jsonObj)
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse server response to profile", e)
            null
        }
    }

    fun createVpnProfile(json: JSONObject): VpnProfile? {
        return try {
            val profile = VpnProfile()

            Log.i(tag, "=== HYBRID MODE: Let's Encrypt Server + Private CA Clients ===")
            Log.d(tag, "JSON keys: ${json.keys().asSequence().toList()}")

            profile.name = json.optString("name", "LibreGuard VPN")
            profile.gateway = json.optString("gateway")

            if (profile.gateway.isBlank()) {
                val remoteObj = json.optJSONObject("remote")
                if (remoteObj != null) {
                    profile.gateway = remoteObj.optString("addr", remoteObj.optString("address"))
                }
            }

            if (profile.gateway.isBlank()) {
                Log.e(tag, "Gateway is blank - cannot create profile")
                return null
            }

            val vpnTypeString = json.optString("type", "ikev2-cert")
            profile.vpnType = when (vpnTypeString.lowercase()) {
                "ikev2-eap" -> VpnType.IKEV2_EAP
                "ikev2-cert" -> VpnType.IKEV2_CERT
                "ikev2-eap-tls" -> VpnType.IKEV2_EAP_TLS
                "ikev2-cert-eap" -> VpnType.IKEV2_CERT_EAP
                else -> VpnType.IKEV2_CERT
            }

            Log.d(tag, "VPN Type: ${profile.vpnType}")
            Log.d(tag, "Gateway: ${profile.gateway}")

            // Remote configuration
            json.optJSONObject("remote")?.let { remoteObj ->
                remoteObj.optString("id").takeIf { it.isNotBlank() }?.let {
                    profile.remoteId = it
                    Log.d(tag, "Remote ID: ${profile.remoteId}")
                }
                remoteObj.optString("ike").takeIf { it.isNotBlank() }?.let { profile.ikeProposal = it }
                remoteObj.optString("esp").takeIf { it.isNotBlank() }?.let { profile.espProposal = it }

                // HYBRID MODE: Check if cert field is empty (hybrid mode indicator)
                val serverCACert = remoteObj.optString("cert")
                if (serverCACert.isNotBlank()) {
                    Log.i(tag, "Processing server CA certificate for validation...")
                    importLetsEncryptCACertificate(serverCACert)?.let { caAlias ->
                        profile.certificateAlias = caAlias
                        Log.i(tag, "✓ Server CA certificate imported: $caAlias")
                    }
                } else {
                    Log.i(tag, "=== HYBRID MODE DETECTED ===")
                    Log.i(tag, "No CA cert in config - server uses publicly trusted certificate (Let's Encrypt)")
                    Log.i(tag, "Client cert will be used for authentication only")
                    // Leave the alias NULL so any chain to a trusted root is allowed;
                    // strongSwan server-certificate validation remains enabled.
                    profile.certificateAlias = null

                    // Proactively seed Let's Encrypt trust anchors into the store strongSwan actually reads
                    try {
                        val installedAliases = ensureLetsEncryptCompatibilityTrustAnchors()
                        if (installedAliases.isNotEmpty()) {
                            Log.i(tag, "✓ Seeded Let's Encrypt compatibility trust anchors: $installedAliases")
                        } else {
                            Log.i(tag, "No Let's Encrypt compatibility trust anchors were available for installation")
                        }
                    } catch (e: Exception) {
                        Log.w(tag, "Failed to ensure Let's Encrypt compatibility roots: ${e.message}")
                    }
                }
            }

            // Client policy is authoritative over downloaded and pushed DNS.
            profile.dnsServers = InternalDnsPolicy.authoritativeIkev2DnsServers(
                json.optJSONArray("dns-servers")?.let(::jsonArrayToSpaceSeparated)
            )

            json.optJSONObject("split-tunneling")?.let { st ->
                var flags = 0
                if (st.optBoolean("block-ipv4", false)) flags = flags or VpnProfile.SPLIT_TUNNELING_BLOCK_IPV4
                if (st.optBoolean("block-ipv6", false)) flags = flags or VpnProfile.SPLIT_TUNNELING_BLOCK_IPV6
                if (flags != 0) profile.splitTunneling = flags
            }

            // Client certificate (signed by YOUR private CA) - ONE-CLICK IMPORT
            json.optJSONObject("local")?.let { localObj ->
                val p12Base64 = localObj.optString("p12")
                val p12Password = localObj.optString("password")

                if (p12Base64.isNotBlank()) {
                    Log.i(tag, "Processing client certificate (signed by your private CA)...")
                    val certAlias = importClientCertificateOneClick(p12Base64, p12Password)

                    if (certAlias != null) {
                        profile.userCertificateAlias = certAlias

                        // For EAP-TLS, set the P12 password; for certificate-only we avoid passing it
                        if (profile.vpnType == VpnType.IKEV2_EAP_TLS && p12Password.isNotBlank()) {
                            profile.password = p12Password
                        }

                        Log.i(tag, "✓ Client certificate imported successfully: $certAlias")
                    } else {
                        Log.e(tag, "✗ Failed to import client certificate")
                        return null
                    }
                }
            }

            Log.i(tag, "=== Profile Created Successfully ===")
            Log.i(tag, "Server: ${profile.gateway} (Let's Encrypt)")
            Log.i(tag, "Client Auth: ${profile.userCertificateAlias} (Your Private CA)")

            profile
        } catch (e: Exception) {
            Log.e(tag, "Failed to create VPN profile", e)
            null
        }
    }

    /**
     * Import Let's Encrypt CA certificate for server validation
     * In hybrid mode, the server presents a Let's Encrypt certificate
     * This CA cert allows Android to validate the server
     */
    private fun importLetsEncryptCACertificate(certBase64: String): String? {
        return try {
            Log.i(tag, "=== LET'S ENCRYPT CA CERTIFICATE IMPORT ===")
            Log.d(tag, "CA cert Base64 length: ${certBase64.length}")

            val parsedCertificates = parseCertificateBundle(certBase64)
            if (parsedCertificates.isEmpty()) {
                Log.e(tag, "Failed to parse Let's Encrypt CA certificate bundle")
                return null
            }

            parsedCertificates.forEachIndexed { index, cert ->
                Log.i(tag, "Let's Encrypt certificate[$index] details:")
                Log.i(tag, "  Subject: ${cert.subjectDN}")
                Log.i(tag, "  Issuer: ${cert.issuerDN}")
                Log.i(tag, "  Valid from: ${cert.notBefore}")
                Log.i(tag, "  Valid until: ${cert.notAfter}")
                Log.d(tag, "  Is self-signed (root CA): ${cert.subjectDN == cert.issuerDN}")
            }

            val caCertificates = parsedCertificates.filter {
                try {
                    it.basicConstraints >= 0
                } catch (_: Exception) {
                    false
                }
            }
            if (caCertificates.isEmpty()) {
                Log.e(tag, "Certificate bundle did not contain any CA certificates to trust")
                return null
            }

            val alias = installCertificatesIntoLocalStore(caCertificates, "letsencrypt_ca")
                ?: return null

            Log.i(tag, "=== LET'S ENCRYPT CA CERTIFICATE IMPORT COMPLETE ===")
            Log.i(tag, "✓✓✓ Server certificates signed by Let's Encrypt will be trusted via alias=$alias")

            alias
        } catch (e: Exception) {
            Log.e(tag, "✗✗✗ Failed to import Let's Encrypt CA certificate: ${e.message}", e)
            null
        }
    }

    /**
     * Import client certificate (signed by YOUR private CA) - ONE-CLICK, NO PROMPTS
     * Uses LocalCertificateKeyStoreManager for seamless import
     */
    private data class LocalImportAttempt(
        val certAlias: String?,
        val loadStrategy: String? = null,
        val failureReason: String? = null,
        val failureThrowable: Throwable? = null,
        val shouldOfferKeyChainFallback: Boolean = false
    )

    private data class ParsedPkcs12Identity(
        val userCertificate: X509Certificate,
        val privateKey: PrivateKey,
        val loadStrategy: String
    )

    private data class ParsedPkcs12Attempt(
        val identity: ParsedPkcs12Identity? = null,
        val failureReason: String? = null,
        val failureThrowable: Throwable? = null,
        val loadStrategy: String? = null,
        val shouldOfferKeyChainFallback: Boolean = false
    )

    private fun importClientCertificateOneClick(p12Base64: String, password: String): String? {
        return try {
            Log.i(tag, "=== ONE-CLICK CLIENT CERTIFICATE IMPORT ===")
            Log.d(tag, "P12 Base64 length: ${p12Base64.length}")
            Log.d(tag, "Password provided: ${if (password.isEmpty()) "[empty]" else "[***${password.length} chars]"}")

            if (p12Base64.isBlank()) {
                Log.e(tag, "P12 data is blank")
                return null
            }

            val cleanBase64 = p12Base64.replace("\\s".toRegex(), "")
            val p12Bytes = Base64.decode(cleanBase64, Base64.DEFAULT)
            if (p12Bytes == null || p12Bytes.isEmpty()) {
                Log.e(tag, "Decoded P12 data is empty")
                return null
            }

            // Use LocalCertificateKeyStoreManager for one-click import (NO Android KeyStore prompts!)
            Log.d(tag, "Calling LocalCertificateKeyStoreManager import API...")
            val result: LocalImportAttempt = importWithLocalCertificateManager(p12Base64, p12Bytes, password)
            val certAlias = result.certAlias
            val importStrategy = result.loadStrategy

            if (certAlias != null) {
                Log.i(tag, "✓✓✓ Client certificate imported successfully: $certAlias")
                Log.i(tag, "✓✓✓ Certificate alias: ${certAlias}")
                Log.i(tag, "✓✓✓ Import strategy: ${importStrategy ?: "unknown"}")
                Log.i(tag, "✓✓✓ NO USER INTERACTION REQUIRED - Ready to connect!")

                // Verify accessibility
                val isAvailable = localCertManager.isCertificateAvailable(certAlias)
                Log.d(tag, "Certificate availability: $isAvailable")

                // Verify private key access
                try {
                    val key = localCertManager.getPrivateKey(certAlias)
                    Log.d(tag, "Private key accessible: ${key != null}, algorithm: ${key?.algorithm}")
                } catch (e: Exception) {
                    Log.e(tag, "Failed to verify private key access: ${e.message}")
                }

                return certAlias
            } else {
                val failureReason = result.failureReason ?: "Local PKCS#12 import failed"
                val failureThrowable = result.failureThrowable ?: IllegalStateException(failureReason)

                if (result.shouldOfferKeyChainFallback) {
                    val fallbackAlias = stagePendingKeyChainInstall(p12Bytes, failureReason)
                    Log.w(tag, "Device PKCS#12 provider issue detected; prepared KeyChain fallback alias=$fallbackAlias reason=$failureReason")
                    CrashlyticsReporter.recordHandledException(
                        failureThrowable,
                        "Local PKCS#12 import failed; KeyChain fallback prepared. $failureReason"
                    )
                    return fallbackAlias
                }

                Log.e(tag, "✗✗✗ LocalCertificateKeyStoreManager.importP12CertificateDetailed failed: $failureReason")
                CrashlyticsReporter.recordHandledException(failureThrowable, "Client certificate import failed: $failureReason")

                // Fallback: Save for debugging
                val fallbackAlias = "shadowlink_${System.currentTimeMillis()}"
                saveP12File(fallbackAlias, p12Bytes)
                Log.w(tag, "Saved raw P12 for debugging: $fallbackAlias")

                return null
            }
        } catch (e: Exception) {
            Log.e(tag, "✗✗✗ Exception during client certificate import: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
            CrashlyticsReporter.recordHandledException(e, "Exception during client certificate import")
            null
        }
    }

    private fun importWithLocalCertificateManager(p12Base64: String, p12Bytes: ByteArray, password: String): LocalImportAttempt {
        return try {
            val detailedMethod = localCertManager.javaClass.methods.firstOrNull {
                it.name == "importP12CertificateDetailed" &&
                    it.parameterTypes.contentEquals(arrayOf(String::class.java, String::class.java))
            }

            val detailedAttempt = if (detailedMethod != null) {
                val rawResult = detailedMethod.invoke(localCertManager, p12Base64, password)
                if (rawResult != null) {
                    LocalImportAttempt(
                        certAlias = invokeStringGetter(rawResult, "getCertAlias"),
                        loadStrategy = invokeStringGetter(rawResult, "getLoadStrategy"),
                        failureReason = invokeStringGetter(rawResult, "getFailureReason"),
                        failureThrowable = invokeThrowableGetter(rawResult, "getException"),
                        shouldOfferKeyChainFallback = invokeBooleanGetter(rawResult, "shouldOfferKeyChainFallback")
                    )
                } else {
                    LocalImportAttempt(
                        certAlias = null,
                        failureReason = "Local certificate import API returned null result"
                    )
                }
            } else {
                null
            }

            if (detailedAttempt?.certAlias != null) {
                return detailedAttempt
            }

            val parseAttempt = parsePkcs12Identity(p12Bytes, password)
            val parsedIdentity = parseAttempt.identity
            if (parsedIdentity != null) {
                val localAlias = importParsedIdentityLocally(parsedIdentity, p12Bytes, password)
                if (localAlias != null) {
                    return LocalImportAttempt(
                        certAlias = localAlias,
                        loadStrategy = parsedIdentity.loadStrategy
                    )
                }

                return LocalImportAttempt(
                    certAlias = null,
                    loadStrategy = parsedIdentity.loadStrategy,
                    failureReason = "Parsed PKCS#12 successfully but failed to persist the client certificate locally",
                    failureThrowable = parseAttempt.failureThrowable ?: detailedAttempt?.failureThrowable,
                    shouldOfferKeyChainFallback = true
                )
            }

            if (parseAttempt.failureReason != null) {
                return LocalImportAttempt(
                    certAlias = null,
                    loadStrategy = parseAttempt.loadStrategy ?: detailedAttempt?.loadStrategy,
                    failureReason = parseAttempt.failureReason,
                    failureThrowable = parseAttempt.failureThrowable ?: detailedAttempt?.failureThrowable,
                    shouldOfferKeyChainFallback = parseAttempt.shouldOfferKeyChainFallback || detailedAttempt?.shouldOfferKeyChainFallback == true
                )
            }

            detailedAttempt ?: LocalImportAttempt(
                certAlias = null,
                failureReason = "Local PKCS#12 import failed",
                shouldOfferKeyChainFallback = true
            )
        } catch (e: Exception) {
            LocalImportAttempt(
                certAlias = null,
                failureReason = "Failed to invoke local certificate import API: ${e.message}",
                failureThrowable = e
            )
        }
    }

    private fun invokeStringGetter(target: Any, methodName: String): String? {
        return runCatching { target.javaClass.getMethod(methodName).invoke(target) as? String }.getOrNull()
    }

    private fun invokeThrowableGetter(target: Any, methodName: String): Throwable? {
        return runCatching { target.javaClass.getMethod(methodName).invoke(target) as? Throwable }.getOrNull()
    }

    private fun invokeBooleanGetter(target: Any, methodName: String): Boolean {
        return runCatching { target.javaClass.getMethod(methodName).invoke(target) as? Boolean ?: false }.getOrDefault(false)
    }

    private fun parsePkcs12Identity(p12Bytes: ByteArray, password: String): ParsedPkcs12Attempt {
        val provider = BouncyCastleBootstrap.ensureExternalProviderRegistered()
            ?: return ParsedPkcs12Attempt(
                failureReason = "External BouncyCastle provider is unavailable on this build",
                shouldOfferKeyChainFallback = true,
                loadStrategy = "app-bc-unavailable"
            )
        val passwordCandidates = buildPasswordCandidates(password)
        var lastError: Exception? = null
        val providerClass = provider.javaClass.name

        for (storePassword in passwordCandidates) {
            try {
                val keyStore = KeyStore.getInstance("PKCS12", provider.name)
                ByteArrayInputStream(p12Bytes).use { input ->
                    keyStore.load(input, storePassword)
                }

                val aliases = keyStore.aliases()
                while (aliases.hasMoreElements()) {
                    val alias = aliases.nextElement()
                    val cert = keyStore.getCertificate(alias) as? X509Certificate ?: continue
                    for (keyPassword in passwordCandidates) {
                        try {
                            val key = keyStore.getKey(alias, keyPassword) as? PrivateKey ?: continue
                            return ParsedPkcs12Attempt(
                                identity = ParsedPkcs12Identity(
                                    userCertificate = cert,
                                    privateKey = key,
                                    loadStrategy = "app-bc-pkcs12 provider=$providerClass password=${if (storePassword.isEmpty()) "empty" else "provided"}"
                                ),
                                loadStrategy = "app-bc-pkcs12 provider=$providerClass"
                            )
                        } catch (e: Exception) {
                            lastError = e
                        }
                    }
                }
            } catch (e: Exception) {
                lastError = e
                Log.w(tag, "BC PKCS#12 parsing failed with ${if (storePassword.isEmpty()) "empty" else "provided"} password: ${e.message}")
            }
        }

        if (lastError != null) {
            Log.w(tag, "Unable to parse PKCS#12 with app-bundled BouncyCastle", lastError)
        }
        return ParsedPkcs12Attempt(
            failureReason = lastError?.message ?: "Unable to parse PKCS#12 with app-bundled BouncyCastle",
            failureThrowable = lastError,
            shouldOfferKeyChainFallback = true,
            loadStrategy = "app-bc-pkcs12 provider=$providerClass"
        )
    }

    private fun importParsedIdentityLocally(identity: ParsedPkcs12Identity, p12Bytes: ByteArray, password: String): String? {
        return try {
            val certificateStore = LocalCertificateStore()
            if (!certificateStore.addCertificate(identity.userCertificate)) {
                Log.e(tag, "Failed to add parsed client certificate to LocalCertificateStore")
                return null
            }

            val certAlias = certificateStore.getCertificateAlias(identity.userCertificate)
            if (certAlias.isNullOrBlank()) {
                Log.e(tag, "LocalCertificateStore returned no alias for parsed client certificate")
                return null
            }

            if (!storeLocalPrivateKey(certAlias, identity.privateKey)) {
                certificateStore.deleteCertificate(certAlias)
                return null
            }

            storeLocalP12Artifacts(certAlias, p12Bytes, password)
            Log.i(tag, "Imported client certificate via direct local-store path alias=$certAlias strategy=${identity.loadStrategy}")
            certAlias
        } catch (e: Exception) {
            Log.e(tag, "Failed direct local-store import of parsed client certificate", e)
            null
        }
    }

    private fun storeLocalPrivateKey(certAlias: String, privateKey: PrivateKey): Boolean {
        return try {
            val keyId = certAlias.removePrefix("local:")
            val keyDir = File(context.filesDir, "user_keys").apply { if (!exists()) mkdirs() }
            val keyFile = File(keyDir, "key-$keyId")
            FileOutputStream(keyFile).use { out ->
                out.write(privateKey.encoded)
            }
            true
        } catch (e: Exception) {
            Log.e(tag, "Failed to store direct-import private key", e)
            false
        }
    }

    private fun storeLocalP12Artifacts(certAlias: String, p12Bytes: ByteArray, password: String) {
        try {
            val keyId = certAlias.removePrefix("local:")
            val p12Dir = File(context.filesDir, "user_p12").apply { if (!exists()) mkdirs() }
            File(p12Dir, "cert-$keyId.p12").writeBytes(p12Bytes)
            if (password.isNotEmpty()) {
                File(p12Dir, "cert-$keyId.pwd").writeText(password)
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to persist direct-import PKCS#12 artifacts", e)
        }
    }

    private fun buildPasswordCandidates(password: String): List<CharArray> {
        val provided = password.toCharArray()
        return if (password.isEmpty()) {
            listOf(provided)
        } else {
            listOf(provided, CharArray(0))
        }
    }

    private fun stagePendingKeyChainInstall(p12Bytes: ByteArray, reason: String): String {
        val alias = "libreguard_${System.currentTimeMillis()}"
        File(pendingDir, "$alias.p12").writeBytes(p12Bytes)
        File(pendingDir, "$alias.meta").writeText(
            JSONObject()
                .put("createdAt", System.currentTimeMillis())
                .put("reason", reason)
                .toString()
        )
        Log.i(tag, "Prepared Android KeyChain fallback alias=$alias reason=$reason")
        return alias
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

    private fun parseCertificateBundle(rawCertificateData: String): List<X509Certificate> {
        val certificates = mutableListOf<X509Certificate>()
        val trimmed = rawCertificateData.trim()

        if (trimmed.contains("-----BEGIN CERTIFICATE-----")) {
            try {
                val factory = CertificateFactory.getInstance("X.509")
                val generated = factory.generateCertificates(ByteArrayInputStream(trimmed.toByteArray()))
                generated.forEach { cert ->
                    (cert as? X509Certificate)?.let { certificates.add(it) }
                }
                if (certificates.isNotEmpty()) {
                    return certificates
                }
            } catch (e: Exception) {
                Log.w(tag, "Failed to parse PEM certificate bundle directly: ${e.message}")
            }
        }

        val cleanBase64 = cleanBase64String(rawCertificateData)
        val certBytes = tryMultipleBase64Decoding(cleanBase64) ?: return emptyList()
        return listOfNotNull(parseServerCertificate(certBytes))
    }

    private fun installCertificatesIntoLocalStore(certs: List<X509Certificate>, backupPrefix: String): String? {
        val certificateStore = LocalCertificateStore()
        val preferredCertificate = certs.firstOrNull { it.subjectX500Principal == it.issuerX500Principal } ?: certs.lastOrNull()
        var preferredAlias: String? = null

        certs.forEachIndexed { index, cert ->
            val alias = certificateStore.getCertificateAlias(cert)
            if (alias.isNullOrBlank()) {
                Log.e(tag, "Failed to derive LocalCertificateStore alias for ${cert.subjectX500Principal.name}")
                return@forEachIndexed
            }

            val alreadyPresent = certificateStore.containsAlias(alias)
            if (!alreadyPresent && !certificateStore.addCertificate(cert)) {
                Log.e(tag, "Failed to add CA certificate to LocalCertificateStore: ${cert.subjectX500Principal.name}")
                return@forEachIndexed
            }

            persistCertificateBackups(cert, alias, backupPrefix, index)
            Log.i(
                tag,
                if (alreadyPresent) {
                    "CA certificate already present in LocalCertificateStore: $alias (${cert.subjectX500Principal.name})"
                } else {
                    "Installed CA certificate into LocalCertificateStore: $alias (${cert.subjectX500Principal.name})"
                }
            )

            if (cert == preferredCertificate) {
                preferredAlias = alias
            }
        }

        if (!preferredAlias.isNullOrBlank()) {
            refreshTrustedCertificateCache()
        }
        return preferredAlias
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

    // -------- Certificate Diagnostics and Helper Methods --------

    enum class UserCertState {
        NOT_IMPORTED,
        PENDING_INSTALL,
        INSTALLED_OK,
        INSTALLED_NO_KEY
    }

    data class UserCertDiagnostics(
        val alias: String?,
        val state: UserCertState,
        val chainSize: Int = 0,
        val subject0: String? = null,
        val hasPrivateKey: Boolean = false,
        val notes: String = ""
    )

    /**
     * Diagnose certificate status (for KeyChain compatibility check)
     * In hybrid mode, we primarily use LocalCertificateStore, but this helps with debugging
     */
    fun diagnoseUserCertificate(alias: String?): UserCertDiagnostics {
        if (alias.isNullOrBlank()) {
            return UserCertDiagnostics(alias, UserCertState.NOT_IMPORTED, notes = "Alias null/blank")
        }

        // Check if it's a LocalCertificateStore alias
        if (alias.startsWith("local:")) {
            val isAvailable = try {
                localCertManager.isCertificateAvailable(alias)
            } catch (e: Exception) {
                false
            }

            if (isAvailable) {
                val hasKey = try {
                    localCertManager.getPrivateKey(alias) != null
                } catch (e: Exception) {
                    false
                }

                return UserCertDiagnostics(
                    alias,
                    if (hasKey) UserCertState.INSTALLED_OK else UserCertState.INSTALLED_NO_KEY,
                    chainSize = 1,
                    subject0 = alias,
                    hasPrivateKey = hasKey,
                    notes = "LocalCertificateStore: ${if (hasKey) "OK" else "No private key"}"
                )
            } else {
                return UserCertDiagnostics(
                    alias,
                    UserCertState.NOT_IMPORTED,
                    notes = "LocalCertificateStore: Certificate not available"
                )
            }
        }

        // Check Android KeyChain (for backward compatibility)
        val pending = File(pendingDir, "$alias.p12").exists()
        return try {
            val chain = KeyChain.getCertificateChain(context, alias)
            val key = try {
                KeyChain.getPrivateKey(context, alias)
            } catch (e: Exception) {
                null
            }

            if (chain == null || chain.isEmpty()) {
                if (pending) {
                    UserCertDiagnostics(
                        alias,
                        UserCertState.PENDING_INSTALL,
                        notes = "KeyChain chain empty; pending files exist"
                    )
                } else {
                    UserCertDiagnostics(
                        alias,
                        UserCertState.PENDING_INSTALL,
                        notes = "KeyChain chain empty"
                    )
                }
            } else {
                val hasKey = key != null
                UserCertDiagnostics(
                    alias,
                    if (hasKey) UserCertState.INSTALLED_OK else UserCertState.INSTALLED_NO_KEY,
                    chainSize = chain.size,
                    subject0 = try {
                        chain[0].subjectX500Principal.name
                    } catch (e: Exception) {
                        null
                    },
                    hasPrivateKey = hasKey,
                    notes = if (hasKey) "KeyChain: Certificate + key accessible" else "KeyChain: Private key not accessible"
                )
            }
        } catch (e: Exception) {
            UserCertDiagnostics(
                alias,
                if (pending) UserCertState.PENDING_INSTALL else UserCertState.PENDING_INSTALL,
                notes = "Exception: ${e.javaClass.simpleName}:${e.message}"
            )
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
            UserCertState.INSTALLED_NO_KEY -> true
            UserCertState.INSTALLED_OK -> false
        }
    }

    // -------- Certificate Alias Mapping (for backward compatibility) --------

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

    // -------- KeyChain Compatibility Methods (for old installs) --------

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

    fun getInstallIntentIfPending(alias: String?): Intent? {
        if (alias is String) {
            if (needsUserCertInstallation(alias)) {
                return getStoredInstallIntent(alias)
            }
        }
        return null
    }

    fun clearPendingInstall(alias: String) {
        File(pendingDir, "$alias.p12").delete()
        File(pendingDir, "$alias.meta").delete()
    }

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
            listOf("$alias.p12", "$alias.meta").forEach { filename ->
                val file = File(pendingDir, filename)
                if (file.exists()) {
                    file.delete()
                    Log.d(tag, "Cleaned up pending install file: $filename")
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to cleanup install intent data", e)
        }
    }

    fun isUserCertificateInstalled(alias: String?): Boolean {
        if (alias.isNullOrBlank()) return false

        // Check LocalCertificateStore first
        if (alias.startsWith("local:")) {
            return try {
                localCertManager.isCertificateAvailable(alias)
            } catch (e: Exception) {
                false
            }
        }

        // Check KeyChain (backward compatibility)
        return try {
            val chain = KeyChain.getCertificateChain(context, alias)
            chain != null && chain.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Seed Let's Encrypt trust anchors into LocalCertificateStore so strongSwan can
     * validate the traditional ISRG Root X1 path and newer Root YE/Root YR paths.
     */
    private data class LetsEncryptCompatibilityTrustAnchor(
        val subjectCN: String,
        val expectedOrg: String,
        val filename: String,
        val bundledResourceId: Int? = null
    )

    private fun ensureLetsEncryptCompatibilityTrustAnchors(): List<String> {
        val aliases = linkedSetOf<String>()
        listOf(
            LetsEncryptCompatibilityTrustAnchor(
                subjectCN = "ISRG Root X1",
                expectedOrg = "Internet Security Research Group",
                filename = "isrg_root_x1.crt"
            ),
            LetsEncryptCompatibilityTrustAnchor(
                subjectCN = "Root YE",
                expectedOrg = "ISRG",
                filename = "root_ye.crt",
                bundledResourceId = R.raw.root_ye
            ),
            LetsEncryptCompatibilityTrustAnchor(
                subjectCN = "Root YR",
                expectedOrg = "ISRG",
                filename = "root_yr.crt",
                bundledResourceId = R.raw.root_yr
            )
        ).forEach { trustAnchor ->
            val alias = installSystemRootBySubjectCN(
                trustAnchor.subjectCN,
                trustAnchor.expectedOrg,
                trustAnchor.filename
            ) ?: trustAnchor.bundledResourceId?.let { resourceId ->
                installBundledLetsEncryptRoot(
                    expectedSubjectCN = trustAnchor.subjectCN,
                    expectedOrg = trustAnchor.expectedOrg,
                    filename = trustAnchor.filename,
                    resourceId = resourceId
                )
            }
            alias?.let(aliases::add)
        }
        if (aliases.isNotEmpty()) {
            refreshTrustedCertificateCache()
        }
        return aliases.toList()
    }

    private fun installBundledLetsEncryptRoot(
        expectedSubjectCN: String,
        expectedOrg: String,
        filename: String,
        resourceId: Int
    ): String? {
        return try {
            val pem = context.resources.openRawResource(resourceId).bufferedReader().use { it.readText() }
            val matchingCertificate = parseCertificateBundle(pem).firstOrNull { cert ->
                val subject = cert.subjectX500Principal.name
                val issuer = cert.issuerX500Principal.name
                val cn = subject.split(',').firstOrNull { it.trim().startsWith("CN=") }?.substringAfter("CN=")?.trim()
                val org = subject.split(',').firstOrNull { it.trim().startsWith("O=") }?.substringAfter("O=")?.trim()
                val isSelfSigned = subject == issuer
                val isCA = try { cert.basicConstraints >= 0 } catch (_: Exception) { false }
                isSelfSigned && isCA && cn == expectedSubjectCN && org == expectedOrg
            }

            if (matchingCertificate == null) {
                Log.e(tag, "Bundled Let's Encrypt root did not match expected subject: CN=$expectedSubjectCN, O=$expectedOrg")
                return null
            }

            val alias = installCertificatesIntoLocalStore(listOf(matchingCertificate), filename.removeSuffix(".crt"))
            if (alias != null) {
                Log.i(tag, "Installed bundled Let's Encrypt trust anchor '$expectedSubjectCN' as $alias")
            }
            alias
        } catch (e: Exception) {
            Log.e(tag, "Failed to install bundled Let's Encrypt root '$expectedSubjectCN': ${e.message}", e)
            null
        }
    }

    /**
     * Install a system CA certificate (from AndroidCAStore) into LocalCertificateStore by matching subject CN and O.
     * Returns the LocalCertificateStore alias if the certificate is available.
     */
    private fun installSystemRootBySubjectCN(subjectCN: String, expectedOrg: String? = null, filename: String): String? {
        try {
            val ks = KeyStore.getInstance("AndroidCAStore")
            ks.load(null)
            val aliases = ks.aliases()
            while (aliases.hasMoreElements()) {
                val alias = aliases.nextElement()
                val cert = ks.getCertificate(alias) as? X509Certificate ?: continue
                val subject = cert.subjectX500Principal.name
                val issuer = cert.issuerX500Principal.name
                val cn = subject.split(',').firstOrNull { it.trim().startsWith("CN=") }?.substringAfter("CN=")?.trim()
                val org = subject.split(',').firstOrNull { it.trim().startsWith("O=") }?.substringAfter("O=")?.trim()
                val isSelfSigned = subject == issuer
                val isCA = try { cert.basicConstraints >= 0 } catch (_: Exception) { false }
                if (isSelfSigned && isCA && cn == subjectCN && (expectedOrg == null || org == expectedOrg)) {
                    val certificateStore = LocalCertificateStore()
                    val localAlias = certificateStore.getCertificateAlias(cert)
                    if (localAlias.isNullOrBlank()) {
                        Log.e(tag, "Unable to derive LocalCertificateStore alias for system root '$subjectCN'")
                        return null
                    }

                    val alreadyPresent = certificateStore.containsAlias(localAlias)
                    if (!alreadyPresent && !certificateStore.addCertificate(cert)) {
                        Log.e(tag, "Failed to add system root '$subjectCN' to LocalCertificateStore")
                        return null
                    }

                    persistCertificateBackups(cert, localAlias, filename.removeSuffix(".crt"), 0, explicitFilename = filename)
                    Log.d(
                        tag,
                        if (alreadyPresent) {
                            "System root '$subjectCN' already present in LocalCertificateStore as $localAlias"
                        } else {
                            "Installed system root '$subjectCN' from AndroidCAStore alias=$alias into LocalCertificateStore as $localAlias"
                        }
                    )
                    return localAlias
                }
            }
            Log.w(tag, "System CA not found: CN=$subjectCN, O=$expectedOrg")
            return null
        } catch (e: Exception) {
            Log.e(tag, "Failed installing system root CN=$subjectCN: ${e.message}", e)
            return null
        }
    }

    private fun persistCertificateBackups(
        cert: X509Certificate,
        alias: String,
        backupPrefix: String,
        index: Int,
        explicitFilename: String? = null
    ) {
        try {
            val fileName = explicitFilename ?: "${backupPrefix}_${index}_${alias.replace(':', '_')}.crt"
            val pem = buildPem(cert.encoded)
            File(configDir, fileName).writeText(pem)
            File(cacertsDir, fileName).writeText(pem)
        } catch (e: Exception) {
            Log.w(tag, "Failed to persist certificate backup for alias=$alias: ${e.message}")
        }
    }

    private fun refreshTrustedCertificateCache() {
        try {
            TrustedCertificateManager.getInstance().reset().load()
            Log.d(tag, "Reloaded strongSwan trusted certificate cache")
        } catch (e: Exception) {
            Log.w(tag, "Failed to refresh trusted certificate cache: ${e.message}")
        }
    }

    private fun buildPem(der: ByteArray): String {
        val b64 = Base64.encodeToString(der, Base64.NO_WRAP)
        val body = b64.chunked(64).joinToString("\n")
        return "-----BEGIN CERTIFICATE-----\n$body\n-----END CERTIFICATE-----\n"
    }
}
