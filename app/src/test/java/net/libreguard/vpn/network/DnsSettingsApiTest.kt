package net.libreguard.vpn.network

import com.google.gson.Gson
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

class DnsSettingsApiTest {
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
    fun teardown() = server.shutdown()

    @Test
    fun `GET uses exact account DNS route and response shape`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(PRO_ENABLED))

        val response = api.getDnsSettings("Bearer token")
        val request = server.takeRequest()

        assertEquals("GET", request.method)
        assertEquals("/api/dns/settings", request.path)
        assertEquals("Bearer token", request.getHeader("Authorization"))
        assertTrue(response.body()!!.requestedEnabled)
        assertTrue(response.body()!!.effectiveEnabled)
        assertEquals("filtered", response.body()!!.effectiveMode)
        assertEquals(15, response.body()!!.propagationSeconds)
    }

    @Test
    fun `PUT sends only adBlockingEnabled and accepts confirmed disable`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(PRO_DISABLED))

        val response = api.updateDnsSettings(
            "Bearer token",
            UpdateDnsSettingsRequest(adBlockingEnabled = false)
        )
        val request = server.takeRequest()

        assertEquals("PUT", request.method)
        assertEquals("/api/dns/settings", request.path)
        assertEquals("{\"adBlockingEnabled\":false}", request.body.readUtf8())
        assertFalse(response.body()!!.requestedEnabled)
        assertFalse(response.body()!!.effectiveEnabled)
    }

    @Test
    fun `PRO_REQUIRED error retains nested authoritative settings snapshot`() = runBlocking {
        val errorJson = """
            {
              "errorCode":"PRO_REQUIRED",
              "message":"An active Pro subscription is required.",
              "settings":{
                "requestedEnabled":true,
                "canUseAdBlocking":false,
                "effectiveEnabled":false,
                "effectiveMode":"regular",
                "propagationSeconds":15
              }
            }
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(403).setBody(errorJson))

        val response = api.updateDnsSettings(
            "Bearer token",
            UpdateDnsSettingsRequest(adBlockingEnabled = true)
        )
        val parsed = Gson().fromJson(response.errorBody()!!.string(), DnsSettingsErrorResponse::class.java)

        assertEquals(403, response.code())
        assertEquals("PRO_REQUIRED", parsed.errorCode)
        assertTrue(parsed.settings!!.requestedEnabled)
        assertFalse(parsed.settings!!.canUseAdBlocking)
    }

    private companion object {
        const val PRO_ENABLED = """{"requestedEnabled":true,"canUseAdBlocking":true,"effectiveEnabled":true,"effectiveMode":"filtered","propagationSeconds":15}"""
        const val PRO_DISABLED = """{"requestedEnabled":false,"canUseAdBlocking":true,"effectiveEnabled":false,"effectiveMode":"regular","propagationSeconds":15}"""
    }
}
