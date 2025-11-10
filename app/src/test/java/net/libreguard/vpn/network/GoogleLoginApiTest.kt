package net.libreguard.vpn.network

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    fun `loginWithGoogle handles error code`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        val response = api.loginWithGoogle(GoogleLoginRequest(idToken = "bad-token"))
        assertEquals(401, response.code())
    }
}

