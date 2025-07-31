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

            StrongSwanConfig(
                serverAddress = json.getString("server"),
                remoteId = json.optString("remote_id", json.getString("server")),
                localId = json.optString("local_id"),
                certificateData = json.optString("certificate"),
                privateKeyData = json.optString("private_key"),
                username = json.optString("username"),
                password = json.optString("password"),
                mtu = json.optInt("mtu", 1400)
            )
        } catch (e: Exception) {
            null
        }
    }

    // Data classes for different VPN configurations
    data class StrongSwanConfig(
        val serverAddress: String,
        val remoteId: String,
        val localId: String?,
        val certificateData: String?,
        val privateKeyData: String?,
        val username: String?,
        val password: String?,
        val mtu: Int
    )
}