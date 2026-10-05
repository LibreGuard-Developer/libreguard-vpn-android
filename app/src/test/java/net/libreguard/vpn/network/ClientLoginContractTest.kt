package net.libreguard.vpn.network

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class ClientLoginContractTest {
    private val capability = "a".repeat(43)
    private val expiry = "2026-10-05T12:10:00Z"

    @Test fun linking_and_device_limit_have_distinct_409_contracts() {
        val linking = ClientLoginResponse(outcome = "accountLinkRequired", errorCode = "GOOGLE_LINK_REQUIRED",
            accountLinkToken = capability, expiresAt = expiry)
        val quota = ClientLoginResponse(outcome = "deviceSelectionRequired", errorCode = "DEVICE_LIMIT_EXCEEDED",
            loginContinuationToken = capability, devices = listOf(DeviceDto(id = 1)),
            currentDevices = 1, maxDevices = 1, expiresAt = expiry)
        assertTrue(decodeClientLoginStep(linking, 409, "device") is ClientLoginStep.LinkPassword)
        assertTrue(decodeClientLoginStep(quota, 409, "device") is ClientLoginStep.Devices)
        assertThrows(IllegalArgumentException::class.java) { decodeClientLoginStep(quota.copy(errorCode = "GOOGLE_LINK_REQUIRED"), 409, "device") }
        assertThrows(IllegalArgumentException::class.java) { decodeClientLoginStep(linking, 200, "device") }
    }

    @Test fun pending_outcomes_cannot_smuggle_session_tokens() {
        val pending = ClientLoginResponse(outcome = "twoFactorRequired", pendingLoginToken = capability, expiresAt = expiry)
        assertThrows(IllegalArgumentException::class.java) { decodeClientLoginStep(pending.copy(token = "jwt"), 200, "device") }
        assertThrows(IllegalArgumentException::class.java) { decodeClientLoginStep(pending.copy(refreshToken = "refresh"), 200, "device") }
        assertThrows(IllegalArgumentException::class.java) { decodeClientLoginStep(pending.copy(pendingLoginToken = "short"), 200, "device") }
    }

    @Test fun signed_in_requires_the_original_device_and_both_credentials() {
        val signedIn = ClientLoginResponse(outcome = "signedIn", token = "jwt", refreshToken = "refresh", userId = "user", deviceId = "original")
        assertTrue(decodeClientLoginStep(signedIn, 200, "original") is ClientLoginStep.SignedIn)
        assertThrows(IllegalArgumentException::class.java) { decodeClientLoginStep(signedIn, 200, "probe_device") }
        assertThrows(IllegalArgumentException::class.java) { decodeClientLoginStep(signedIn.copy(refreshToken = null), 200, "original") }
    }

    @Test fun errors_distinguish_invalid_factors_expiry_and_throttling() {
        assertNotEquals(clientLoginErrorMessage("INVALID_2FA_CODE", null), clientLoginErrorMessage("CLIENT_LOGIN_ATTEMPT_INVALID", null))
        assertNotEquals(clientLoginErrorMessage("INVALID_RECOVERY_CODE", null), clientLoginErrorMessage("ACCOUNT_LOCKED", null))
        assertTrue(clientLoginErrorMessage("RATE_LIMIT_EXCEEDED", "35").contains("35 seconds"))
        assertTrue(clientLoginErrorMessage("SECURITY_CONTROL_UNAVAILABLE", null).contains("temporarily unavailable"))
    }

    @Test fun continuation_posts_only_the_capability_and_selected_ids() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(409).setBody(Gson().toJson(ClientLoginResponse(
                outcome = "deviceSelectionRequired", errorCode = "DEVICE_LIMIT_EXCEEDED",
                loginContinuationToken = capability, expiresAt = expiry, devices = emptyList(), currentDevices = 1, maxDevices = 1))))
            val api = Retrofit.Builder().baseUrl(server.url("/")).client(OkHttpClient.Builder().retryOnConnectionFailure(false).build())
                .addConverterFactory(GsonConverterFactory.create()).build().create(ApiService::class.java)
            val response = api.continueClientLogin(ClientContinueRequest(capability, listOf(7)))
            assertEquals(409, response.code())
            val request = server.takeRequest()
            assertEquals("/api/login/client/continue", request.path)
            assertNull(request.getHeader("Authorization"))
            val fields = Gson().fromJson(request.body.readUtf8(), Map::class.java)
            assertEquals(setOf("loginContinuationToken", "deviceIdsToRemove"), fields.keys)
            assertEquals(listOf(7.0), fields["deviceIdsToRemove"])
            assertEquals(capability, fields["loginContinuationToken"])
        } finally { server.shutdown() }
    }
}
