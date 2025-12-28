package net.libreguard.vpn.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.libreguard.vpn.network.RemoteVpnServer
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import kotlin.system.measureTimeMillis

/**
 * Server Latency Helper - Measure network latency to VPN servers
 *
 * Tries multiple methods in order:
 * 1. UDP packets to VPN-specific ports (500, 4500, 1194)
 * 2. TCP socket timing to port 443
 * 3. HTTP HEAD request as final fallback
 */
object ServerLatencyHelper {
    private const val TAG = "ServerLatencyHelper"
    private const val DEFAULT_TIMEOUT_MS = 3000

    // VPN-specific UDP ports to try
    private val VPN_UDP_PORTS = listOf(
        500,    // IKEv2/IPSec (ISAKMP)
        4500,   // IPSec NAT-T
        1194    // OpenVPN default
    )

    /**
     * Measure latency to a server using multiple methods
     *
     * @param serverIp The IP address or hostname of the server
     * @param timeoutMs Connection timeout in milliseconds
     * @return Latency in milliseconds, or null if unreachable
     */
    suspend fun measureLatency(
        serverIp: String,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS
    ): Int? = withContext(Dispatchers.IO) {
        try {
            // Try UDP packets to VPN ports first (most likely to work for VPN servers)
            for (port in VPN_UDP_PORTS) {
                val udpLatency = measureUdpLatency(serverIp, port, timeoutMs)
                if (udpLatency != null) {
                    Log.d(TAG, "UDP latency to $serverIp:$port = ${udpLatency}ms")
                    return@withContext udpLatency
                }
            }

            // Try TCP socket timing to port 443
            val tcpLatency = measureTcpLatency(serverIp, 443, timeoutMs)
            if (tcpLatency != null) {
                Log.d(TAG, "TCP latency to $serverIp:443 = ${tcpLatency}ms")
                return@withContext tcpLatency
            }

            // Fallback to HTTP HEAD request
            val httpLatency = measureHttpLatency(serverIp, timeoutMs)
            if (httpLatency != null) {
                Log.d(TAG, "HTTP latency to $serverIp = ${httpLatency}ms (fallback)")
                return@withContext httpLatency
            }

            Log.w(TAG, "Failed to measure latency to $serverIp")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error measuring latency to $serverIp: ${e.message}")
            null
        }
    }

    /**
     * Measure latency using UDP packet to VPN port
     * Sends a small probe packet and measures time to get any response
     */
    private suspend fun measureUdpLatency(
        serverIp: String,
        port: Int,
        timeoutMs: Int
    ): Int? = withContext(Dispatchers.IO) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socket.soTimeout = 1000 // 1 second timeout for receive

            val address = InetAddress.getByName(serverIp)

            // Create a small probe packet (8 bytes)
            // For IKE/IPSec, this won't be a valid packet, but we're just measuring latency
            val sendData = ByteArray(8) { 0xFF.toByte() }
            val sendPacket = DatagramPacket(sendData, sendData.size, address, port)

            // Prepare receive buffer
            val receiveData = ByteArray(512)
            val receivePacket = DatagramPacket(receiveData, receiveData.size)

            Log.d(TAG, "Trying UDP probe to $serverIp:$port")

            // Measure round-trip time
            val latency = measureTimeMillis {
                socket.send(sendPacket)
                try {
                    // Wait for any response (could be valid response or ICMP error)
                    socket.receive(receivePacket)
                } catch (e: SocketTimeoutException) {
                    // No response within timeout - this is normal for UDP probe
                    // We still measure the send time which indicates reachability
                }
            }.toInt()

            // Consider latency valid if it's reasonable (not hitting timeout)
            if (latency < 1000) {
                Log.d(TAG, "UDP probe to $serverIp:$port succeeded in ${latency}ms")
                return@withContext latency
            } else {
                Log.v(TAG, "UDP probe to $serverIp:$port timeout")
                return@withContext null
            }
        } catch (e: Exception) {
            Log.v(TAG, "UDP probe failed to $serverIp:$port - ${e.message}")
            return@withContext null
        } finally {
            socket?.close()
        }
    }

    /**
     * Measure latency using TCP socket connection
     */
    private suspend fun measureTcpLatency(
        serverIp: String,
        port: Int,
        timeoutMs: Int
    ): Int? = withContext(Dispatchers.IO) {
        try {
            withTimeoutOrNull(timeoutMs.toLong()) {
                val socket = Socket()
                val latency = measureTimeMillis {
                    socket.connect(InetSocketAddress(serverIp, port), timeoutMs)
                }
                socket.close()
                latency.toInt()
            }
        } catch (e: Exception) {
            Log.v(TAG, "TCP connection failed to $serverIp:$port - ${e.message}")
            null
        }
    }

    /**
     * Measure latency using HTTP HEAD request (fallback method)
     */
    private suspend fun measureHttpLatency(
        serverIp: String,
        timeoutMs: Int
    ): Int? = withContext(Dispatchers.IO) {
        try {
            // Try HTTPS first, then HTTP
            val urls = listOf(
                "https://$serverIp/",
                "http://$serverIp/"
            )

            for (urlString in urls) {
                try {
                    val url = URL(urlString)
                    val latency = withTimeoutOrNull(timeoutMs.toLong()) {
                        measureTimeMillis {
                            val connection = url.openConnection() as HttpURLConnection
                            connection.requestMethod = "HEAD"
                            connection.connectTimeout = timeoutMs
                            connection.readTimeout = timeoutMs
                            connection.instanceFollowRedirects = false
                            connection.connect()
                            connection.responseCode // Just get response code to establish connection
                            connection.disconnect()
                        }
                    }

                    if (latency != null && latency > 0) {
                        return@withContext latency.toInt()
                    }
                } catch (e: Exception) {
                    Log.v(TAG, "HTTP request failed to $urlString - ${e.message}")
                    continue
                }
            }
            null
        } catch (e: Exception) {
            Log.v(TAG, "HTTP latency measurement failed to $serverIp - ${e.message}")
            null
        }
    }

    /**
     * Measure latency for multiple servers in parallel
     *
     * @param servers List of VPN servers to measure
     * @return Map of server ID to latency in milliseconds
     */
    suspend fun measureLatencyForServers(
        servers: List<RemoteVpnServer>
    ): Map<Int, Int> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting latency measurement for ${servers.size} servers")

            // Measure all servers in parallel
            val results = servers.map { server ->
                async {
                    val latency = measureLatency(server.serverIp)
                    if (latency != null) {
                        server.id to latency
                    } else {
                        null
                    }
                }
            }.awaitAll()

            // Filter out null results and convert to map
            val latencyMap = results.filterNotNull().toMap()

            Log.d(TAG, "Latency measurement complete: ${latencyMap.size}/${servers.size} servers reachable")
            latencyMap
        } catch (e: Exception) {
            Log.e(TAG, "Error measuring latency for servers: ${e.message}")
            emptyMap()
        }
    }
}

