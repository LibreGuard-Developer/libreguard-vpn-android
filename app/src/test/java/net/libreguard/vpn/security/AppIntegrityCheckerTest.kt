package net.libreguard.vpn.security
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
class AppIntegrityCheckerTest {
    @Test
    fun normalizeDigest_removesSeparatorsAndUppercases() {
        assertEquals(
            "AABBCCDDEEFF",
            AppIntegrityChecker.normalizeDigest("aa:bb cc:dd-ee:ff")
        )
    }
    @Test
    fun parseDigestAllowlist_supportsMultipleSeparators() {
        val allowlist = AppIntegrityChecker.parseDigestAllowlist(
            "AA:BB, ccdd; EE FF\n\nAA:BB"
        )
        assertEquals(setOf("AABB", "CCDD", "EEFF"), allowlist)
    }
    @Test
    fun evaluateEnforcement_doesNotBlockDebugBuilds() {
        val decision = AppIntegrityChecker.evaluateEnforcement(
            isDebugBuild = true,
            enforcementEnabled = true,
            configuredSigningAllowlist = setOf("EXPECTED"),
            currentSigningDigests = listOf("OTHER")
        )
        assertFalse(decision.shouldBlock)
        assertNull(decision.reason)
    }
    @Test
    fun evaluateEnforcement_doesNotBlockWhenReleaseEnforcementDisabled() {
        val decision = AppIntegrityChecker.evaluateEnforcement(
            isDebugBuild = false,
            enforcementEnabled = false,
            configuredSigningAllowlist = setOf("EXPECTED"),
            currentSigningDigests = listOf("OTHER")
        )
        assertFalse(decision.shouldBlock)
        assertNull(decision.reason)
    }
    @Test
    fun evaluateEnforcement_fallsBackToWarnOnlyWhenAllowlistMissing() {
        val decision = AppIntegrityChecker.evaluateEnforcement(
            isDebugBuild = false,
            enforcementEnabled = true,
            configuredSigningAllowlist = emptySet(),
            currentSigningDigests = listOf("OTHER")
        )
        assertFalse(decision.shouldBlock)
        assertEquals("signing_allowlist_missing", decision.reason)
    }
    @Test
    fun evaluateEnforcement_allowsKeyRotationDigests() {
        val decision = AppIntegrityChecker.evaluateEnforcement(
            isDebugBuild = false,
            enforcementEnabled = true,
            configuredSigningAllowlist = setOf("OLDDIGEST", "NEWDIGEST"),
            currentSigningDigests = listOf("new:digest")
        )
        assertFalse(decision.shouldBlock)
        assertNull(decision.reason)
    }
    @Test
    fun evaluateEnforcement_blocksExplicitReleaseSigningMismatch() {
        val decision = AppIntegrityChecker.evaluateEnforcement(
            isDebugBuild = false,
            enforcementEnabled = true,
            configuredSigningAllowlist = setOf("EXPECTED"),
            currentSigningDigests = listOf("OTHER")
        )
        assertTrue(decision.shouldBlock)
        assertEquals("signing_allowlist_mismatch", decision.reason)
    }
    @Test
    fun evaluateEnforcement_warnsWhenCurrentDigestUnavailable() {
        val decision = AppIntegrityChecker.evaluateEnforcement(
            isDebugBuild = false,
            enforcementEnabled = true,
            configuredSigningAllowlist = setOf("EXPECTED"),
            currentSigningDigests = emptyList()
        )
        assertFalse(decision.shouldBlock)
        assertEquals("signing_digest_unavailable", decision.reason)
    }
}
