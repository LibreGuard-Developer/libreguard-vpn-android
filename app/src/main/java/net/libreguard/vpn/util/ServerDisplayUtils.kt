package net.libreguard.vpn.util

import net.libreguard.vpn.network.RemoteVpnServer
import java.util.Locale

private val countryFlagEmojiMap = mapOf(
    "us" to "🇺🇸",
    "usa" to "🇺🇸",
    "united states" to "🇺🇸",
    "united states of america" to "🇺🇸",
    "gb" to "🇬🇧",
    "uk" to "🇬🇧",
    "united kingdom" to "🇬🇧",
    "great britain" to "🇬🇧",
    "jp" to "🇯🇵",
    "japan" to "🇯🇵",
    "de" to "🇩🇪",
    "germany" to "🇩🇪",
    "nl" to "🇳🇱",
    "netherlands" to "🇳🇱",
    "ca" to "🇨🇦",
    "canada" to "🇨🇦",
    "fr" to "🇫🇷",
    "france" to "🇫🇷",
    "au" to "🇦🇺",
    "australia" to "🇦🇺",
    "sg" to "🇸🇬",
    "singapore" to "🇸🇬",
    "ch" to "🇨🇭",
    "switzerland" to "🇨🇭",
    "se" to "🇸🇪",
    "sweden" to "🇸🇪",
    "no" to "🇳🇴",
    "norway" to "🇳🇴",
    "it" to "🇮🇹",
    "italy" to "🇮🇹",
    "es" to "🇪🇸",
    "spain" to "🇪🇸",
    "br" to "🇧🇷",
    "brazil" to "🇧🇷",
    "in" to "🇮🇳",
    "india" to "🇮🇳",
    "kr" to "🇰🇷",
    "korea" to "🇰🇷",
    "south korea" to "🇰🇷",
    "hk" to "🇭🇰",
    "hong kong" to "🇭🇰",
    "ie" to "🇮🇪",
    "ireland" to "🇮🇪",
    "pl" to "🇵🇱",
    "poland" to "🇵🇱",
    "fi" to "🇫🇮",
    "finland" to "🇫🇮"
)

fun getFlagEmoji(country: String): String {
    val normalizedCountry = country.trim().lowercase(Locale.ROOT)
    return countryFlagEmojiMap[normalizedCountry] ?: "🏳️"
}

fun getPrimaryServerLabel(server: RemoteVpnServer): String {
    return server.city.ifBlank { server.serverName }
}

fun getSecondaryServerLabel(server: RemoteVpnServer): String {
    return server.serverName.ifBlank { server.country }
}
