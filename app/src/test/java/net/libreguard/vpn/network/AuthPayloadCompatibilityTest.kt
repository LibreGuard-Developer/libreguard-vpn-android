package net.libreguard.vpn.network

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class AuthPayloadCompatibilityTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ApiService

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient.Builder().build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun `ordinary authentication payloads do not contain newsletter consent`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        api.login(AuthRequest(email = "user@example.com", password = "Password1!"))
        assertEquals(
            "{\"email\":\"user@example.com\",\"password\":\"Password1!\",\"devicePublicKeyAlgorithm\":\"RSA-OAEP-256\"}",
            server.takeRequest().body.readUtf8()
        )

        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        api.refreshToken(RefreshTokenRequest(refreshToken = "refresh", deviceId = "device-123")).execute()
        assertFalse(server.takeRequest().body.readUtf8().contains("\"newsletterConsent\""))

        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        api.verify2fa(
            Verify2faRequest(
                email = "user@example.com",
                twoFactorCode = "123456",
                pendingLoginToken = "pending",
                deviceId = "device-123"
            )
        )
        assertFalse(server.takeRequest().body.readUtf8().contains("\"newsletterConsent\""))

        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        api.verifyRecoveryCode(
            VerifyRecoveryRequest(
                email = "user@example.com",
                recoveryCode = "ABCD-EFGH-IJKL",
                pendingLoginToken = "pending",
                deviceId = "device-123"
            )
        )
        assertFalse(server.takeRequest().body.readUtf8().contains("\"newsletterConsent\""))
    }
}
