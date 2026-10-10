package net.libreguard.vpn.viewmodel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import net.libreguard.vpn.network.RemoteVpnServer
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerRefreshCoordinatorTest {
    @Test fun startupScreenManualAndQuickConnectShareOneJob() = runTest {
        val fetched = CompletableDeferred<List<RemoteVpnServer>>()
        var fetches = 0
        var probes = 0
        val coordinator = coordinator(
            this,
            fetch = { fetches++; fetched.await() },
            probe = { servers, result -> probes++; servers.forEach { result(it, 25) } }
        )
        val startup = coordinator.refresh(trigger = "startup")
        assertSame(startup, coordinator.refresh(trigger = "screen"))
        assertSame(startup, coordinator.refresh(trigger = "manual"))
        assertSame(startup, coordinator.refresh(fetchMetadata = false, trigger = "quickConnect"))
        runCurrent()
        assertTrue(coordinator.isLoading.value)
        assertTrue(coordinator.isRefreshing.value)
        fetched.complete(listOf(server(1)))
        startup.await()
        assertEquals(1, fetches)
        assertEquals(1, probes)
        assertFalse(coordinator.isRefreshing.value)
        assertFalse(coordinator.isLoading.value)
        assertEquals(mapOf(1 to 25), coordinator.freshLatencies())
    }

    @Test fun keepsOldReadingsAndPublishesServersAsTheyFinish() = runTest {
        var pass = 0
        val second = CompletableDeferred<Unit>()
        val coordinator = coordinator(this, probe = { servers, result ->
            pass++
            result(servers[0], if (pass == 1) 30 else 20)
            if (pass == 2) second.await()
            result(servers[1], if (pass == 1) 60 else null)
        })
        coordinator.refresh().await()
        val refresh = coordinator.refresh()
        runCurrent()
        assertEquals(mapOf(1 to 20, 2 to 60), coordinator.latencies.value)
        assertEquals(ServerLatencyStatus.REFRESHING, coordinator.statuses.value[2])
        assertTrue(coordinator.isRefreshing.value)
        assertTrue(coordinator.isMeasuring.value)
        assertFalse(coordinator.isLoading.value)
        second.complete(Unit)
        refresh.await()
        assertEquals(ServerLatencyStatus.STALE, coordinator.statuses.value[2])
        assertEquals(mapOf(1 to 20), coordinator.freshLatencies())
        assertFalse(coordinator.isMeasuring.value)
    }

    @Test fun nativeStartupGateCompletesBeforeAnyProbe() = runTest {
        val nativeStatus = CompletableDeferred<Boolean>()
        var probes = 0
        val coordinator = coordinator(this, gate = { nativeStatus.await() },
            probe = { _, _ -> probes++ })
        val refresh = coordinator.refresh()
        runCurrent()
        assertEquals(0, probes)
        assertTrue(coordinator.isRefreshing.value)
        nativeStatus.complete(false)
        refresh.await()
        assertEquals(0, probes)
        assertFalse(coordinator.isRefreshing.value)
    }

    @Test fun vpnTransitionsAllowMetadataButPreventPings() = runTest {
        var blocked = true
        var fetches = 0
        var probes = 0
        val coordinator = coordinator(this, blocked = { blocked },
            fetch = { fetches++; listOf(server(1)) },
            probe = { _, _ -> probes++ })
        coordinator.refresh().await()
        assertEquals(1, fetches)
        assertEquals(0, probes)
        blocked = false
        coordinator.refresh().await()
        assertEquals(1, probes)
    }

    @Test fun nextRefreshRecoversWhenVpnLostCallbackWasMissed() = runTest {
        val tracker = VpnNetworkTracker<Int>()
        tracker.capabilitiesChanged(99, true)
        var probes = 0
        val coordinator = coordinator(this, blocked = { tracker.isVpnPresent },
            probe = { servers, result -> probes++; servers.forEach { result(it, 25) } })
        coordinator.refresh().await()
        assertEquals(0, probes)
        tracker.reconcile(mapOf(106 to false))
        coordinator.refresh(fetchMetadata = false, trigger = "manual").await()
        assertEquals(1, probes)
        assertEquals(mapOf(1 to 25, 2 to 25), coordinator.freshLatencies())
    }

    @Test fun lateResultsAfterConnectionAreDiscarded() = runTest {
        var pass = 0
        var blocked = false
        val late = CompletableDeferred<Unit>()
        val coordinator = coordinator(this, blocked = { blocked }, probe = { servers, result ->
            pass++
            if (pass == 1) servers.forEach { result(it, 30) }
            else withContext(NonCancellable) {
                late.await()
                servers.forEach { result(it, 900) }
            }
        })
        coordinator.refresh().await()
        val refresh = coordinator.refresh()
        runCurrent()
        blocked = true
        coordinator.cancelLatency("connectionStarted")
        late.complete(Unit)
        refresh.await()
        assertEquals(mapOf(1 to 30, 2 to 30), coordinator.latencies.value)
        assertEquals(ServerLatencyStatus.STALE, coordinator.statuses.value[1])
    }

    @Test fun changedEndpointsAndRemovedServersLoseOldReadings() = runTest {
        val coordinator = coordinator(this)
        coordinator.refresh().await()
        coordinator.updateServers(listOf(server(1).copy(serverHostname = "changed.example"), server(3)))
        assertTrue(coordinator.latencies.value.isEmpty())
        assertEquals(setOf(1, 3), coordinator.statuses.value.keys)
        assertTrue(coordinator.statuses.value.values.all { it == ServerLatencyStatus.UNAVAILABLE })
    }

    @Test fun networkChangeRetainsStaleReadingsAndAccountChangeClearsThem() = runTest {
        val coordinator = coordinator(this)
        coordinator.refresh().await()
        coordinator.invalidate(reason = "networkChanged")
        assertEquals(2, coordinator.latencies.value.size)
        assertTrue(coordinator.freshLatencies().isEmpty())
        assertTrue(coordinator.statuses.value.values.all { it == ServerLatencyStatus.STALE })
        coordinator.invalidate(clearLatencies = true, reason = "accountChanged")
        assertTrue(coordinator.latencies.value.isEmpty())
        assertTrue(coordinator.statuses.value.isEmpty())
    }

    @Test fun lateMetadataCannotCrossAccountOrNetworkGeneration() = runTest {
        val late = CompletableDeferred<Unit>()
        val published = mutableListOf<List<RemoteVpnServer>>()
        val coordinator = coordinator(this, onServers = { published += it }, fetch = {
            withContext(NonCancellable) { late.await(); listOf(server(99)) }
        })
        val refresh = coordinator.refresh()
        runCurrent()
        coordinator.invalidate(clearLatencies = true, reason = "accountChanged")
        late.complete(Unit)
        refresh.join()
        assertTrue(published.isEmpty())
        assertFalse(coordinator.isRefreshing.value)
    }

    @Test fun cancelledStartupGateDoesNotStartLaterRequests() = runTest {
        var probes = 0
        val coordinator = coordinator(this, gate = { awaitCancellation() },
            probe = { _, _ -> probes++ })
        val refresh = coordinator.refresh()
        runCurrent()
        coordinator.cancelLatency("vpnConsent")
        refresh.await()
        assertEquals(0, probes)
        assertFalse(coordinator.isRefreshing.value)
    }

    @Test fun fetchFailureKeepsCacheAndCompletesIndicator() = runTest {
        var failures = 0
        val coordinator = coordinator(this, fetch = { error("offline") }, failure = { failures++ })
        coordinator.updateServers(listOf(server(1)))
        coordinator.refresh().await()
        assertEquals(1, failures)
        assertFalse(coordinator.isRefreshing.value)
        assertFalse(coordinator.isLoading.value)
        assertEquals(ServerLatencyStatus.UNAVAILABLE, coordinator.statuses.value[1])
    }

    @Test fun cachedDisplayDoesNotRewriteCacheAndFetchedMetadataPersistsOnce() = runTest {
        var writes = 0
        val coordinator = coordinator(this, fetched = { writes++ })
        coordinator.updateServers(listOf(server(1)))
        assertEquals(0, writes)
        coordinator.refresh().await()
        assertEquals(1, writes)
    }

    private fun coordinator(
        scope: CoroutineScope,
        fetch: suspend () -> List<RemoteVpnServer> = { listOf(server(1), server(2)) },
        probe: suspend (List<RemoteVpnServer>, suspend (RemoteVpnServer, Int?) -> Unit) -> Unit =
            { servers, result -> servers.forEach { result(it, 25) } },
        gate: suspend () -> Boolean = { true },
        blocked: () -> Boolean = { false },
        onServers: (List<RemoteVpnServer>) -> Unit = {},
        failure: (Exception) -> Unit = { throw it },
        fetched: (List<RemoteVpnServer>) -> Unit = {}
    ) = ServerRefreshCoordinator(scope, fetch, probe, gate, blocked, onServers, failure, onFetched = fetched)

    private fun server(id: Int) = RemoteVpnServer(
        id, "Server $id", "127.0.0.1", "$id.example", "Country", "City", 1000, "Free"
    )
}

