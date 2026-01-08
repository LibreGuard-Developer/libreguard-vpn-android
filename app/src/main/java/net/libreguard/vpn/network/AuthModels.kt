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
    val userId: String? = null
)
