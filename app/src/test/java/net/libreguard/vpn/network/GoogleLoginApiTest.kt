package net.libreguard.vpn.network

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class GoogleLoginApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ApiService

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        val client = OkHttpClient.Builder().build()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun `loginWithGoogle returns token on success`() = runBlocking {
        val json = """{"token":"jwt123","email":"user@example.com","userId":"uid-1","provider":"Google"}""".trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(json))

        val response = api.loginWithGoogle(GoogleLoginRequest(idToken = "dummy-id-token"))
        assertEquals(true, response.isSuccessful)
        val body = response.body()
        assertNotNull(body)
        assertEquals("jwt123", body!!.token)
        assertEquals("user@example.com", body.email)
        assertEquals("uid-1", body.userId)
        assertEquals("Google", body.provider)
    }

    @Test
    fun `loginWithGoogle deserializes pending login token when two factor is required`() = runBlocking {
        val json = """
            {
              "requiresTwoFactor": true,
              "pendingLoginToken": "pending-login-token",
              "email": "user@example.com",
              "userId": "uid-1",
              "provider": "Google"
            }
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(json))

        val response = api.loginWithGoogle(GoogleLoginRequest(idToken = "dummy-id-token"))

        assertEquals(true, response.isSuccessful)
        val body = response.body()
        assertNotNull(body)
        assertEquals(true, body!!.requiresTwoFactor)
        assertEquals("pending-login-token", body.pendingLoginToken)
        assertEquals("user@example.com", body.email)
        assertEquals("uid-1", body.userId)
    }

    @Test
    fun `loginWithGoogle sends app version in request body`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val response = api.loginWithGoogle(
            GoogleLoginRequest(
                idToken = "dummy-id-token",
                deviceId = "device-123",
                appVersion = "1.2.3",
                devicePublicKey = "base64-or-pem",
                devicePublicKeyId = "optional",
                devicePublicKeyAlgorithm = "RSA-OAEP-256"
            )
        )

        assertEquals(200, response.code())
        val request = server.takeRequest()
        val body = request.body.readUtf8()

        assertEquals("POST", request.method)
        assertEquals("/api/login/google", request.path)
        assertTrue(body.contains("\"idToken\":\"dummy-id-token\""))
        assertTrue(body.contains("\"deviceId\":\"device-123\""))
        assertTrue(body.contains("\"appVersion\":\"1.2.3\""))
    }

    @Test
    fun `verify2fa sends pending login token in request body`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val response = api.verify2fa(
            Verify2faRequest(
                email = "user@example.com",
                twoFactorCode = "123456",
                pendingLoginToken = "pending-login-token",
                deviceId = "device-123",
                appVersion = "1.2.3"
            )
        )

        assertEquals(200, response.code())
        val request = server.takeRequest()
        val body = request.body.readUtf8()

        assertEquals("POST", request.method)
        assertEquals("/api/login/verify-2fa", request.path)
        assertTrue(body.contains("\"pendingLoginToken\":\"pending-login-token\""))
        assertTrue(body.contains("\"deviceId\":\"device-123\""))
        assertTrue(body.contains("\"appVersion\":\"1.2.3\""))
    }

    @Test
    fun `verifyRecoveryCode sends pending login token in request body`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val response = api.verifyRecoveryCode(
            VerifyRecoveryRequest(
                email = "user@example.com",
                recoveryCode = "ABCD-EFGH-IJKL",
                pendingLoginToken = "pending-login-token",
                deviceId = "device-123",
                appVersion = "1.2.3"
            )
        )

        assertEquals(200, response.code())
        val request = server.takeRequest()
        val body = request.body.readUtf8()

        assertEquals("POST", request.method)
        assertEquals("/api/login/verify-recovery-code", request.path)
        assertTrue(body.contains("\"pendingLoginToken\":\"pending-login-token\""))
        assertTrue(body.contains("\"deviceId\":\"device-123\""))
        assertTrue(body.contains("\"appVersion\":\"1.2.3\""))
    }

    @Test
    fun `loginWithGoogle surfaces app version enforcement failures`() = runBlocking {
        val json = """{"message":"This app version is not allowed to access the API.","errorCode":"APP_VERSION_BLOCKED","appVersion":"1.2.3","enforcementEnabled":true}"""
        server.enqueue(MockResponse().setResponseCode(403).setBody(json))

        val response = api.loginWithGoogle(
            GoogleLoginRequest(
                idToken = "dummy-id-token",
                deviceId = "device-123",
                appVersion = "1.2.3"
            )
        )

        assertEquals(403, response.code())
        assertEquals(json, response.errorBody()!!.string())
    }

    @Test
    fun `loginWithGoogle handles error code`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        val response = api.loginWithGoogle(GoogleLoginRequest(idToken = "bad-token"))
        assertEquals(401, response.code())
    }
}

