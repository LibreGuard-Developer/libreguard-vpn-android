package net.libreguard.vpn.network

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Service for measuring latency to VPN servers via /ping endpoint on port 5001.
 * 
 * This service connects directly to VPN servers (not through ManagementPanel)
 * to measure actual network latency from the user's device.
 * 
 * The /ping endpoint is:
 * - Unauthenticated (no token required)
 * - HTTPS-only using server hostname (Let's Encrypt certificates)
 * - Returns minimal JSON: {"pong":true,"timestamp":...}
 */
object PingService {
    
    private const val TAG = "PingService"
    private const val DEFAULT_PING_PORT = 5001
    private const val CONNECT_TIMEOUT_SECONDS = 5L
    private const val READ_TIMEOUT_SECONDS = 5L
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    
    /**
     * Ping a single server and return latency in milliseconds.
     * 
     * @param serverHostname The server's hostname (REQUIRED - IP addresses not supported)
     * @param port The ping port (default 5001)
     * @return Latency in milliseconds, or null if unreachable
     */
    suspend fun pingServer(serverHostname: String, port: Int = DEFAULT_PING_PORT): Int? =
        withContext(Dispatchers.IO) {
            try {
                val url = "https://$serverHostname:$port/ping"
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()
                
                val startTime = System.currentTimeMillis()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val latency = (System.currentTimeMillis() - startTime).toInt()
                        android.util.Log.d(TAG, "Ping to $serverHostname: ${latency}ms")
                        latency
                    } else {
                        android.util.Log.v(TAG, "Ping to $serverHostname failed: ${response.code}")
                        null
                    }
                }
            } catch (e: Exception) {
                android.util.Log.v(TAG, "Ping to $serverHostname error: ${e.message}")
                null
            }
        }
    
    /**
     * Ping multiple servers in parallel and return a map of serverId to latency.
     * 
     * This method pings all servers concurrently for fast results.
     * Servers that fail to respond will not be included in the result map.
     * 
     * @param servers List of servers to ping
     * @return Map of server ID to latency in milliseconds
     */
    suspend fun pingServers(servers: List<RemoteVpnServer>): Map<Int, Int> = coroutineScope {
        android.util.Log.d(TAG, "Starting parallel ping for ${servers.size} servers")
        
        servers.map { server ->
            async {
                // Skip servers without hostname
                if (server.serverHostname.isNullOrBlank()) {
                    android.util.Log.v(TAG, "Server ${server.serverName} has no hostname, skipping ping")
                    return@async null
                }

                val latency = pingServer(server.serverHostname, server.latencyPingPort)
                if (latency != null) {
                    server.id to latency
                } else {
                    null
                }
            }
        }
        .awaitAll()
        .filterNotNull()
        .toMap()
        .also { results ->
            android.util.Log.d(TAG, "Ping complete: ${results.size}/${servers.size} servers responded")
        }
    }
    
    /**
     * Ping servers in batches to avoid overwhelming the network.
     * 
     * Use this for large server lists or on slow connections.
     * 
     * @param servers List of servers to ping
     * @param batchSize Number of concurrent pings (default 10)
     * @return Map of server ID to latency in milliseconds
     */
    suspend fun pingServersInBatches(
        servers: List<RemoteVpnServer>, 
        batchSize: Int = 10
    ): Map<Int, Int> = coroutineScope {
        val results = mutableMapOf<Int, Int>()
        
        servers.chunked(batchSize).forEach { batch ->
            val batchResults = pingServers(batch)
            results.putAll(batchResults)
        }
        
        results.toMap()
    }
}
