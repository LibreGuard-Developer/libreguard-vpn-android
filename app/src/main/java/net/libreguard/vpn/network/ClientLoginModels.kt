package net.libreguard.vpn.network

data class ClientDeviceRequest(
    val deviceId: String,
    val appVersion: String?,
    val devicePublicKey: String?,
    val devicePublicKeyId: String?,
    val devicePublicKeyAlgorithm: String?,
    val newsletterConsent: Boolean = false
)
data class ClientPasswordRequest(
    val identifier: String,
    val password: String,
    val deviceId: String,
    val appVersion: String?,
    val devicePublicKey: String?,
    val devicePublicKeyId: String?,
    val devicePublicKeyAlgorithm: String?
)
data class ClientGoogleBeginResponse(
    val attemptId: String,
    val redemptionToken: String,
    val nonce: String,
    val clientId: String,
    val expiresAt: String
)
data class ClientGoogleCompleteRequest(val attemptId: String, val redemptionToken: String, val idToken: String)
data class ClientFactorRequest(val pendingLoginToken: String, val twoFactorCode: String? = null, val recoveryCode: String? = null)
data class ClientContinueRequest(val loginContinuationToken: String, val deviceIdsToRemove: List<Int> = emptyList())
data class ClientLinkPasswordRequest(val accountLinkToken: String, val identifier: String, val password: String)
data class ClientLinkConfirmRequest(val accountLinkConfirmationToken: String)

// Pending response bodies are never persisted or included in diagnostics.
data class ClientLoginResponse(
    val outcome: String? = null,
    val errorCode: String? = null,
    val message: String? = null,
    val token: String? = null,
    val refreshToken: String? = null,
    val userId: String? = null,
    val email: String? = null,
    val deviceId: String? = null,
    val activeDevices: Int? = null,
    val currentDevices: Int? = null,
    val maxDevices: Int? = null,
    val planType: String? = null,
    val serviceAccess: Boolean? = null,
    val requiresRenewal: Boolean? = null,
    val warningRecoveryCodes: Boolean = false,
    val pendingLoginToken: String? = null,
    val loginContinuationToken: String? = null,
    val accountLinkToken: String? = null,
    val accountLinkConfirmationToken: String? = null,
    val googleEmail: String? = null,
    val accountIdentifier: String? = null,
    val expiresAt: String? = null,
    val devices: List<DeviceDto>? = null
)
