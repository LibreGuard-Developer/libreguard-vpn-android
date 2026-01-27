package net.libreguard.vpn.network

import com.google.gson.annotations.SerializedName

// Updated to match the C# LoginModel with device binding
data class AuthRequest(
    @SerializedName("Email") // Use SerializedName to ensure the JSON key is "Email"
    val email: String,
    @SerializedName("Password") // Match the C# model property name
    val password: String,
    @SerializedName("DeviceId") // Device ID for enforcing device limits - use PascalCase to match backend
    val deviceId: String? = null,
    @SerializedName("AppVersion")
    val appVersion: String? = null
)


data class AuthResponse(
    @SerializedName("token")
    val token: String?, // Token might not be present in an error response
    @SerializedName("refreshToken")
    val refreshToken: String?,
    @SerializedName("message")
    val message: String?, // Message might not be present in a success response
    @SerializedName("requiresTwoFactor")
    val requiresTwoFactor: Boolean = false,
    @SerializedName("email")
    val email: String? = null,
    @SerializedName("userId")
    val userId: String? = null,
    @SerializedName("deviceId")
    val deviceId: String? = null,
    @SerializedName("activeDevices")
    val activeDevices: Int? = null,
    @SerializedName("maxDevices")
    val maxDevices: Int? = null,
    @SerializedName("planType")
    val planType: String? = null // "Free", "Pro"
)

// Add Google Sign-In models
// Request body for POST /api/login/google
data class GoogleLoginRequest(
    @SerializedName("idToken")
    val idToken: String,
    @SerializedName("DeviceId")
    val deviceId: String? = null,
    @SerializedName("AppVersion")
    val appVersion: String? = null
)

// Response from POST /api/login/google { token, email, userId, provider }
data class GoogleLoginResponse(
    @SerializedName("token") val token: String,
    @SerializedName("refreshToken") val refreshToken: String,
    @SerializedName("email") val email: String,
    @SerializedName("userId") val userId: String,
    @SerializedName("provider") val provider: String,
    @SerializedName("deviceId") val deviceId: String? = null,
    @SerializedName("activeDevices") val activeDevices: Int? = null,
    @SerializedName("maxDevices") val maxDevices: Int? = null,
    @SerializedName("planType") val planType: String? = null // "Free", "Pro"
)

// Refresh Token Request (device-bound)
data class RefreshTokenRequest(
    @SerializedName("RefreshToken")
    val refreshToken: String,
    @SerializedName("DeviceId")
    val deviceId: String,
    @SerializedName("AppVersion")
    val appVersion: String? = null
)

// 2FA Verification Request (device-bound)
data class Verify2faRequest(
    @SerializedName("email")
    val email: String,
    @SerializedName("twoFactorCode")
    val twoFactorCode: String,
    @SerializedName("DeviceId")
    val deviceId: String,
    @SerializedName("AppVersion")
    val appVersion: String? = null
)

// Structured API Error Response
data class ApiErrorResponse(
    @SerializedName("error")
    val error: String? = null,
    @SerializedName("code")
    val code: String? = null,
    @SerializedName("message")
    val message: String? = null,
    @SerializedName("requiresEmailVerification")
    val requiresEmailVerification: Boolean = false
)

// Recovery Code Verification Request (device-bound)
data class VerifyRecoveryRequest(
    @SerializedName("email")
    val email: String,
    @SerializedName("recoveryCode")
    val recoveryCode: String,
    @SerializedName("DeviceId")
    val deviceId: String,
    @SerializedName("AppVersion")
    val appVersion: String? = null
)

// Token Response for 2FA verification
data class TokenResponse(
    val token: String,
    val email: String,
    val userId: String,
    val message: String,
    val warningRecoveryCodes: Boolean? = null,
    val deviceId: String? = null
)

// 2FA Status Response
data class TwoFactorStatusResponse(
    val is2faEnabled: Boolean,
    val hasAuthenticator: Boolean,
    val recoveryCodesLeft: Int
)

// 2FA Setup Response
data class SetupResponse(
    val sharedKey: String,
    val authenticatorUri: String,
    val manualEntryKey: String
)

// Enable 2FA Request
data class EnableRequest(
    val code: String
)

// Enable 2FA Response
data class EnableResponse(
    val message: String,
    val recoveryCodes: List<String>?
)

// Generic Message Response
data class MessageResponse(
    val message: String
)

// Recovery Codes Response
data class RecoveryCodesResponse(
    val recoveryCodes: List<String>,
    val message: String
)

// ===== LOGOUT =====
// Request body for POST /api/logout
data class LogoutRequest(
    @SerializedName("refreshToken")
    val refreshToken: String,
    @SerializedName("deviceId")
    val deviceId: String? = null
)

// Response from POST /api/logout
data class LogoutResponse(
    @SerializedName("message")
    val message: String,
    @SerializedName("success")
    val success: Boolean? = true
)

/**
 * Error response returned when device limit is exceeded (409 Conflict).
 * Backend may optionally include devices array for pre-login device management.
 */
data class DeviceLimitErrorResponse(
    @SerializedName("message")
    val message: String,
    @SerializedName("errorCode")
    val errorCode: String, // "DEVICE_LIMIT_EXCEEDED"
    @SerializedName("currentDevices")
    val currentDevices: Int? = null,
    @SerializedName("maxDevices")
    val maxDevices: Int? = null,
    @SerializedName("planType")
    val planType: String? = null, // "Free", "Pro"
    @SerializedName("email")
    val email: String? = null,
    @SerializedName("userId")
    val userId: String? = null,
    @SerializedName("devices")
    val devices: List<DeviceDto>? = null // Optional: device list for first-login scenario
)

// ===== DEVICE MANAGEMENT =====

/**
 * Device information returned from device management endpoints.
 */
data class DeviceDto(
    @SerializedName("id")
    val id: Int,
    @SerializedName("deviceId")
    val deviceId: String? = null,
    @SerializedName("deviceIdHash")
    val deviceIdHash: String? = null,
    @SerializedName("deviceName")
    val deviceName: String? = null,
    @SerializedName("deviceType")
    val deviceType: String? = null,
    @SerializedName("osVersion")
    val osVersion: String? = null,
    @SerializedName("appVersion")
    val appVersion: String? = null,
    @SerializedName("lastSeenAt")
    val lastSeenAt: String? = null, // ISO 8601 timestamp
    @SerializedName("firstSeenAt")
    val firstSeenAt: String? = null, // ISO 8601 timestamp
    @SerializedName("isActive")
    val isActive: Boolean = false,
    @SerializedName("isCurrent")
    val isCurrent: Boolean = false,
    @SerializedName("daysSinceLastSeen")
    val daysSinceLastSeen: Int? = null,
    @SerializedName("deviceNickname")
    val deviceNickname: String? = null,
    @SerializedName("createdAt")
    val createdAt: String? = null // ISO 8601 timestamp (optional, may not be in all responses)
)

/**
 * Response from GET /api/devices
 */
data class DeviceListResponse(
    @SerializedName("devices")
    val devices: List<DeviceDto>,
    @SerializedName("message")
    val message: String? = null
)

/**
 * Response from single device operations (remove/delete)
 */
data class DeviceActionResponse(
    @SerializedName("success")
    val success: Boolean,
    @SerializedName("message")
    val message: String
)

/**
 * Response from bulk device operations (remove-all-others, remove-all-inactive)
 */
data class BulkActionResponse(
    @SerializedName("success")
    val success: Boolean,
    @SerializedName("message")
    val message: String,
    @SerializedName("devicesRemoved")
    val devicesRemoved: Int = 0
)

/**
 * Sealed class for API error handling with specific error types
 */
sealed class ApiError {
    data class RateLimited(val retryAfterSeconds: Int) : ApiError()
    data class Unauthorized(val message: String) : ApiError()
    data class DeviceProtected(val message: String) : ApiError()
    data class ServerError(val statusCode: Int, val message: String) : ApiError()
    data class NetworkError(val throwable: Throwable) : ApiError()
    data class Unknown(val statusCode: Int, val message: String?) : ApiError()
}

// ===== PRE-AUTH DEVICE MANAGEMENT =====

/**
 * Request for removing device before login (password-based authentication)
 */
data class PreAuthDeviceRemovalRequest(
    @SerializedName("email")
    val email: String,
    @SerializedName("password")
    val password: String,
    @SerializedName("deviceIdToRemove")
    val deviceIdToRemove: Int
)

/**
 * Request for removing multiple devices before login
 */
data class PreAuthMultipleDeviceRemovalRequest(
    @SerializedName("email")
    val email: String,
    @SerializedName("password")
    val password: String,
    @SerializedName("deviceIdsToRemove")
    val deviceIdsToRemove: List<Int>
)

/**
 * Request for removing device before login (OAuth-based authentication)
 */
data class PreAuthOAuthDeviceRemovalRequest(
    @SerializedName("idToken")
    val idToken: String,
    @SerializedName("provider")
    val provider: String = "Google",
    @SerializedName("deviceIdToRemove")
    val deviceIdToRemove: Int
)

/**
 * Request for removing multiple devices before login (OAuth)
 */
data class PreAuthOAuthMultipleDeviceRemovalRequest(
    @SerializedName("idToken")
    val idToken: String,
    @SerializedName("provider")
    val provider: String = "Google",
    @SerializedName("deviceIdsToRemove")
    val deviceIdsToRemove: List<Int>
)

/**
 * Response from pre-auth device removal operations
 */
data class DeviceRemovalResponse(
    @SerializedName("success")
    val success: Boolean,
    @SerializedName("message")
    val message: String,
    @SerializedName("deviceId")
    val deviceId: String? = null,
    @SerializedName("removedDeviceCount")
    val removedDeviceCount: Int = 0,
    @SerializedName("removedDeviceIds")
    val removedDeviceIds: List<String>? = null
)

