package com.example.shadowlinkvpn.network

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Streaming
import retrofit2.http.Path

data class VpnConfigRequest(
    val serverId: Int, // Changed to Int to match your API
    val protocol: String // "IKEV2", "OPENVPN", "WIREGUARD"
)

data class OpenVpnDownloadRequest(
    val serverId: Int
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

// New models for certificate request & job polling
// Request a certificate issuance job
data class CertificateRequest(
    val vpnType: String, // "OPENVPN" | "IKEV2"
    val serverId: Int
)

// Response for creating a certificate job
// status is typically "Pending" immediately after request
// requestedName is the expected certificate name/alias if provided by backend
data class CertificateJobResponse(
    val jobId: String,
    val requestedName: String?,
    val status: String,
    val message: String? = null
)

// Polling response (can reuse the same structure)
typealias CertificateJobStatusResponse = CertificateJobResponse

interface ApiService {
    @POST("api/login")
    suspend fun login(@Body request: AuthRequest): Response<AuthResponse>

    // 2FA Login endpoints
    @POST("api/login/verify-2fa")
    suspend fun verify2fa(@Body request: Verify2faRequest): Response<TokenResponse>

    @POST("api/login/verify-recovery-code")
    suspend fun verifyRecoveryCode(@Body request: VerifyRecoveryRequest): Response<TokenResponse>

    @GET("api/vpn/servers")
    suspend fun getVpnServers(@Header("Authorization") authorization: String): Response<ServerResponse>

    @POST("api/vpn/config")
    suspend fun getVpnConfig(
        @Header("Authorization") authorization: String,
        @Body request: VpnConfigRequest
    ): Response<VpnConfigResponse>

    @POST("api/vpn/config/openvpn/download")
    @Streaming
    suspend fun downloadOpenVpnConfig(
        @Header("Authorization") authorization: String,
        @Body request: OpenVpnDownloadRequest
    ): Response<ResponseBody>

    // Request certificate issuance if none exists
    @POST("api/certificates/request")
    suspend fun requestCertificate(
        @Header("Authorization") authorization: String,
        @Body request: CertificateRequest
    ): Response<CertificateJobResponse>

    // Poll job status until Success/Failed
    @GET("api/certificates/jobs/{jobId}")
    suspend fun getCertificateJobStatus(
        @Header("Authorization") authorization: String,
        @Path("jobId") jobId: String
    ): Response<CertificateJobStatusResponse>

    // 2FA Management endpoints
    @GET("api/2fa/status")
    suspend fun get2faStatus(@Header("Authorization") authorization: String): Response<TwoFactorStatusResponse>

    @POST("api/2fa/setup")
    suspend fun setup2fa(@Header("Authorization") authorization: String): Response<SetupResponse>

    @POST("api/2fa/enable")
    suspend fun enable2fa(
        @Header("Authorization") authorization: String,
        @Body request: EnableRequest
    ): Response<EnableResponse>

    @POST("api/2fa/disable")
    suspend fun disable2fa(@Header("Authorization") authorization: String): Response<MessageResponse>

    @POST("api/2fa/reset")
    suspend fun reset2fa(@Header("Authorization") authorization: String): Response<MessageResponse>

    @POST("api/2fa/recovery-codes/generate")
    suspend fun generateRecoveryCodes(@Header("Authorization") authorization: String): Response<RecoveryCodesResponse>
}