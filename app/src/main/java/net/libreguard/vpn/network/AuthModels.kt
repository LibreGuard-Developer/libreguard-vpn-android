package net.libreguard.vpn.network

import com.google.gson.annotations.SerializedName

// Updated to match the C# LoginModel
data class AuthRequest(
    @SerializedName("Email") // Use SerializedName to ensure the JSON key is "Email"
    val email: String,
    @SerializedName("Password") // Match the C# model property name
    val password: String
)

data class AuthResponse(
    @SerializedName("token")
    val token: String?, // Token might not be present in an error response
    @SerializedName("message")
    val message: String?, // Message might not be present in a success response
    @SerializedName("requiresTwoFactor")
    val requiresTwoFactor: Boolean = false,
    @SerializedName("email")
    val email: String? = null,
    @SerializedName("userId")
    val userId: String? = null
)

// 2FA Verification Request
data class Verify2faRequest(
    val email: String,
    val twoFactorCode: String
)

// Recovery Code Verification Request
data class VerifyRecoveryRequest(
    val email: String,
    val recoveryCode: String
)

// Token Response for 2FA verification
data class TokenResponse(
    val token: String,
    val email: String,
    val userId: String,
    val message: String,
    val warningRecoveryCodes: Boolean? = null
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
