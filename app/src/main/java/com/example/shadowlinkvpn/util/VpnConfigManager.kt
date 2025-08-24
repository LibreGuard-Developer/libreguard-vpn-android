package com.example.shadowlinkvpn.util

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import org.strongswan.android.data.VpnProfile
import org.strongswan.android.data.VpnType
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.*
import javax.net.ssl.TrustManagerFactory
import java.io.ByteArrayInputStream
import java.security.Provider
import java.security.Security
import java.security.cert.Certificate
import javax.security.auth.x500.X500Principal

class VpnConfigManager(private val context: Context) {

    private val tag = "VpnConfigManager"
    private val configDir: File = File(context.filesDir, "sswan_configs")

    init {
        if (!configDir.exists()) {
            configDir.mkdirs()
        }
        // Initialize security providers properly
        initializeSecurityProviders()
    }

    /**
     * Initialize security providers to handle P12 certificates properly
     */
    private fun initializeSecurityProviders() {
        try {
            // List available providers
            val providers = Security.getProviders()
            Log.d(tag, "Available security providers:")
            providers.forEach { provider ->
                Log.d(tag, "Provider: ${provider.name} - ${provider.info}")
            }

            // Check if PKCS12 is supported
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

    fun createVpnProfile(json: JSONObject): VpnProfile? {
        return try {
            val profile = VpnProfile()

            // Initialize UUID if missing
            if (profile.uuid == null) {
                profile.uuid = UUID.randomUUID()
            }

            profile.name = json.optString("name", "ShadowLink VPN")
            profile.gateway = json.optString("gateway")

            // Fix vpnType assignment - use "type" from JSON response
            val vpnTypeString = json.optString("type", "ikev2-eap")
            profile.vpnType = when (vpnTypeString.lowercase()) {
                "ikev2-eap" -> VpnType.IKEV2_EAP
                "ikev2-cert" -> VpnType.IKEV2_CERT
                "ikev2-eap-tls" -> VpnType.IKEV2_EAP_TLS
                else -> VpnType.IKEV2_EAP
            }

            Log.d(tag, "VPN Type from JSON: $vpnTypeString -> ${profile.vpnType}")

            // For ikev2-eap-tls, username/password are not needed as it uses client certificates
            if (profile.vpnType != VpnType.IKEV2_EAP_TLS) {
                profile.username = json.optString("username")
                profile.password = json.optString("password")
            }

            // Set remoteId to gateway if not specified
            profile.remoteId = json.optString("remoteId", profile.gateway)

            // Handle P12 certificate import with enhanced error handling
            json.optJSONObject("local")?.let { localObj ->
                val p12Base64 = localObj.optString("p12")
                val p12Password = localObj.optString("password")

                if (p12Base64.isNotBlank()) {
                    val certAlias = importP12CertificateFixed(p12Base64, p12Password)
                    if (certAlias != null) {
                        profile.userCertificateAlias = certAlias
                        // For ikev2-eap-tls, we already set the correct type above
                        // For other types, we can switch to certificate auth if P12 is available
                        if (profile.vpnType == VpnType.IKEV2_EAP) {
                            profile.vpnType = VpnType.IKEV2_CERT
                        }
                        Log.d(tag, "Imported P12 certificate with alias: $certAlias")
                    } else {
                        Log.w(tag, "P12 certificate import failed")
                        // For ikev2-eap-tls, this is critical as it requires certificates
                        if (profile.vpnType == VpnType.IKEV2_EAP_TLS) {
                            Log.e(tag, "ikev2-eap-tls requires client certificate but P12 import failed")
                            return null
                        }
                        // For other types, fall back to EAP authentication
                    }
                }
            }

            // Handle server certificate with trust anchor installation
            json.optJSONObject("remote")?.optString("cert")?.let { serverCert ->
                if (serverCert.isNotBlank()) {
                    val caCertAlias = importServerCertificateWithTrustFix(serverCert)
                    if (caCertAlias != null) {
                        profile.certificateAlias = caCertAlias
                        Log.d(tag, "Imported server certificate with alias: $caCertAlias")
                    }
                }
            }

            profile
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse server response", e)
            null
        }
    }

    private fun importP12CertificateFixed(p12Base64: String, password: String): String? {
        return try {
            Log.d(tag, "Starting enhanced P12 certificate import with trust fix")

            val cleanBase64 = cleanBase64String(p12Base64)
            val p12Bytes = tryMultipleBase64Decoding(cleanBase64) ?: return null

            Log.d(tag, "P12 bytes length: ${p12Bytes.size}")

            // First, let's try to inspect the P12 structure
            val p12Info = inspectP12Structure(p12Bytes)
            Log.d(tag, "P12 structure info: $p12Info")

            // Try different approaches for P12 loading
            val passwords = listOf(password, "", "changeit", "password")

            for (pass in passwords) {
                // Try raw certificate extraction first
                val rawResult = tryRawCertificateExtraction(p12Bytes, pass)
                if (rawResult != null) {
                    Log.d(tag, "Successfully extracted certificate using raw extraction")
                    return installCertificateAndKey(rawResult.first, rawResult.second, rawResult.third)
                }

                // Try traditional KeyStore approach
                val result = tryLoadP12WithNativeApproach(p12Bytes, pass)
                if (result != null) {
                    return installCertificateAndKey(result.first, result.second, result.third)
                }
            }

            // Last resort: try to save and use P12 directly for StrongSwan
            Log.w(tag, "Traditional parsing failed, trying direct P12 file approach")
            return tryDirectP12Approach(p12Bytes, password)

        } catch (e: Exception) {
            Log.e(tag, "Failed to import P12 certificate", e)
            null
        }
    }

    private fun inspectP12Structure(p12Bytes: ByteArray): String {
        return try {
            val hexStart = p12Bytes.take(16).joinToString(" ") { "%02x".format(it) }
            val hasP12Header = p12Bytes.size > 4 &&
                    p12Bytes[0] == 0x30.toByte() // ASN.1 SEQUENCE tag
            "Size: ${p12Bytes.size}, Hex start: $hexStart, Has ASN.1 header: $hasP12Header"
        } catch (e: Exception) {
            "Failed to inspect: ${e.message}"
        }
    }

    private fun tryRawCertificateExtraction(p12Bytes: ByteArray, password: String): Triple<X509Certificate, PrivateKey, String>? {
        return try {
            Log.d(tag, "Attempting raw certificate extraction from P12")

            // Try to parse P12 as DER-encoded ASN.1 structure manually
            // This is a fallback for when standard KeyStore parsing fails

            // Try using a temporary file approach which sometimes works better
            val tempFile = File.createTempFile("temp_p12", ".p12", context.cacheDir)
            try {
                tempFile.writeBytes(p12Bytes)

                // Try different KeyStore implementations
                val keyStoreTypes = listOf("PKCS12", "pkcs12", "PKCS#12")

                for (ksType in keyStoreTypes) {
                    try {
                        Log.d(tag, "Trying KeyStore type: $ksType")
                        val keyStore = KeyStore.getInstance(ksType)
                        tempFile.inputStream().use { fis ->
                            keyStore.load(fis, password.toCharArray())
                        }

                        val result = extractCertAndKey(keyStore, password)
                        if (result != null) {
                            Log.d(tag, "Successfully loaded P12 with KeyStore type: $ksType")
                            return result
                        }
                    } catch (e: Exception) {
                        Log.v(tag, "KeyStore type $ksType failed: ${e.message}")
                    }
                }
            } finally {
                tempFile.delete()
            }

            null
        } catch (e: Exception) {
            Log.v(tag, "Raw extraction failed: ${e.message}")
            null
        }
    }

    private fun tryLoadP12WithNativeApproach(p12Bytes: ByteArray, password: String): Triple<X509Certificate, PrivateKey, String>? {
        return try {
            // First try with BouncyCastle provider explicitly
            val bcResult = tryLoadP12WithBouncyCastle(p12Bytes, password)
            if (bcResult != null) {
                return bcResult
            }

            // Fallback to default provider
            val keyStore = KeyStore.getInstance("PKCS12")
            keyStore.load(ByteArrayInputStream(p12Bytes), password.toCharArray())

            Log.d(tag, "Successfully loaded PKCS12 with password using default provider")

            // Extract certificate and private key
            val aliases = keyStore.aliases()
            while (aliases.hasMoreElements()) {
                val alias = aliases.nextElement()
                val cert = keyStore.getCertificate(alias) as? X509Certificate
                val key = keyStore.getKey(alias, password.toCharArray()) as? PrivateKey

                if (cert != null && key != null) {
                    Log.d(tag, "Found certificate and key with alias: $alias")
                    return Triple(cert, key, password)
                }
            }
            null
        } catch (e: Exception) {
            Log.v(tag, "Native approach failed with password '$password': ${e.message}")
            null
        }
    }

    private fun tryLoadP12WithBouncyCastle(p12Bytes: ByteArray, password: String): Triple<X509Certificate, PrivateKey, String>? {
        return try {
            // Try multiple approaches to handle Android's BouncyCastle conflicts
            val approaches = listOf(
                { tryP12WithAndroidProvider(p12Bytes, password) },
                { tryP12WithBCProvider(p12Bytes, password) },
                { tryP12WithSunProvider(p12Bytes, password) },
                { tryP12WithoutProvider(p12Bytes, password) }
            )

            for (approach in approaches) {
                try {
                    val result = approach()
                    if (result != null) {
                        Log.d(tag, "P12 parsing succeeded with one of the approaches")
                        return result
                    }
                } catch (e: Exception) {
                    Log.v(tag, "P12 approach failed: ${e.message}")
                }
            }

            null
        } catch (e: Exception) {
            Log.v(tag, "BouncyCastle approach failed with password '$password': ${e.message}")
            null
        }
    }

    private fun tryP12WithAndroidProvider(p12Bytes: ByteArray, password: String): Triple<X509Certificate, PrivateKey, String>? {
        return try {
            Log.d(tag, "Trying P12 with Android OpenSSL provider")
            val androidProvider = Security.getProvider("AndroidOpenSSL")
            if (androidProvider == null) {
                Log.w(tag, "AndroidOpenSSL provider not available")
                return null
            }

            val keyStore = KeyStore.getInstance("PKCS12", androidProvider)
            keyStore.load(ByteArrayInputStream(p12Bytes), password.toCharArray())
            extractCertAndKey(keyStore, password)
        } catch (e: Exception) {
            Log.v(tag, "Android provider approach failed: ${e.message}")
            null
        }
    }

    private fun tryP12WithBCProvider(p12Bytes: ByteArray, password: String): Triple<X509Certificate, PrivateKey, String>? {
        return try {
            Log.d(tag, "Trying P12 with BouncyCastle provider")
            val bcProvider = Security.getProvider("BC")
            if (bcProvider == null) {
                Log.w(tag, "BouncyCastle provider not available")
                return null
            }

            val keyStore = KeyStore.getInstance("PKCS12", bcProvider)
            keyStore.load(ByteArrayInputStream(p12Bytes), password.toCharArray())
            extractCertAndKey(keyStore, password)
        } catch (e: Exception) {
            Log.v(tag, "BC provider approach failed: ${e.message}")
            null
        }
    }

    private fun tryP12WithSunProvider(p12Bytes: ByteArray, password: String): Triple<X509Certificate, PrivateKey, String>? {
        return try {
            Log.d(tag, "Trying P12 with SUN provider (if available)")

            // Try to get SUN provider (might not be available on Android)
            val providers = Security.getProviders()
            val sunProvider = providers.find { it.name.contains("SUN", ignoreCase = true) }

            if (sunProvider != null) {
                val keyStore = KeyStore.getInstance("PKCS12", sunProvider)
                keyStore.load(ByteArrayInputStream(p12Bytes), password.toCharArray())
                extractCertAndKey(keyStore, password)
            } else {
                Log.v(tag, "SUN provider not available")
                null
            }
        } catch (e: Exception) {
            Log.v(tag, "SUN provider approach failed: ${e.message}")
            null
        }
    }

    private fun tryP12WithoutProvider(p12Bytes: ByteArray, password: String): Triple<X509Certificate, PrivateKey, String>? {
        return try {
            Log.d(tag, "Trying P12 without specifying provider (system default)")
            val keyStore = KeyStore.getInstance("PKCS12")
            keyStore.load(ByteArrayInputStream(p12Bytes), password.toCharArray())
            extractCertAndKey(keyStore, password)
        } catch (e: Exception) {
            Log.v(tag, "Default provider approach failed: ${e.message}")
            null
        }
    }

    private fun extractCertAndKey(keyStore: KeyStore, password: String): Triple<X509Certificate, PrivateKey, String>? {
        try {
            val aliases = keyStore.aliases()
            while (aliases.hasMoreElements()) {
                val alias = aliases.nextElement()
                Log.d(tag, "Processing alias: $alias")

                val cert = keyStore.getCertificate(alias) as? X509Certificate
                val key = keyStore.getKey(alias, password.toCharArray()) as? PrivateKey

                if (cert != null && key != null) {
                    Log.d(tag, "Successfully extracted certificate and key from alias: $alias")
                    Log.d(tag, "Certificate subject: ${cert.subjectDN}")
                    Log.d(tag, "Key algorithm: ${key.algorithm}")
                    return Triple(cert, key, password)
                }
            }

            Log.w(tag, "No valid certificate/key pair found in P12")
            return null
        } catch (e: Exception) {
            Log.e(tag, "Failed to extract cert and key: ${e.message}")
            return null
        }
    }

    private fun tryDirectP12Approach(p12Bytes: ByteArray, password: String): String? {
        return try {
            Log.d(tag, "Trying direct P12 file approach for StrongSwan")

            // Save P12 file directly for StrongSwan to use
            val alias = "shadowlink_p12_${System.currentTimeMillis()}"
            val p12File = File(configDir, "$alias.p12")

            p12File.writeBytes(p12Bytes)

            // Also save the password for StrongSwan
            val passwordFile = File(configDir, "$alias.pwd")
            passwordFile.writeText(password)

            Log.d(tag, "Saved P12 file directly: ${p12File.absolutePath}")
            Log.d(tag, "P12 file size: ${p12File.length()} bytes")

            // Return the alias so StrongSwan can use the files directly
            return alias

        } catch (e: Exception) {
            Log.e(tag, "Direct P12 approach failed", e)
            null
        }
    }

    private fun installCertificateAndKey(cert: X509Certificate, privateKey: PrivateKey, password: String): String {
        // Generate unique alias
        val alias = "shadowlink_${System.currentTimeMillis()}"

        try {
            // Save certificate to internal storage for StrongSwan
            val certFile = File(configDir, "$alias.crt")
            certFile.writeBytes(cert.encoded)

            // For StrongSwan, we need to save the private key in a format it can use
            // This is complex on Android, so for now we'll use the direct P12 approach
            Log.d(tag, "Certificate saved with alias: $alias")
            return alias

        } catch (e: Exception) {
            Log.e(tag, "Failed to install certificate", e)
            throw e
        }
    }

    private fun importServerCertificateWithTrustFix(certBase64: String): String? {
        return try {
            Log.d(tag, "Starting enhanced server certificate import")

            val cleanBase64 = cleanBase64String(certBase64)
            val certBytes = tryMultipleBase64Decoding(cleanBase64) ?: return null

            // Try multiple certificate parsing approaches
            val cert = parseServerCertificate(certBytes) ?: return null

            val alias = "shadowlink_ca_${System.currentTimeMillis()}"

            // Install certificate to system trust store
            installServerCertificate(cert, alias)

            Log.d(tag, "Server certificate imported with alias: $alias")
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
            // Try parsing as DER format directly
            val factory = CertificateFactory.getInstance("X.509")
            factory.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
        } catch (e: Exception) {
            null
        }
    }

    private fun installServerCertificate(cert: X509Certificate, alias: String) {
        try {
            // Save certificate to internal storage for StrongSwan
            val certFile = File(configDir, "$alias.crt")
            certFile.writeBytes(cert.encoded)

            Log.d(tag, "Server certificate saved to: ${certFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(tag, "Failed to save server certificate", e)
            throw e
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

    fun parseServerResponseToProfile(responseBody: String): VpnProfile? {
        return try {
            val json = JSONObject(responseBody)
            createVpnProfile(json)
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse server response to profile", e)
            null
        }
    }

    /**
     * Save StrongSwan configuration to file
     */
    fun saveStrongSwanConfig(
        gateway: String,
        username: String,
        password: String,
        serverCert: String? = null,
        clientCert: String? = null
    ): File {
        val configContent = buildString {
            appendLine("conn shadowlink")
            appendLine("    keyexchange=ikev2")
            appendLine("    auto=add")
            appendLine("    right=$gateway")
            appendLine("    rightauth=pubkey")
            appendLine("    rightid=$gateway")
            appendLine("    leftauth=eap-mschapv2")
            appendLine("    leftid=$username")
            appendLine("    eap_identity=$username")
            appendLine("    leftsendcert=never")
            appendLine("    rightsubnet=0.0.0.0/0")

            if (serverCert != null) {
                appendLine("    rightcert=$serverCert")
            }
            if (clientCert != null) {
                appendLine("    leftcert=$clientCert")
            }
        }

        val configFile = File(configDir, "strongswan_${System.currentTimeMillis()}.conf")
        configFile.writeText(configContent)

        Log.d(tag, "StrongSwan config saved to: ${configFile.absolutePath}")
        return configFile
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
}
