package net.libreguard.vpn.network

data class DnsSettingsResponse(
    val requestedEnabled: Boolean,
    val canUseAdBlocking: Boolean,
    val effectiveEnabled: Boolean,
    val effectiveMode: String,
    val propagationSeconds: Int
)

data class UpdateDnsSettingsRequest(
    val adBlockingEnabled: Boolean
)

data class DnsSettingsErrorResponse(
    val errorCode: String?,
    val message: String?,
    val settings: DnsSettingsResponse?
)
