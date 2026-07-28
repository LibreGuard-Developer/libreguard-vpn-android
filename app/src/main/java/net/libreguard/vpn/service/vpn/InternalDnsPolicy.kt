package net.libreguard.vpn.service.vpn

/** Client DNS policy shared by every supported VPN protocol. */
object InternalDnsPolicy {
    const val REGULAR_RESOLVER = "10.254.0.53"

    private val legacyDnsDirective = Regex(
        pattern = "^dhcp-option\\s+dns(?:6)?(?:\\s|$)",
        option = RegexOption.IGNORE_CASE
    )
    private val modernDnsDirective = Regex(
        pattern = "^dns\\s+server(?:\\s|$)",
        option = RegexOption.IGNORE_CASE
    )
    private val pullFilterDirective = Regex(
        pattern = "^pull-filter(?:\\s|$)",
        option = RegexOption.IGNORE_CASE
    )
    private val dnsPullFilterTarget = Regex(
        pattern = "(?:dhcp-option\\s+dns|dns\\s+server)",
        option = RegexOption.IGNORE_CASE
    )

    fun authoritativeIkev2DnsServers(downloadedDnsServers: String?): String {
        @Suppress("UNUSED_VARIABLE")
        val ignoredDownloadedValue = downloadedDnsServers
        return REGULAR_RESOLVER
    }

    /** Removes profile and pushed DNS choices, then applies exactly one resolver. */
    fun normalizeOpenVpnConfig(rawConfig: String): String {
        require(rawConfig.isNotBlank()) { "OpenVPN configuration is empty" }

        val retainedLines = rawConfig.lineSequence()
            .filterNot { line ->
                val trimmed = line.trim()
                legacyDnsDirective.containsMatchIn(trimmed) ||
                    modernDnsDirective.containsMatchIn(trimmed) ||
                    isDnsPullFilter(trimmed)
            }
            .toMutableList()

        while (retainedLines.lastOrNull()?.isBlank() == true) {
            retainedLines.removeAt(retainedLines.lastIndex)
        }

        retainedLines += "pull-filter ignore \"dhcp-option DNS\""
        retainedLines += "pull-filter ignore \"dns server\""
        retainedLines += "dhcp-option DNS $REGULAR_RESOLVER"
        return retainedLines.joinToString(separator = "\n", postfix = "\n")
    }

    private fun isDnsPullFilter(trimmedLine: String): Boolean {
        if (!pullFilterDirective.containsMatchIn(trimmedLine)) return false
        return dnsPullFilterTarget.containsMatchIn(trimmedLine)
    }
}
