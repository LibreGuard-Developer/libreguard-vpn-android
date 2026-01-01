package net.libreguard.vpn.util

import android.util.Log
import net.libreguard.vpn.network.RemoteVpnServer

/**
 * ServerSelectionHelper - Smart server selection algorithm for Quick Connect
 *
 * Automatically selects the best VPN server based on:
 * 1. Latency (primary factor - 70% weight)
 * 2. Server Load (25% weight normally, 50% when load > 70%)
 * 3. Pro Server Bonus (10% bonus for Pro users on Premium servers)
 *
 * Filters servers by user subscription tier (Free/Pro).
 *
 * Created: January 1, 2026
 */
object ServerSelectionHelper {

    private const val TAG = "ServerSelectionHelper"

    // Scoring weights
    private const val LATENCY_WEIGHT_NORMAL = 0.70
    private const val LOAD_WEIGHT_NORMAL = 0.25
    private const val PRO_BONUS_WEIGHT = 0.10

    // When server load exceeds this threshold, increase load importance
    private const val HIGH_LOAD_THRESHOLD = 70
    private const val LATENCY_WEIGHT_HIGH_LOAD = 0.50
    private const val LOAD_WEIGHT_HIGH_LOAD = 0.50

    // Server load considered "overloaded" (avoid if possible)
    private const val OVERLOAD_THRESHOLD = 90

    // Maximum acceptable latency (ms) - servers above this get heavy penalty
    private const val MAX_ACCEPTABLE_LATENCY = 500

    /**
     * Select the best server for Quick Connect based on user tier and metrics
     *
     * @param servers List of all available servers
     * @param serverLatencies Map of server ID to latency in milliseconds
     * @param isPro Whether the user has Pro subscription
     * @return Best server, or null if no suitable server found
     */
    fun selectBestServer(
        servers: List<RemoteVpnServer>,
        serverLatencies: Map<Int, Int>,
        isPro: Boolean
    ): RemoteVpnServer? {
        if (servers.isEmpty()) {
            Log.w(TAG, "No servers available for selection")
            return null
        }

        // Filter servers by user subscription tier
        val eligibleServers = filterServersByTier(servers, isPro)

        if (eligibleServers.isEmpty()) {
            Log.w(TAG, "No eligible servers for user tier (isPro=$isPro)")
            return null
        }

        Log.d(TAG, "Selecting from ${eligibleServers.size} eligible servers (isPro=$isPro)")

        // Score each eligible server
        val scoredServers = eligibleServers.mapNotNull { server ->
            val score = calculateServerScore(server, serverLatencies[server.id], isPro)
            if (score != null) {
                ScoredServer(server, score)
            } else {
                null
            }
        }

        if (scoredServers.isEmpty()) {
            Log.w(TAG, "No servers could be scored (missing latency data?)")
            return null
        }

        // Sort by score (higher is better) and select the best
        val bestServer = scoredServers.maxByOrNull { it.score }?.server

        if (bestServer != null) {
            val bestScore = scoredServers.find { it.server == bestServer }?.score
            Log.d(TAG, "Best server selected: ${bestServer.serverName} (${bestServer.country}) " +
                    "- Score: ${"%.2f".format(bestScore)} " +
                    "- Latency: ${serverLatencies[bestServer.id]}ms " +
                    "- Load: ${bestServer.load ?: "N/A"}% " +
                    "- Tier: ${bestServer.pricingTier}")
        }

        return bestServer
    }

    /**
     * Filter servers based on user subscription tier
     *
     * - Free users: Only "Free" tier servers
     * - Pro users: All servers (Free + Premium), with preference for Premium
     */
    private fun filterServersByTier(
        servers: List<RemoteVpnServer>,
        isPro: Boolean
    ): List<RemoteVpnServer> {
        return if (isPro) {
            // Pro users can access all servers
            servers
        } else {
            // Free users can only access Free tier servers
            servers.filter { !it.pricingTier.equals("Premium", ignoreCase = true) }
        }
    }

    /**
     * Calculate a score for a server (higher is better)
     *
     * Score components:
     * 1. Latency score (0-100): Lower latency = higher score
     * 2. Load score (0-100): Lower load = higher score
     * 3. Pro bonus (0-10): Premium servers get bonus for Pro users
     *
     * Final score is weighted sum normalized to 0-100 range
     */
    private fun calculateServerScore(
        server: RemoteVpnServer,
        latency: Int?,
        isPro: Boolean
    ): Double? {
        // Cannot score without latency data
        if (latency == null) {
            Log.v(TAG, "Server ${server.serverName} has no latency data, skipping")
            return null
        }

        // Check if server is severely overloaded (>90%)
        val load = server.load ?: 0
        if (load >= OVERLOAD_THRESHOLD) {
            Log.v(TAG, "Server ${server.serverName} is overloaded ($load%), applying penalty")
        }

        // Calculate latency score (inverse - lower latency = higher score)
        // Normalize to 0-100 range, with 0ms = 100, 500ms+ = 0
        val latencyScore = calculateLatencyScore(latency)

        // Calculate load score (inverse - lower load = higher score)
        // Normalize to 0-100 range, with 0% = 100, 100% = 0
        val loadScore = calculateLoadScore(load)

        // Determine if server has high load (adjust weights)
        val isHighLoad = load >= HIGH_LOAD_THRESHOLD

        val latencyWeight = if (isHighLoad) LATENCY_WEIGHT_HIGH_LOAD else LATENCY_WEIGHT_NORMAL
        val loadWeight = if (isHighLoad) LOAD_WEIGHT_HIGH_LOAD else LOAD_WEIGHT_NORMAL

        // Calculate base score (weighted sum)
        var totalScore = (latencyScore * latencyWeight) + (loadScore * loadWeight)

        // Add Pro bonus if applicable
        if (isPro && server.pricingTier.equals("Premium", ignoreCase = true)) {
            val proBonus = 10.0 // 10 points bonus for Premium servers
            totalScore += proBonus * PRO_BONUS_WEIGHT
            Log.v(TAG, "Server ${server.serverName} gets Pro bonus: +${proBonus * PRO_BONUS_WEIGHT}")
        }

        // Log detailed scoring
        Log.v(TAG, "Server ${server.serverName}: " +
                "Latency=${latency}ms (score=${"%.1f".format(latencyScore)}, weight=${"%.0f".format(latencyWeight * 100)}%), " +
                "Load=${load}% (score=${"%.1f".format(loadScore)}, weight=${"%.0f".format(loadWeight * 100)}%), " +
                "Total=${"%.2f".format(totalScore)}")

        return totalScore
    }

    /**
     * Calculate latency score (0-100)
     * Lower latency = higher score
     *
     * - 0-50ms: 100 points (excellent)
     * - 50-150ms: 90-70 points (good)
     * - 150-300ms: 70-40 points (acceptable)
     * - 300-500ms: 40-0 points (poor)
     * - 500ms+: 0 points (very poor)
     */
    private fun calculateLatencyScore(latency: Int): Double {
        return when {
            latency <= 50 -> 100.0
            latency <= 150 -> 100.0 - ((latency - 50) * 0.3) // 100 -> 70
            latency <= 300 -> 70.0 - ((latency - 150) * 0.2) // 70 -> 40
            latency <= MAX_ACCEPTABLE_LATENCY -> 40.0 - ((latency - 300) * 0.2) // 40 -> 0
            else -> 0.0 // Very high latency
        }.coerceIn(0.0, 100.0)
    }

    /**
     * Calculate load score (0-100)
     * Lower load = higher score
     *
     * - 0-30%: 100 points (excellent)
     * - 30-60%: 100-70 points (good)
     * - 60-80%: 70-30 points (moderate)
     * - 80-90%: 30-10 points (high)
     * - 90-100%: 10-0 points (overloaded)
     */
    private fun calculateLoadScore(load: Int): Double {
        return when {
            load <= 30 -> 100.0
            load <= 60 -> 100.0 - ((load - 30) * 1.0) // 100 -> 70
            load <= 80 -> 70.0 - ((load - 60) * 2.0) // 70 -> 30
            load <= 90 -> 30.0 - ((load - 80) * 2.0) // 30 -> 10
            else -> 10.0 - ((load - 90) * 1.0) // 10 -> 0
        }.coerceIn(0.0, 100.0)
    }

    /**
     * Data class to hold server with its calculated score
     */
    private data class ScoredServer(
        val server: RemoteVpnServer,
        val score: Double
    )
}

