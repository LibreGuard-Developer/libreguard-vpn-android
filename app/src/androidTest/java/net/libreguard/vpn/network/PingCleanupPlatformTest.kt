package net.libreguard.vpn.network

import android.os.StrictMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class PingCleanupPlatformTest {
    @Test fun warmHttpsPoolCanBeResetFromMainWithNetworkStrictModeEnabled() = runBlocking {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.start()
        try {
            repeat(8) { server.enqueue(MockResponse().setBody("""{"pong":true}""")) }
            val cleanupFailed = java.util.concurrent.atomic.AtomicBoolean(false)
            val transport = OkHttpPingTransport(
                log = { if (it.startsWith("Connection cleanup failed")) cleanupFailed.set(true) },
                client = OkHttpClient.Builder().sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager).build(),
                urlForEndpoint = { server.url("/ping") }
            )
            val engine = WarmLatencyEngine(transport)
            val endpoint = ProbeEndpoint("localhost", server.port)
            assertNotNull(engine.measureEndpoint(endpoint))
            repeat(4) { assertEquals(it, server.takeRequest(2, TimeUnit.SECONDS)!!.sequenceNumber) }
            withContext(Dispatchers.Main) {
                val previous = StrictMode.getThreadPolicy()
                StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder(previous).detectNetwork().penaltyDeathOnNetwork().build())
                try { transport.resetConnections() } finally { StrictMode.setThreadPolicy(previous) }
            }
            assertFalse("Cleanup must not hide a StrictMode network violation", cleanupFailed.get())
            assertNotNull(engine.measureEndpoint(endpoint))
            repeat(4) { assertEquals(it, server.takeRequest(2, TimeUnit.SECONDS)!!.sequenceNumber) }
            transport.resetConnections()
        } finally { server.shutdown() }
    }

    @Test fun resettingDuringAProbeDiscardsOldWorkAndAllowsANewConnection() = runBlocking {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.start()
        try {
            val transport = OkHttpPingTransport(
                client = OkHttpClient.Builder().sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager).build(),
                urlForEndpoint = { server.url("/ping") }
            )
            val endpoint = ProbeEndpoint("localhost", server.port)
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val old = async { transport.ping(endpoint, warmUp = true) }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) })
            withContext(Dispatchers.Main) { transport.resetConnections() }
            assertNull(withTimeout(4_000) { old.await() })
            server.enqueue(MockResponse().setBody("""{"pong":true}"""))
            assertNotNull(transport.ping(endpoint, warmUp = true))
            assertEquals(0, server.takeRequest(2, TimeUnit.SECONDS)!!.sequenceNumber)
            transport.resetConnections()
        } finally { server.shutdown() }
    }
}
