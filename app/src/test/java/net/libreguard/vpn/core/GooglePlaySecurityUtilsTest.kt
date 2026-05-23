package net.libreguard.vpn.core
import com.android.billingclient.api.BillingClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
class GooglePlaySecurityUtilsTest {
    @Test
    fun obfuscateId_returnsStableSha256Digest() {
        val digest = GooglePlaySecurityUtils.obfuscateId("user@example.com")
        assertEquals(64, digest?.length)
        assertEquals(digest, GooglePlaySecurityUtils.obfuscateId("user@example.com"))
        assertNotEquals(digest, GooglePlaySecurityUtils.obfuscateId("other@example.com"))
        assertTrue(digest?.all { it.isDigit() || it in 'a'..'f' } == true)
    }
    @Test
    fun obfuscateId_returnsNullForBlankValues() {
        assertNull(GooglePlaySecurityUtils.obfuscateId(null))
        assertNull(GooglePlaySecurityUtils.obfuscateId("   "))
    }
    @Test
    fun billingMessage_mapsTransientPlayErrors() {
        assertEquals(
            "Lost connection to Google Play. Please try again.",
            GooglePlaySecurityUtils.billingMessage(BillingClient.BillingResponseCode.SERVICE_DISCONNECTED)
        )
        assertEquals(
            "Google Play is temporarily unavailable. Check your network and try again.",
            GooglePlaySecurityUtils.billingMessage(BillingClient.BillingResponseCode.NETWORK_ERROR)
        )
    }
    @Test
    fun billingMessage_mapsOwnedAndRestoreStates() {
        assertEquals(
            "You already own this subscription. Restoring your purchase…",
            GooglePlaySecurityUtils.billingMessage(BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED)
        )
        assertEquals(
            "No Google Play subscription was found to restore for this account.",
            GooglePlaySecurityUtils.billingMessage(BillingClient.BillingResponseCode.ITEM_NOT_OWNED)
        )
    }
}
