package com.example.shadowlinkvpn.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

data class VpnConfigRequest(
    val serverId: Int, // Changed to Int to match your API
    val protocol: String // "IKEV2", "OPENVPN", "WIREGUARD"
)

data class VpnConfigResponse(
    val success: Boolean,
    val protocol: String?,
    val serverName: String?,
    val serverIp: String?,
    val certificateName: String?,
    val configContent: String?, // Changed from configData to configContent
    val passphrase: String?,
    val issueDate: String?,
    val expirationDate: String?,
    val clientIp: String?,
    val message: String? // Keep this for error cases
)

data class ServerResponse(
    val servers: List<RemoteVpnServer>
)

data class RemoteVpnServer(
    val id: Int,
    val serverName: String,
    val serverIp: String,
    val serverHostname: String?,
    val country: String,
    val linkSpeed: Int,
    val pricingTier: String
)

interface ApiService {
    @POST("api/login")
    suspend fun login(@Body request: AuthRequest): Response<AuthResponse>

    @GET("api/vpn/servers")
    suspend fun getVpnServers(@Header("Authorization") authorization: String): Response<ServerResponse>

    @POST("api/vpn/config")
    suspend fun getVpnConfig(
        @Header("Authorization") authorization: String,
        @Body request: VpnConfigRequest
    ): Response<VpnConfigResponse>
}