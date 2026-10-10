package net.libreguard.vpn.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import net.libreguard.vpn.network.TokenCheckResponse
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response
import java.net.SocketTimeoutException

class TokenValidationResultTest {
    @Test fun successfulValidationAllowsConnection() = runTest {
        assertEquals(TokenValidationResult.VALID, validateTokenRequest({
            Response.success(TokenCheckResponse(true, null))
        }))
    }

    @Test fun explicitInvalidTokenRequiresLogin() = runTest {
        assertEquals(TokenValidationResult.REVOKED, validateTokenRequest({
            Response.success(TokenCheckResponse(false, null))
        }))
    }

    @Test fun onlyAuthenticationHttpErrorsRevokeSession() = runTest {
        for (code in listOf(401, 403)) {
            assertEquals(TokenValidationResult.REVOKED, validateTokenRequest({
                Response.error(code, "".toResponseBody())
            }))
        }
        for (code in listOf(429, 500, 502, 503)) {
            assertEquals(TokenValidationResult.UNAVAILABLE, validateTokenRequest({
                Response.error(code, "".toResponseBody())
            }))
        }
    }

    @Test fun missingResponseBodyDoesNotRevokeSession() = runTest {
        assertEquals(TokenValidationResult.UNAVAILABLE, validateTokenRequest({
            Response.success<TokenCheckResponse>(null)
        }))
    }

    @Test fun connectTimeoutIsRetryableAndReportedOnce() = runTest {
        val failure = SocketTimeoutException("connect timed out")
        val failures = mutableListOf<Exception>()
        assertEquals(TokenValidationResult.UNAVAILABLE, validateTokenRequest({ throw failure }, failures::add))
        assertEquals(listOf(failure), failures)
    }

    @Test fun validationCancellationIsPreserved() = runTest {
        val cancellation = CancellationException("attempt cancelled")
        var logged = false
        try {
            validateTokenRequest({ throw cancellation }, { logged = true })
            fail("Cancellation was swallowed")
        } catch (e: CancellationException) {
            assertSame(cancellation, e)
        }
        assertFalse(logged)
    }

    @Test fun refreshTransportFailureAndRateLimitDoNotRevokeSession() {
        assertEquals(TokenValidationResult.UNAVAILABLE,
            TokenRefreshResult.ExceptionFailure(SocketTimeoutException()).validationResult())
        assertEquals(TokenValidationResult.UNAVAILABLE,
            TokenRefreshResult.RateLimited(30).validationResult())
        assertEquals(TokenValidationResult.UNAVAILABLE,
            TokenRefreshResult.Failure(503).validationResult())
        assertEquals(TokenValidationResult.UNAVAILABLE,
            TokenRefreshResult.Failure(200, message = "Missing tokens").validationResult())
    }

    @Test fun refreshRejectionRequiresLogin() {
        assertEquals(TokenValidationResult.REVOKED, TokenRefreshResult.Failure(401).validationResult())
        assertEquals(TokenValidationResult.REVOKED, TokenRefreshResult.Failure(403).validationResult())
        assertEquals(TokenValidationResult.VALID, TokenRefreshResult.Skipped.validationResult())
        assertEquals(TokenValidationResult.VALID, TokenRefreshResult.Success("test-token").validationResult())
    }

    @Test fun refreshCancellationIsPreserved() {
        val cancellation = CancellationException("refresh cancelled")
        try {
            TokenRefreshResult.ExceptionFailure(cancellation).validationResult()
            fail("Cancellation was swallowed")
        } catch (e: CancellationException) {
            assertSame(cancellation, e)
        }
    }
}
