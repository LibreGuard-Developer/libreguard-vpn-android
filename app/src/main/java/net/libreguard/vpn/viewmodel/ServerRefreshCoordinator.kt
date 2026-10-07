package net.libreguard.vpn.viewmodel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import net.libreguard.vpn.network.RemoteVpnServer
import java.util.Locale

enum class ServerLatencyStatus { REFRESHING, FRESH, STALE, UNAVAILABLE }

/** All state mutations are confined to the supplied (UI) scope. */
internal class ServerRefreshCoordinator(
    private val scope: CoroutineScope,
    private val fetchServers: suspend () -> List<RemoteVpnServer>,
    private val probeServers: suspend (List<RemoteVpnServer>, suspend (RemoteVpnServer, Int?) -> Unit) -> Unit,
    private val canProbe: suspend () -> Boolean,
    private val isBlocked: () -> Boolean,
    private val onServers: (List<RemoteVpnServer>) -> Unit,
    private val onFailure: (Exception) -> Unit,
    private val log: (String) -> Unit = {},
    private val onFetched: (List<RemoteVpnServer>) -> Unit = {}
) {
    private val _latencies = MutableStateFlow<Map<Int, Int>>(emptyMap())
    val latencies: StateFlow<Map<Int, Int>> = _latencies
    private val _statuses = MutableStateFlow<Map<Int, ServerLatencyStatus>>(emptyMap())
    val statuses: StateFlow<Map<Int, ServerLatencyStatus>> = _statuses
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading
    private val _isMeasuring = MutableStateFlow(false)
    val isMeasuring: StateFlow<Boolean> = _isMeasuring
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    private var servers: List<RemoteVpnServer> = emptyList()
    private var generation = 0L
    private var probeGeneration = 0L
    private var refreshJob: Deferred<Unit>? = null
    private var latencyJob: Deferred<Unit>? = null

    fun freshLatencies(): Map<Int, Int> =
        _latencies.value.filterKeys { _statuses.value[it] == ServerLatencyStatus.FRESH }

    fun updateServers(updated: List<RemoteVpnServer>) {
        val previous = servers.associateBy { it.id }
        val retained = updated.filter { endpoint(it) == previous[it.id]?.let(::endpoint) }.map { it.id }.toSet()
        _latencies.value = _latencies.value.filterKeys { it in retained }
        _statuses.value = updated.associate { it.id to (_statuses.value[it.id]?.takeIf { _ -> it.id in retained } ?: ServerLatencyStatus.UNAVAILABLE) }
        servers = updated
        onServers(updated)
    }

    fun refresh(fetchMetadata: Boolean = true, trigger: String = "automatic"): Deferred<Unit> {
        refreshJob?.takeIf { it.isActive }?.let {
            log("Refresh joined trigger=$trigger")
            return it
        }
        val refreshGeneration = ++generation
        _isRefreshing.value = true
        _isLoading.value = fetchMetadata
        val job = scope.async(start = CoroutineStart.LAZY) {
            try {
                if (fetchMetadata) {
                    val fetched = fetchServers()
                    currentCoroutineContext().ensureActive()
                    if (generation != refreshGeneration) return@async
                    updateServers(fetched)
                    onFetched(fetched)
                }
                if (generation != refreshGeneration) return@async
                _isLoading.value = false
                if (isBlocked()) return@async
                val measurementGeneration = ++probeGeneration
                val targets = servers.toList()
                val measurement = async(start = CoroutineStart.LAZY) {
                    if (!canProbe() || !isCurrent(refreshGeneration, measurementGeneration)) return@async
                    _isMeasuring.value = true
                    _statuses.value = targets.associate { it.id to ServerLatencyStatus.REFRESHING }
                    probeServers(targets) { server, latency ->
                        withContext(scope.coroutineContext.minusKey(Job)) {
                            currentCoroutineContext().ensureActive()
                            if (isCurrent(refreshGeneration, measurementGeneration) &&
                                servers.any { it.id == server.id && endpoint(it) == endpoint(server) }) {
                                if (latency != null) _latencies.value = _latencies.value + (server.id to latency)
                                _statuses.value = _statuses.value + (server.id to when {
                                    latency != null -> ServerLatencyStatus.FRESH
                                    server.id in _latencies.value -> ServerLatencyStatus.STALE
                                    else -> ServerLatencyStatus.UNAVAILABLE
                                })
                            }
                        }
                    }
                }
                latencyJob = measurement
                measurement.start()
                try {
                    measurement.await()
                } catch (e: CancellationException) {
                    // A VPN transition cancels probes without cancelling the metadata refresh.
                    currentCoroutineContext().ensureActive()
                } finally {
                    if (generation == refreshGeneration) {
                        latencyJob = null
                        _isMeasuring.value = false
                        settlePending()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == refreshGeneration) onFailure(e)
            } finally {
                if (generation == refreshGeneration) {
                    _isLoading.value = false
                    _isRefreshing.value = false
                    log("Refresh finished trigger=$trigger")
                }
            }
        }
        // Publish before dispatch: login and screen-entry requests can arrive together.
        refreshJob = job
        job.start()
        return job
    }

    fun cancelLatency(reason: String) {
        probeGeneration++
        latencyJob?.cancel()
        latencyJob = null
        _isMeasuring.value = false
        settlePending()
        log("Latency cancelled reason=$reason")
    }

    fun invalidate(clearLatencies: Boolean = false, reason: String) {
        generation++
        cancelLatency(reason)
        refreshJob?.cancel()
        refreshJob = null
        _isLoading.value = false
        _isRefreshing.value = false
        if (clearLatencies) {
            servers = emptyList()
            _latencies.value = emptyMap()
            _statuses.value = emptyMap()
        } else {
            _statuses.value = servers.associate { it.id to if (it.id in _latencies.value) ServerLatencyStatus.STALE else ServerLatencyStatus.UNAVAILABLE }
        }
    }

    private fun settlePending() {
        _statuses.value = _statuses.value.mapValues { (id, status) ->
            if (status != ServerLatencyStatus.REFRESHING) status
            else if (id in _latencies.value) ServerLatencyStatus.STALE
            else ServerLatencyStatus.UNAVAILABLE
        }
    }

    private fun isCurrent(refresh: Long, measurement: Long): Boolean =
        refresh == generation && measurement == probeGeneration && !isBlocked()

    private fun endpoint(server: RemoteVpnServer): Pair<String?, Int> =
        server.serverHostname?.trim()?.lowercase(Locale.ROOT) to server.latencyPingPort
}

