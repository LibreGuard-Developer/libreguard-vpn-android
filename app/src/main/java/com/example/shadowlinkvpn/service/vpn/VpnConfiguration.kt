package com.example.shadowlinkvpn.service.vpn

/**
 * VPN configuration data received from CA server
 */
data class VpnConfiguration(
    val serverAddress: String,
    val username: String,
    val password: String,
    val certificate: String? = null,
    val caCertificate: String? = null,
    val serverPort: Int = 500,
    val protocol: VpnProtocol = VpnProtocol.IKEV2_EAP,
    val mtu: Int = 1400,
    val dnsServers: List<String> = emptyList(),
    val routes: List<String> = emptyList(),
    val p12Base64: String? = null,
    val p12Password: String? = null
) {
    /**
     * Convert VpnConfiguration to JSON string for the VPN service
     */
    fun toJson(): String {
        val json = org.json.JSONObject()

        // Basic server configuration
        json.put("server", serverAddress)
        json.put("name", "ShadowLink VPN Connection")
        json.put("uuid", java.util.UUID.randomUUID().toString())
        json.put("type", "ikev2-cert") // Force certificate-only auth

        // Remote server configuration
        val remoteObj = org.json.JSONObject()
        remoteObj.put("addr", serverAddress)
        remoteObj.put("id", serverAddress)
        caCertificate?.let { remoteObj.put("cert", it) }
        json.put("remote", remoteObj)

        // Local client configuration (P12 certificate)
        if (p12Base64 != null && p12Password != null) {
            val localObj = org.json.JSONObject()
            localObj.put("p12", p12Base64)
            localObj.put("password", p12Password)
            json.put("local", localObj)
        }

        // DNS servers
        if (dnsServers.isNotEmpty()) {
            val dnsArray = org.json.JSONArray()
            dnsServers.forEach { dnsArray.put(it) }
            json.put("dns-servers", dnsArray)
        }

        return json.toString()
    }

    companion object {
        /**
         * Parse VPN configuration from CA server response
         */
        fun fromCurlResponse(response: Map<String, Any>): VpnConfiguration {
            return VpnConfiguration(
                serverAddress = response["server_address"] as? String
                    ?: throw IllegalArgumentException("Missing server_address"),
                username = response["username"] as? String
                    ?: throw IllegalArgumentException("Missing username"),
                password = response["password"] as? String
                    ?: throw IllegalArgumentException("Missing password"),
                certificate = response["certificate"] as? String,
                caCertificate = response["ca_certificate"] as? String,
                serverPort = (response["server_port"] as? Number)?.toInt() ?: 500,
                mtu = (response["mtu"] as? Number)?.toInt() ?: 1400,
                dnsServers = (response["dns_servers"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                routes = (response["routes"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                p12Base64 = response["p12_certificate"] as? String,
                p12Password = response["p12_password"] as? String
            )
        }
    }
}

/**
 * Supported VPN protocols
 */
enum class VpnProtocol {
    IKEV2_EAP,
    IKEV2_RSA,
    IKEV1_XAUTH_PSK,
    IKEV1_XAUTH_RSA
}
