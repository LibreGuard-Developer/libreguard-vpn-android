package net.libreguard.vpn.network

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class RegistrationApiTest {
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
    fun `register omits newsletter consent by default`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val response = api.register(RegisterRequest("user@example.com", "Password1!"))

        assertEquals(200, response.code())
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/register", request.path)
        assertFalse(request.body.readUtf8().contains("\"newsletterConsent\""))
    }

    @Test
    fun `register sends newsletter consent when the user opted in`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val response = api.register(
            RegisterRequest(
                email = "user@example.com",
                password = "Password1!",
                newsletterConsent = true
            )
        )

        assertEquals(200, response.code())
        assertTrue(server.takeRequest().body.readUtf8().contains("\"newsletterConsent\":true"))
    }
}
