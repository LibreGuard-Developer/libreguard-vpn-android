package net.libreguard.vpn.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException

class SavedSessionRecoveryTest {
    @Test fun expiredAccessTokenWithRefreshTimeoutKeepsSavedSignIn() = runTest {
        var saved: String? = "expired-access"
        var clears = 0
        val result = restoreSavedSession({ saved }, { throw SocketTimeoutException() }, { clears++; saved = null })
        assertEquals("expired-access", result.token)
        assertEquals(TokenValidationResult.UNAVAILABLE, result.validation)
        assertEquals("expired-access", saved)
        assertEquals(0, clears)
    }

    @Test fun unavailableCheckDoesNotClearCredentials() = runTest {
        var cleared = false
        val result = restoreSavedSession({ "saved" }, { TokenValidationResult.UNAVAILABLE }, { cleared = true })
        assertEquals("saved", result.token)
        assertFalse(cleared)
    }

    @Test fun successfulRotationUsesRefreshedToken() = runTest {
        var saved: String? = "old-access"
        val result = restoreSavedSession({ saved }, { saved = "fresh-access"; TokenValidationResult.VALID }, { fail("Unexpected logout") })
        assertEquals("fresh-access", result.token)
        assertEquals(TokenValidationResult.VALID, result.validation)
    }

    @Test fun confirmedRejectionClearsSavedSessionOnce() = runTest {
        var saved: String? = "rejected"
        var clears = 0
        val result = restoreSavedSession({ saved }, { TokenValidationResult.REVOKED }, { clears++; saved = null })
        assertNull(result.token)
        assertNull(saved)
        assertEquals(1, clears)
    }

    @Test fun missingSessionDoesNotValidateOrInvokeLogout() = runTest {
        val result = restoreSavedSession({ null }, { fail("No credentials to validate"); TokenValidationResult.VALID }, { fail("No saved session") })
        assertNull(result.token)
    }

    @Test fun cancellationDoesNotClearSessionOrEmitNetworkFailure() = runTest {
        val cancellation = CancellationException("activity destroyed")
        try {
            restoreSavedSession({ "saved" }, { throw cancellation }, { fail("Unexpected logout") }, { fail("Unexpected failure message") })
            fail("Cancellation was swallowed")
        } catch (e: CancellationException) {
            assertSame(cancellation, e)
        }
    }

    @Test fun concurrentLogoutDoesNotRestoreCapturedToken() = runTest {
        var saved: String? = "old-access"
        val result = restoreSavedSession({ saved }, { saved = null; TokenValidationResult.UNAVAILABLE }, { fail("Already logged out") })
        assertNull(result.token)
    }
}
