package net.libreguard.vpn.viewmodel

import net.libreguard.vpn.network.DnsSettingsResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsPreferenceUiStateTest {
    @Test
    fun `confirmed Pro enable becomes active without optimistic state`() {
        val initial = DnsPreferenceUiState(isLoaded = true, requestedEnabled = false)
        val active = initial.withConfirmedSettings(settings(requested = true, canUse = true, effective = true))

        assertTrue(active.requestedEnabled)
        assertTrue(active.canUseAdBlocking)
        assertTrue(active.effectiveEnabled)
        assertFalse(active.isSaving)
    }

    @Test
    fun `downgrade keeps requested on while pausing effectiveness`() {
        val paused = DnsPreferenceUiState().withConfirmedSettings(
            settings(requested = true, canUse = false, effective = false)
        )

        assertTrue(paused.requestedEnabled)
        assertFalse(paused.canUseAdBlocking)
        assertFalse(paused.effectiveEnabled)
    }

    @Test
    fun `renewal resumes preserved preference from authoritative response`() {
        val paused = DnsPreferenceUiState().withConfirmedSettings(
            settings(requested = true, canUse = false, effective = false)
        )
        val renewed = paused.withConfirmedSettings(
            settings(requested = true, canUse = true, effective = true)
        )

        assertTrue(renewed.requestedEnabled)
        assertTrue(renewed.canUseAdBlocking)
        assertTrue(renewed.effectiveEnabled)
    }

    @Test
    fun `global disable is distinct from a user disabling their saved preference`() {
        val unavailable = DnsPreferenceUiState().withConfirmedSettings(
            settings(requested = true, canUse = true, effective = false)
        )

        assertTrue(unavailable.requestedEnabled)
        assertTrue(unavailable.canUseAdBlocking)
        assertFalse(unavailable.effectiveEnabled)
        assertEquals("regular", unavailable.effectiveMode)
    }

    @Test
    fun `logout or account switch starts with no previous account snapshot`() {
        val cleared = DnsPreferenceUiState()
        assertFalse(cleared.isLoaded)
        assertFalse(cleared.requestedEnabled)
        assertNull(cleared.confirmationMessage)
    }

    @Test
    fun `network failure retains last confirmed preference`() {
        val confirmed = DnsPreferenceUiState().withConfirmedSettings(
            settings(requested = true, canUse = true, effective = true)
        )
        val failed = confirmed.copy(isSaving = true).withDnsRequestFailure("Network unavailable")

        assertTrue(failed.requestedEnabled)
        assertTrue(failed.effectiveEnabled)
        assertFalse(failed.isSaving)
        assertEquals("Network unavailable", failed.errorMessage)
    }

    @Test
    fun `newer request makes older response stale`() {
        val gate = DnsRequestGate()
        val first = gate.begin()
        val second = gate.begin()

        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
        gate.invalidate()
        assertFalse(gate.isCurrent(second))
    }

    private fun settings(requested: Boolean, canUse: Boolean, effective: Boolean) =
        DnsSettingsResponse(
            requestedEnabled = requested,
            canUseAdBlocking = canUse,
            effectiveEnabled = effective,
            effectiveMode = if (effective) "filtered" else "regular",
            propagationSeconds = 15
        )
}
