// app/src/main/java/com/example/shadowlinkvpn/util/VpnConfigManager.kt
package com.example.shadowlinkvpn.util

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID

class VpnConfigManager(private val context: Context) {

    // Base directory for all VPN configs
    private val configDir = File(context.filesDir, "vpn_configs").apply {
        if (!exists()) mkdirs()
    }

    fun importClientCertificate(p12Base64: String, password: String): String {
        // Decode the base64 string to a byte array
        val p12Bytes = android.util.Base64.decode(p12Base64, android.util.Base64.DEFAULT)

        // Generate a unique alias for this certificate
        val alias = "client_cert_" + System.currentTimeMillis()

        // Import the certificate into Android's KeyStore
        val keyStore = java.security.KeyStore.getInstance("PKCS12")
        keyStore.load(p12Bytes.inputStream(), password.toCharArray())

        // Save the certificate to the Android Keystore
        // This is a simplified version; in a real implementation you would use Android KeyChain API

        return alias
    }

    fun saveStrongSwanConfig(configContent: String, certificateName: String?): File {
        // Create a unique filename if none provided
        val fileName = certificateName ?: "ikev2_${UUID.randomUUID()}.sswan"
        val configFile = File(configDir, fileName)
        configFile.writeText(configContent)
        return configFile
    }

    fun parseStrongSwanConfig(configFile: File): StrongSwanConfig? {
        return try {
            val configContent = configFile.readText()
            val json = JSONObject(configContent)

            val remote = json.getJSONObject("remote")
            val local = json.getJSONObject("local")

            StrongSwanConfig(
                uuid = json.getString("uuid"),
                serverAddress = json.optString("server", remote.getString("addr")),
                serverIp = json.optString("server_ip", remote.optString("addr")),
                remoteId = remote.optString("id"),
                clientP12 = local.optString("p12"),
                clientPassword = local.optString("password"),
                serverCert = remote.optString("cert"),
                dnsServers = json.optJSONArray("dns-servers")?.let { dns ->
                    (0 until dns.length()).map { dns.getString(it) }
                } ?: listOf("1.1.1.1")
            )
        } catch (e: Exception) {
            null
        }
    }


    // Data classes for different VPN configurations
    data class StrongSwanConfig(
        val uuid: String,
        val serverAddress: String,
        val serverIp: String?,
        val remoteId: String,
        val clientP12: String,
        val clientPassword: String,
        val serverCert: String,
        val dnsServers: List<String>
    )

}