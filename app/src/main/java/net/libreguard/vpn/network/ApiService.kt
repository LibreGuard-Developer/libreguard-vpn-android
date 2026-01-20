package net.libreguard.vpn.network

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
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

/**
 * VPN Server information returned from the ManagementPanel API.
 * 
 * Now includes:
 * - load: Server load percentage (0-100), null if unavailable
 * - activeConnections: Number of active VPN connections
 * - latencyPingPort: Port for latency measurement (default 5001)
 * - loadDataFresh: Whether load data was recently updated
 */
data class RemoteVpnServer(
    val id: Int,
    val serverName: String,
    val serverIp: String,
    val serverHostname: String?,
    val country: String,
    val city: String,
    val linkSpeed: Int,
    val pricingTier: String,
    // NEW: Server load and latency fields
    val load: Int? = null,                  // Server load percentage (0-100)
    val activeConnections: Int? = null,     // Number of active VPN connections
    val latencyPingPort: Int = 5001,        // Port for latency ping endpoint
    val loadDataFresh: Boolean = false      // Whether load data is fresh (within last 10 min)
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

// Registration API models
data class RegisterRequest(
    val email: String,
    val password: String
)

data class RegisterResponse(
    val message: String?,
    val userId: String?,
    val email: String?,
    val deviceId: String? = null,
    val requiresEmailConfirmation: Boolean = true,
    val emailConfirmationToken: String? = null,
    val accountStatus: String? = null // "created" | "unverified" | "verified"
)

data class ConfirmEmailRequest(
    val userId: String,
    val token: String
)

data class ConfirmEmailResponse(
    val message: String?,
    val email: String?,
    val userId: String?,
    val nextStep: String? = null,
    val requiresDeviceId: String? = null
)

data class ResendConfirmationRequest(
    val email: String
)

// Check confirmation polling response
data class CheckConfirmationResponse(
    val emailConfirmed: Boolean,
    val message: String?,
    val token: String? = null,
    val email: String? = null,
    val userId: String? = null
)

// Token validation response
data class TokenCheckResponse(
    val isValid: Boolean,
    val message: String?
)

// VPN health check response
data class VpnHealthResponse(
    val status: String, // "healthy", "degraded", "unreachable"
    val message: String?,
    val serverIp: String?,
    val responseTime: Int?,
    val lastChecked: String?
)

interface ApiService {
    @POST("api/login")
    suspend fun login(@Body request: AuthRequest): Response<AuthResponse>

    // Add Google Sign-In endpoint
    @POST("api/login/google")
    suspend fun loginWithGoogle(@Body request: GoogleLoginRequest): Response<GoogleLoginResponse>

    // Refresh Token endpoint
    @POST("api/login/refresh")
    fun refreshToken(@Body request: RefreshTokenRequest): retrofit2.Call<AuthResponse>

    // 2FA Login endpoints
    @POST("api/login/verify-2fa")
    suspend fun verify2fa(@Body request: Verify2faRequest): Response<TokenResponse>

    @POST("api/login/verify-recovery-code")
    suspend fun verifyRecoveryCode(@Body request: VerifyRecoveryRequest): Response<TokenResponse>

    // Logout endpoint - mark device as inactive and revoke token
    @POST("api/logout")
    suspend fun logout(
        @Header("Authorization") authorization: String,
        @Body request: LogoutRequest
    ): Response<LogoutResponse>

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

    // Registration endpoints
    @POST("api/register")
    suspend fun register(@Body request: RegisterRequest): Response<RegisterResponse>

    @POST("api/register/confirm-email")
    suspend fun confirmEmail(@Body request: ConfirmEmailRequest): Response<ConfirmEmailResponse>

    @POST("api/register/resend-confirmation")
    suspend fun resendConfirmation(@Body request: ResendConfirmationRequest): Response<MessageResponse>

    @GET("api/register/check-confirmation/{userId}")
    suspend fun checkConfirmation(@Path("userId") userId: String): Response<CheckConfirmationResponse>

    // Token validation endpoint - Check if token is still valid (not revoked)
    @GET("api/token/check")
    suspend fun checkTokenValidity(@Header("Authorization") authorization: String): Response<TokenCheckResponse>

    // VPN health check endpoint - Verify VPN server is reachable
    @GET("api/vpn/health")
    suspend fun checkVpnHealth(@Header("Authorization") authorization: String): Response<VpnHealthResponse>

    // ===== SUBSCRIPTION ENDPOINTS =====
    @GET("api/subscription/status")
    suspend fun getSubscriptionStatus(
        @Header("Authorization") authorization: String
    ): Response<SubscriptionStatusResponse>

    @POST("api/subscription/register-device")
    suspend fun registerDevice(
        @Header("Authorization") authorization: String,
        @Body request: RegisterDeviceRequest
    ): Response<DeviceResponse>

    @GET("api/subscription/can-access-server/{tierNumber}")
    suspend fun canAccessServer(
        @Header("Authorization") authorization: String,
        @Path("tierNumber") tierNumber: Int
    ): Response<AccessCheckResponse>

    // ===== CARD PAYMENT (LEMONSQUEEZY) =====
    @GET("api/subscription/checkout-url")
    suspend fun getCheckoutUrl(
        @Header("Authorization") authorization: String
    ): Response<CheckoutUrlResponse>

    @POST("api/webhooks/lemonsqueezy/check-payment-status/{orderId}")
    suspend fun checkPaymentStatus(
        @Header("Authorization") authorization: String,
        @Path("orderId") orderId: String
    ): Response<PaymentStatusResponse>

    // ===== MONERO PAYMENT =====
    @GET("api/monero/price")
    suspend fun getMoneroPrice(
        @Header("Authorization") authorization: String
    ): Response<MoneroPriceResponse>

    @POST("api/monero/create-invoice")
    suspend fun createMoneroInvoice(
        @Header("Authorization") authorization: String
    ): Response<MoneroInvoiceResponse>

    @GET("api/monero/status/{invoiceId}")
    suspend fun getMoneroPaymentStatus(
        @Header("Authorization") authorization: String,
        @Path("invoiceId") invoiceId: String
    ): Response<MoneroStatusResponse>

    @GET("api/monero/latest-invoice")
    suspend fun getLatestMoneroInvoice(
        @Header("Authorization") authorization: String
    ): Response<MoneroInvoiceResponse?>

    // ===== DATA USAGE ENDPOINTS =====
    @GET("api/usage/quota")
    suspend fun getUsageQuota(
        @Header("Authorization") authorization: String
    ): Response<QuotaResponse>

    @GET("api/usage/can-connect")
    suspend fun checkCanConnect(
        @Header("Authorization") authorization: String
    ): Response<CanConnectResponse>

    // ===== DEVICE MANAGEMENT ENDPOINTS =====
    @GET("api/devices")
    suspend fun getDevices(
        @Header("Authorization") authorization: String
    ): Response<DeviceListResponse>

    @POST("api/devices/remove/{id}")
    suspend fun removeDevice(
        @Header("Authorization") authorization: String,
        @Path("id") deviceId: Int
    ): Response<DeviceActionResponse>

    @DELETE("api/devices/{id}")
    suspend fun deleteDevice(
        @Header("Authorization") authorization: String,
        @Path("id") deviceId: Int
    ): Response<DeviceActionResponse>

    @POST("api/devices/remove-all-others")
    suspend fun removeAllOtherDevices(
        @Header("Authorization") authorization: String
    ): Response<BulkActionResponse>

    @POST("api/devices/remove-all-inactive")
    suspend fun removeAllInactiveDevices(
        @Header("Authorization") authorization: String
    ): Response<BulkActionResponse>

    // ===== PRE-AUTH DEVICE MANAGEMENT (Password-based, no JWT required) =====
    @POST("api/devices/pre-auth/remove")
    suspend fun removeDevicePreAuth(
        @Body request: PreAuthDeviceRemovalRequest
    ): Response<DeviceRemovalResponse>

    @POST("api/devices/pre-auth/remove-multiple")
    suspend fun removeMultipleDevicesPreAuth(
        @Body request: PreAuthMultipleDeviceRemovalRequest
    ): Response<DeviceRemovalResponse>

    // ===== PRE-AUTH DEVICE MANAGEMENT (OAuth-based, no JWT required) =====
    @POST("api/devices/pre-auth/oauth/remove")
    suspend fun removeDevicePreAuthOAuth(
        @Body request: PreAuthOAuthDeviceRemovalRequest
    ): Response<DeviceRemovalResponse>

    @POST("api/devices/pre-auth/oauth/remove-multiple")
    suspend fun removeMultipleDevicesPreAuthOAuth(
        @Body request: PreAuthOAuthMultipleDeviceRemovalRequest
    ): Response<DeviceRemovalResponse>
}
