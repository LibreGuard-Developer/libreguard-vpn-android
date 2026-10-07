package net.libreguard.vpn.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OkHttpPingTransportTest {
    private lateinit var server: MockWebServer
    private val endpoint = ProbeEndpoint("vpn.example", 5001)

    @Before fun setup() { server = MockWebServer(); server.start() }
    @After fun teardown() { server.shutdown() }

    @Test fun consumesBodiesAndReusesWarmConnectionForAllSamples() = runBlocking {
        repeat(4) { server.enqueue(MockResponse().setBody("""{"pong":true,"timestamp":123}""")) }
        assertNotNull(WarmLatencyEngine(transport()).measureEndpoint(endpoint))
        repeat(4) { sequence ->
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals(sequence, request.sequenceNumber)
            assertEquals("/ping", request.path)
            assertEquals("GET", request.method)
            assertEquals("no-cache, no-store", request.getHeader("Cache-Control"))
            assertNull(request.getHeader("Authorization"))
        }
    }

    @Test fun networkResetClosesIdleConnectionBeforeNextWarmPass() = runBlocking {
        repeat(8) { server.enqueue(MockResponse().setBody("""{"pong":true}""")) }
        val transport = transport()
        val engine = WarmLatencyEngine(transport)
        repeat(2) { pass ->
            if (pass == 1) transport.resetConnections()
            assertNotNull(engine.measureEndpoint(endpoint))
            repeat(4) { sequence ->
                assertEquals(sequence, server.takeRequest(1, TimeUnit.SECONDS)!!.sequenceNumber)
            }
        }
    }

    @Test fun rejectsWrongPongTypesBadStatusAndMalformedBodies() = runBlocking {
        val transport = transport()
        val responses = listOf(
            MockResponse().setBody("""{"pong":false}"""),
            MockResponse().setBody("""{"pong":"true"}"""),
            MockResponse().setBody("""{"pong":1}"""),
            MockResponse().setBody("{}"),
            MockResponse().setBody("not-json"),
            MockResponse().setResponseCode(503).setBody("""{"pong":true}""")
        )
        for (response in responses) {
            server.enqueue(response)
            assertNull(transport.ping(endpoint, warmUp = false))
        }
    }

    @Test fun redirectIsNotFollowed() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/other")))
        assertNull(transport().ping(endpoint, warmUp = true))
        assertEquals(1, server.requestCount)
    }

    @Test(timeout = 10_000) fun requestHasThreeSecondTotalTimeoutAndNoRetry() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        assertNull(transport().ping(endpoint, warmUp = true))
        assertEquals(1, server.requestCount)
    }

    @Test(timeout = 10_000) fun coroutineCancellationCancelsUnderlyingHttpCall() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val cancelled = CountDownLatch(1)
        val transport = transport { if (it.contains("cancelled=true")) cancelled.countDown() }
        val job = async { transport.ping(endpoint, warmUp = true) }
        assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) })
        job.cancelAndJoin()
        assertTrue(withContext(Dispatchers.IO) { cancelled.await(2, TimeUnit.SECONDS) })
        assertEquals(1, server.requestCount)
    }

    private fun transport(log: (String) -> Unit = {}) = OkHttpPingTransport(
        log = log,
        urlForEndpoint = { server.url("/ping") }
    )
}

