package net.libreguard.vpn.network

import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/** HTTPS /ping round trips, after warming each server's connection. */
object PingService {
    private val transport = OkHttpPingTransport(log = { android.util.Log.d("PingService", it) })
    private val engine = WarmLatencyEngine(
        transport = transport,
        log = { android.util.Log.d("PingService", it) }
    )

    internal suspend fun resetConnections() = transport.resetConnections()

    suspend fun pingServer(serverHostname: String, port: Int = 5001): Int? =
        engine.measureEndpoint(ProbeEndpoint(serverHostname, port))

    suspend fun pingServers(
        servers: List<RemoteVpnServer>,
        canProbe: () -> Boolean = { true },
        onResult: suspend (RemoteVpnServer, Int?) -> Unit = { _, _ -> }
    ): Map<Int, Int> = engine.measure(servers, canProbe = canProbe, onResult = onResult)

    // Compatibility for callers using the old batch API; slots now refill immediately.
    suspend fun pingServersInBatches(
        servers: List<RemoteVpnServer>,
        batchSize: Int = 10
    ): Map<Int, Int> = engine.measure(servers, concurrency = batchSize.coerceIn(1, 8))
}

internal data class ProbeEndpoint(val host: String, val port: Int)

internal fun interface PingTransport {
    suspend fun ping(endpoint: ProbeEndpoint, warmUp: Boolean): Int?
}

internal class WarmLatencyEngine(
    private val transport: PingTransport,
    private val log: (String) -> Unit = {},
    private val nanoTime: () -> Long = System::nanoTime
) {
    suspend fun measure(
        servers: List<RemoteVpnServer>,
        concurrency: Int = 8,
        canProbe: () -> Boolean = { true },
        onResult: suspend (RemoteVpnServer, Int?) -> Unit = { _, _ -> }
    ): Map<Int, Int> = coroutineScope {
        val started = nanoTime()
        val workers = minOf(servers.size, concurrency.coerceIn(1, 8))
        if (workers == 0) return@coroutineScope emptyMap()
        val slots = Semaphore(workers)
        val results = servers.map { server ->
            async {
                slots.withPermit {
                    val latency = server.serverHostname?.takeIf { it.isNotBlank() }?.let {
                        measureEndpoint(ProbeEndpoint(it.trim(), server.latencyPingPort), canProbe)
                    }
                    currentCoroutineContext().ensureActive()
                    onResult(server, latency)
                    latency?.let { server.id to it }
                }
            }
        }.awaitAll().filterNotNull().toMap()
        log("Refresh finished servers=${servers.size} successes=${results.size} workers=$workers elapsedMs=${(nanoTime() - started) / 1_000_000}")
        results
    }

    suspend fun measureEndpoint(
        endpoint: ProbeEndpoint,
        canProbe: () -> Boolean = { true }
    ): Int? = withTimeoutOrNull(12_000L) {
        if (endpoint.host.isBlank() || endpoint.port !in 1..65535) return@withTimeoutOrNull null
        suspend fun request(warmUp: Boolean): Int? {
            currentCoroutineContext().ensureActive()
            if (!canProbe()) throw CancellationException("VPN latency probing blocked")
            return try {
                withTimeoutOrNull(3_000L) { transport.ping(endpoint, warmUp) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Probe failed host=${endpoint.host} reason=${e.javaClass.simpleName}")
                null
            }
        }
        if (request(warmUp = true) == null) return@withTimeoutOrNull null
        val samples = List(3) { request(warmUp = false) }.filterNotNull().sorted()
        val median = when (samples.size) {
            3 -> samples[1]
            2 -> ((samples[0].toLong() + samples[1]) / 2.0).roundToInt()
            else -> null
        }
        log("Server measured host=${endpoint.host} samples=$samples medianMs=$median")
        median
    }
}

/** Reads the body on OkHttp's worker, and ties coroutine cancellation to Call.cancel(). */
internal class OkHttpPingTransport(
    private val nanoTime: () -> Long = System::nanoTime,
    private val log: (String) -> Unit = {},
    client: OkHttpClient? = null,
    private val urlForEndpoint: (ProbeEndpoint) -> HttpUrl = {
        HttpUrl.Builder().scheme("https").host(it.host).port(it.port).addPathSegment("ping").build()
    }
) : PingTransport {
    private val clientTemplate = (client?.newBuilder() ?: OkHttpClient.Builder())
        .dispatcher(Dispatcher().apply { maxRequests = 8; maxRequestsPerHost = 8 })
        .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.SECONDS)
        .cache(null)
        .addInterceptor { chain ->
            chain.request().tag(ProbeTiming::class.java)?.processingStarted()
            chain.proceed(chain.request())
        }
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .eventListenerFactory { call -> call.request().tag(ProbeTiming::class.java)!! }
        .build()

    private val connectionLock = Mutex()
    @Volatile private var activeClient = clientTemplate

    suspend fun resetConnections() = withContext(Dispatchers.IO) {
        connectionLock.withLock {
            val old = activeClient
            // Separate pools and dispatchers keep obsolete requests out of the new network's pool.
            activeClient = clientTemplate.newBuilder()
                .dispatcher(Dispatcher().apply { maxRequests = 8; maxRequestsPerHost = 8 })
                .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES)).build()
            try {
                old.dispatcher.cancelAll()
                old.connectionPool.evictAll()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Connection cleanup failed reason=${e.javaClass.simpleName}")
                net.libreguard.vpn.util.CrashlyticsReporter.recordHandledException(e, "Latency connection cleanup failed")
            }
        }
    }

    override suspend fun ping(endpoint: ProbeEndpoint, warmUp: Boolean): Int? {
        val timing = ProbeTiming(nanoTime)
        val request = try {
            Request.Builder().url(urlForEndpoint(endpoint))
                .header("Cache-Control", "no-cache, no-store")
                .tag(ProbeTiming::class.java, timing).get().build()
        } catch (_: IllegalArgumentException) {
            return null
        }
        connectionLock.lock()
        return suspendCancellableCoroutine { continuation ->
            try {
                // Enqueue before releasing the reset lock so cancelAll includes this request.
                val client = activeClient
                val call = client.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        log("Probe failed host=${endpoint.host} warmUp=$warmUp cancelled=${call.isCanceled()} reason=${e.javaClass.simpleName}")
                        continuation.resume(null)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val latency = try {
                            response.use {
                                val body = it.body?.string()
                                if (client === activeClient && it.isSuccessful && validPong(body)) timing.latencyMs() else null
                            }
                        } catch (_: Exception) {
                            null
                        }
                        log("Probe host=${endpoint.host} warmUp=$warmUp ${timing.diagnostics()} valid=${latency != null}")
                        continuation.resume(latency)
                    }
                })
            } finally {
                // Do not hold the lock while waiting for the network response.
                connectionLock.unlock()
            }
        }
    }

    private fun validPong(body: String?): Boolean = try {
        val pong = JsonParser.parseString(body ?: "").asJsonObject.get("pong")
        pong?.isJsonPrimitive == true && pong.asJsonPrimitive.isBoolean && pong.asBoolean
    } catch (_: Exception) {
        false
    }
}

internal class ProbeTiming(private val nanoTime: () -> Long) : EventListener() {
    private var callAt: Long? = null
    private var processingAt: Long? = null
    private var dnsAt: Long? = null
    private var connectAt: Long? = null
    private var tlsAt: Long? = null
    private var sentAt: Long? = null
    private var receivedAt: Long? = null
    private var dnsNs = 0L
    private var connectNs = 0L
    private var tlsNs = 0L

    fun processingStarted() { processingAt = nanoTime() }
    override fun callStart(call: Call) { callAt = nanoTime() }
    override fun dnsStart(call: Call, domainName: String) { dnsAt = nanoTime() }
    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<java.net.InetAddress>) {
        dnsNs = dnsAt?.let { nanoTime() - it } ?: 0
    }
    override fun connectStart(call: Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) {
        connectAt = nanoTime()
    }
    override fun secureConnectStart(call: Call) { tlsAt = nanoTime() }
    override fun secureConnectEnd(call: Call, handshake: okhttp3.Handshake?) {
        tlsNs = tlsAt?.let { nanoTime() - it } ?: 0
    }
    override fun connectEnd(call: Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy, protocol: okhttp3.Protocol?) {
        connectNs = connectAt?.let { nanoTime() - it } ?: 0
    }
    override fun requestHeadersStart(call: Call) { sentAt = nanoTime() }
    override fun responseBodyEnd(call: Call, byteCount: Long) { receivedAt = nanoTime() }

    fun latencyMs(): Int? = sentAt?.let { start ->
        receivedAt?.let { ((it - start) / 1_000_000.0).roundToInt().coerceAtLeast(1) }
    }

    fun diagnostics(): String = "queueMs=${processingAt?.let { processing -> callAt?.let { (processing - it) / 1_000_000 } }} setupMs=${sentAt?.let { sent -> processingAt?.let { (sent - it) / 1_000_000 } }} totalMs=${callAt?.let { (nanoTime() - it) / 1_000_000 }} queueAndSetupMs=${sentAt?.let { sent -> callAt?.let { (sent - it) / 1_000_000 } }} dnsMs=${dnsNs / 1_000_000} connectMs=${connectNs / 1_000_000} tlsMs=${tlsNs / 1_000_000} roundTripMs=${latencyMs()}"
}
