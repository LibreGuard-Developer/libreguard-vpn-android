package net.libreguard.vpn.util

import org.junit.Assert.*
import org.junit.Test

class AccountSessionTest {
    @Test fun refresh_cannot_write_after_logout_and_another_account_login() {
        val gate = AccountSessionGate()
        val original = gate.begin("account-a")
        gate.clear()
        val replacement = gate.begin("account-b")
        var persisted = false
        val accepted = gate.withCurrent(original) { persisted = true; "stale-token" }
        assertNull(accepted)
        assertFalse(persisted)
        assertTrue(gate.isCurrent(replacement))
    }

    @Test fun accepted_login_rotates_even_for_the_same_account_but_restoration_does_not() {
        val gate = AccountSessionGate()
        val original = gate.begin("account-a")
        assertEquals(original, gate.restore("account-a"))
        assertEquals("refreshed", gate.withCurrent(original) { "refreshed" })
        val nextLogin = gate.begin("account-a")
        assertNotEquals(original, nextLogin)
        assertFalse(gate.isCurrent(original))
        assertTrue(gate.isCurrent(nextLogin))
    }
}
