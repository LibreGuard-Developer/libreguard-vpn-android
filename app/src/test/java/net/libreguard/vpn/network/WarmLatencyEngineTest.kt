package net.libreguard.vpn.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WarmLatencyEngineTest {
    @Test fun discardsWarmUpAndUsesMedian() = runTest {
        val readings = mutableListOf<Int?>(900, 40, 190, 42)
        val phases = mutableListOf<Boolean>()
        val engine = WarmLatencyEngine(PingTransport { _, warmUp ->
            phases += warmUp
            readings.removeAt(0)
        })
        assertEquals(42, engine.measureEndpoint(ProbeEndpoint("vpn.example", 5001)))
        assertEquals(listOf(true, false, false, false), phases)
    }

    @Test fun averagesTwoSuccessesAndRejectsOne() = runTest {
        suspend fun measure(values: List<Int?>): Int? {
            val readings = values.toMutableList()
            return WarmLatencyEngine(PingTransport { _, _ -> readings.removeAt(0) })
                .measureEndpoint(ProbeEndpoint("vpn.example", 5001))
        }
        assertEquals(41, measure(listOf(900, 40, null, 41)))
        assertNull(measure(listOf(900, null, 41, null)))
    }

    @Test fun failedWarmUpDoesNotStartMeasuredRequests() = runTest {
        var calls = 0
        val engine = WarmLatencyEngine(PingTransport { _, _ -> calls++; null })
        assertNull(engine.measureEndpoint(ProbeEndpoint("vpn.example", 5001)))
        assertEquals(1, calls)
        assertNull(engine.measureEndpoint(ProbeEndpoint("vpn.example", 0)))
        assertEquals(1, calls)
    }

    @Test fun ordinaryProbeFailureDoesNotAbortOtherServers() = runTest {
        val engine = WarmLatencyEngine(PingTransport { endpoint, _ ->
            if (endpoint.host == "1.example") throw IllegalStateException("transport unavailable")
            20
        })
        assertEquals(mapOf(2 to 20), engine.measure(listOf(server(1), server(2))))
    }

    @Test fun eightSlotsRefillBeforeSlowServerFinishes() = runTest {
        val gates = (1..9).associate { "$it.example" to CompletableDeferred<Unit>() }
        val started = mutableListOf<String>()
        val completed = mutableListOf<Int>()
        var active = 0
        var maximumActive = 0
        val engine = WarmLatencyEngine(PingTransport { endpoint, warmUp ->
            if (warmUp) {
                started += endpoint.host
                active++
                maximumActive = maxOf(active, maximumActive)
                gates.getValue(endpoint.host).await()
            }
            20
        })
        val result = async {
            engine.measure((1..9).map(::server)) { item, _ -> active--; completed += item.id }
        }
        runCurrent()
        assertEquals(8, started.size)
        gates.getValue("2.example").complete(Unit)
        runCurrent()
        assertTrue("9.example" in started)
        assertTrue(2 in completed)
        assertFalse(1 in completed)
        gates.values.forEach { it.complete(Unit) }
        assertEquals(9, result.await().size)
        assertEquals(8, maximumActive)
    }

    @Test fun cancellationStopsActiveAndQueuedJobs() = runTest {
        var cancelled = 0
        var started = 0
        val engine = WarmLatencyEngine(PingTransport { _, _ ->
            started++
            try { awaitCancellation() } finally { cancelled++ }
        })
        val job = launch { engine.measure((1..12).map(::server)) }
        runCurrent()
        job.cancelAndJoin()
        assertEquals(8, started)
        assertEquals(8, cancelled)
    }

    @Test fun requestBudgetIncludesDispatchQueueAndCancelsStuckTransport() = runTest {
        var cancelled = false
        val engine = WarmLatencyEngine(PingTransport { _, _ ->
            try { awaitCancellation() } finally { cancelled = true }
        })
        val result = async { engine.measureEndpoint(ProbeEndpoint("vpn.example", 5001)) }
        advanceTimeBy(3_000)
        runCurrent()
        assertNull(result.await())
        assertTrue(cancelled)
    }

    @Test fun vpnGuardIsCheckedBeforeEveryRequest() = runTest {
        var allowed = true
        var calls = 0
        val engine = WarmLatencyEngine(PingTransport { _, _ -> calls++; allowed = false; 20 })
        val job = launch { engine.measure(listOf(server(1)), canProbe = { allowed }) }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, calls)
    }

    @Test fun setupAndParsingAreOutsideRoundTripTimer() {
        var clock = 0L
        val timing = ProbeTiming { clock }
        val call = OkHttpClient().newCall(Request.Builder().url("https://vpn.example/ping").build())
        timing.callStart(call)
        clock = 2_000_000_000
        timing.secureConnectStart(call)
        clock = 4_000_000_000
        timing.secureConnectEnd(call, null)
        clock = 5_000_000_000
        timing.requestHeadersStart(call)
        clock = 5_041_000_000
        timing.responseBodyEnd(call, 12)
        clock = 8_000_000_000
        assertEquals(41, timing.latencyMs())
    }

    private fun server(id: Int) = RemoteVpnServer(
        id, "Server $id", "127.0.0.1", "$id.example", "Country", "City", 1000, "Free"
    )
}

