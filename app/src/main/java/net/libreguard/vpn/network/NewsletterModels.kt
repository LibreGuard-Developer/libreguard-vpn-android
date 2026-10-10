package net.libreguard.vpn.network

data class NewsletterSettingsResponse(
    val accountId: String,
    val subscribed: Boolean,
    val canSubscribe: Boolean,
    val promptPending: Boolean,
    val consentText: String,
    val consentTextVersion: String,
    val privacyUrl: String,
    val revision: String
)

data class UpdateNewsletterRequest(
    val subscribed: Boolean,
    val expectedRevision: String,
    val consentTextVersion: String? = null
)

data class NewsletterOnboardingRequest(
    val decision: String,
    val expectedRevision: String,
    val consentTextVersion: String? = null
)

data class NewsletterErrorResponse(val errorCode: String? = null, val message: String? = null)
