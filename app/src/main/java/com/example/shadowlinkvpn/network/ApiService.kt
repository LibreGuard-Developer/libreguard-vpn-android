package com.example.shadowlinkvpn.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

data class VpnConfigRequest(
    val serverId: String, // Using server name from your JSON
    val protocol: String, // "IKEV2_IPSEC", "OPENVPN", "WIREGUARD"
    val token: String
)

data class VpnConfigResponse(
    val success: Boolean,
    val message: String?,
    val configData: String?, // Base64 encoded .sswan file content
    val filename: String?
)

interface ApiService {
    @POST("api/login")
    suspend fun login(@Body request: AuthRequest): Response<AuthResponse>

    @POST("api/vpn/config")
    suspend fun getVpnConfig(@Body request: VpnConfigRequest): Response<VpnConfigResponse>
}