package net.libreguard.vpn.network

sealed interface ClientLoginStep {
    data object Start : ClientLoginStep
    data class Factor(val capability: String, val expiresAt: String) : ClientLoginStep
    data class Devices(
        val capability: String, val expiresAt: String, val devices: List<DeviceDto>,
        val currentDevices: Int, val maxDevices: Int
    ) : ClientLoginStep
    data class LinkPassword(val capability: String, val expiresAt: String) : ClientLoginStep
    data class LinkConfirmation(
        val capability: String, val expiresAt: String, val googleEmail: String, val accountIdentifier: String
    ) : ClientLoginStep
    data class SignedIn(val response: ClientLoginResponse) : ClientLoginStep
}

// Fail closed on ambiguous response shapes; HTTP 409 by itself never opens a device picker.
internal fun decodeClientLoginStep(body: ClientLoginResponse, status: Int, deviceId: String): ClientLoginStep {
    fun capability(value: String?): String = requireNotNull(value?.takeIf { it.length in 32..128 })
    fun expiry(): String = requireNotNull(body.expiresAt?.takeIf { it.isNotBlank() })
    if (body.outcome != "signedIn") require(body.token == null && body.refreshToken == null)
    return when (body.outcome) {
        "signedIn" -> {
            require(status == 200 && body.deviceId == deviceId)
            require(!body.token.isNullOrBlank() && !body.refreshToken.isNullOrBlank() && !body.userId.isNullOrBlank())
            ClientLoginStep.SignedIn(body)
        }
        "twoFactorRequired" -> {
            require(status == 200)
            ClientLoginStep.Factor(capability(body.pendingLoginToken), expiry())
        }
        "deviceSelectionRequired" -> {
            require(status == 409 && body.errorCode == "DEVICE_LIMIT_EXCEEDED")
            val devices = requireNotNull(body.devices)
            require(devices.all { it.id > 0 })
            ClientLoginStep.Devices(capability(body.loginContinuationToken), expiry(), devices,
                requireNotNull(body.currentDevices), requireNotNull(body.maxDevices))
        }
        "accountLinkRequired" -> {
            require(status == 409 && body.errorCode == "GOOGLE_LINK_REQUIRED")
            ClientLoginStep.LinkPassword(capability(body.accountLinkToken), expiry())
        }
        "accountLinkConfirmationRequired" -> {
            require(status == 200)
            ClientLoginStep.LinkConfirmation(capability(body.accountLinkConfirmationToken), expiry(),
                requireNotNull(body.googleEmail), requireNotNull(body.accountIdentifier))
        }
        else -> error("Unrecognized login outcome")
    }
}

internal fun clientLoginErrorMessage(code: String?, retryAfter: String?): String = when (code) {
    "INVALID_2FA_CODE" -> "The verification code is incorrect. Try again."
    "INVALID_RECOVERY_CODE" -> "The recovery code is incorrect or has already been used."
    "INVALID_CREDENTIALS" -> "The account identifier or password is incorrect."
    "ACCOUNT_LOCKED", "GOOGLE_IDENTITY_REJECTED" -> "This account cannot sign in."
    "CLIENT_LOGIN_ATTEMPT_INVALID", "INVALID_GOOGLE_PROOF" -> "This sign-in attempt is no longer valid. Please start again."
    "RATE_LIMIT_EXCEEDED" -> "Too many attempts. Try again" + (retryAfter?.toLongOrNull()?.let { " in $it seconds." } ?: " later.")
    "SECURITY_CONTROL_UNAVAILABLE", "CLIENT_LOGIN_UNAVAILABLE" -> "Sign-in is temporarily unavailable. Please try again later."
    "GOOGLE_LINK_CONFLICT" -> "Google could not be linked to this account. Please start again."
    "EMAIL_NOT_VERIFIED" -> "Please verify your email before signing in."
    "SUBSCRIPTION_REQUIRED" -> "An active subscription is required."
    else -> "Sign-in could not be completed. Please try again."
}
