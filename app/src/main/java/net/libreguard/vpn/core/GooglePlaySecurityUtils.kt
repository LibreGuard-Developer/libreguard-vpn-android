package net.libreguard.vpn.core

import com.android.billingclient.api.BillingClient
import java.security.MessageDigest
import java.util.Locale

internal object GooglePlaySecurityUtils {
    fun obfuscateId(rawValue: String?): String? {
        val normalized = rawValue?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { "%02x".format(Locale.US, it) }
    }

    fun billingMessage(responseCode: Int): String {
        return when (responseCode) {
            BillingClient.BillingResponseCode.OK -> "Google Play request completed successfully."
            BillingClient.BillingResponseCode.USER_CANCELED -> "Purchase was cancelled."
            BillingClient.BillingResponseCode.SERVICE_DISCONNECTED -> "Lost connection to Google Play. Please try again."
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
            BillingClient.BillingResponseCode.NETWORK_ERROR -> "Google Play is temporarily unavailable. Check your network and try again."
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> "Google Play Billing is unavailable on this device or account."
            BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED -> "This device does not support Google Play subscriptions."
            BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> "This subscription offer is not currently available."
            BillingClient.BillingResponseCode.DEVELOPER_ERROR -> "This purchase request is misconfigured. Please contact support if the problem continues."
            BillingClient.BillingResponseCode.ERROR -> "Google Play reported an unexpected billing error. Please try again."
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> "You already own this subscription. Restoring your purchase…"
            BillingClient.BillingResponseCode.ITEM_NOT_OWNED -> "No Google Play subscription was found to restore for this account."
            else -> "Google Play billing failed with code $responseCode. Please try again."
        }
    }
}
