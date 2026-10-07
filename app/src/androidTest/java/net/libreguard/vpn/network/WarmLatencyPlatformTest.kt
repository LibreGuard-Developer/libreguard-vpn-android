package net.libreguard.vpn.network

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Live diagnostic: run while disconnected on Wi-Fi, then repeat on cellular. */
class WarmLatencyPlatformTest {
    @Test fun compareColdClientWithRepeatedWarmRefreshes() = runBlocking {
        assumeTrue("Live diagnostics are opt-in", InstrumentationRegistry.getArguments().getString("runLiveLatency") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        fun vpnActive() = connectivity.allNetworks.any {
            connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
        assumeFalse("Live latency diagnostics require a disconnected VPN", vpnActive())
        val network = connectivity.activeNetwork
        assumeTrue("A network is required", network != null)
        val capabilities = connectivity.getNetworkCapabilities(network)
        val networkType = when {
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
            else -> "other"
        }
        val expectedNetwork = InstrumentationRegistry.getArguments().getString("expectedNetwork")
        assumeTrue("Expected $expectedNetwork, found $networkType", expectedNetwork == null || expectedNetwork == networkType)
        val hosts = InstrumentationRegistry.getArguments().getString("latencyHosts")
            ?.split(",") ?: listOf(
                "us-multi-1.libreguard.net", "de-multi-1.libreguard.net", "ch-multi-1.libreguard.net"
            )
        val servers = hosts.mapIndexed { index, host ->
            RemoteVpnServer(index, host, "", host.trim(), "", "", 1000, "Free")
        }
        val logger: (String) -> Unit = { Log.i("WarmLatencyPlatform", it) }
        val engine = WarmLatencyEngine(OkHttpPingTransport(log = logger), log = logger)
        val passes = List(5) { pass ->
            engine.measure(servers, canProbe = { connectivity.activeNetwork == network && !vpnActive() })
                .also { Log.i("WarmLatencyPlatform", "network=$networkType pass=$pass latencyMs=$it") }
        }
        assumeTrue("No live /ping endpoints responded", passes.first().isNotEmpty())
        val stableIds = passes.map { it.keys }.reduce { left, right -> left intersect right }
        assertTrue("At least one server must respond throughout comparison", stableIds.isNotEmpty())
        for (id in stableIds) {
            val cold = passes[0].getValue(id)
            val repeated = passes.drop(1).map { it.getValue(id) }.sorted()
            val typical = (repeated[1] + repeated[2]) / 2.0
            Log.i("WarmLatencyPlatform", "network=$networkType host=${hosts[id]} firstMs=$cold subsequentMedianMs=$typical differenceMs=${cold - typical}")
            assertTrue(cold > 0 && typical > 0)
        }
    }
}

