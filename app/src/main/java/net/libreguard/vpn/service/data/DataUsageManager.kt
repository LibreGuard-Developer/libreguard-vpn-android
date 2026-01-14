package net.libreguard.vpn.service.data

import android.content.Context
import android.content.SharedPreferences
import android.net.TrafficStats
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.libreguard.vpn.network.RetrofitClient
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * Data Usage Manager - Server-first quota tracking with local session monitoring
 *
 * Architecture:
 * 1. Server quota API (/api/usage/quota) is the source of truth for total usage and limits
 * 2. Local session tracking (TrafficStats) is used ONLY for:
 *    - Real-time speed display (download/upload Mbps)
 *    - Current session usage display
 * 3. Session usage is calculated using app-specific UID traffic (not total device traffic)
 * 4. Local data persistence is kept for session restoration on app restart
 * 5. Pre-flight connection checks via /api/usage/can-connect enforce quota limits
 *
 * Key change from previous version: Local session deltas no longer accumulate into
 * totalBytesUsed. The server tracks all actual VPN data usage.
 */
class DataUsageManager(private val context: Context) {

    private val TAG = "DataUsageManager"

    // Default 5GB limit in bytes (can be overridden by server)
    private var dataLimitBytes = 5L * 1024 * 1024 * 1024

    // Current user ID for user-specific storage
    private var currentUserId: String? = null

    // Auth token for API calls
    private var authToken: String? = null
    private var previousAuthToken: String? = null  // Track previous token to detect changes

    // Server-synced quota state
    private var isUnlimited: Boolean = false
    private var serverUsedBytes: Long? = null  // null = not synced yet
    private var resetDate: String? = null  // ISO 8601 format from API
    private var lastServerSync: Long = 0L
    private val SERVER_SYNC_INTERVAL_MS = 5 * 60 * 1000L  // Sync every 5 minutes

    // Shared preferences for persistent storage (user-specific)
    private fun getPrefs(): SharedPreferences {
        val prefsName = if (currentUserId != null) {
            "vpn_data_usage_${currentUserId}"
        } else {
            "vpn_data_usage_default"
        }
        return context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    }

    // Atomic counters for thread safety
    private val totalBytesUsed = AtomicLong(0L)
    private val sessionBytesUsed = AtomicLong(0L)

    // Session persistence - track if VPN is currently active
    private var isVpnSessionActive = false

    // Baseline traffic stats when VPN starts
    private var baselineRxBytes = 0L
    private var baselineTxBytes = 0L
    private var vpnStartTime = 0L

    // Speed calculation tracking
    private var lastRxBytes = 0L
    private var lastTxBytes = 0L
    private var lastSpeedUpdateTime = 0L

    // Monitoring job and scope
    private var monitoringJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // State flows for UI updates
    private val _dataUsage = MutableStateFlow(DataUsageInfo())
    val dataUsage: StateFlow<DataUsageInfo> = _dataUsage.asStateFlow()

    // Security: Simple integrity check using hash
    private fun calculateIntegrityHash(bytes: Long, timestamp: Long, userId: String): String {
        // Use stable app-scoped value instead of device serial for integrity seed
        val seed = context.packageName
        val data = "$bytes:$timestamp:$userId:$seed"
        return data.hashCode().toString()
    }

    init {
        // Don't load data until user is set
    }

    /**
     * Set the current user ID for user-specific data tracking
     */
    fun setUserId(userId: String) {
        if (currentUserId != userId) {
            scope.launch {
                // CRITICAL FIX: Properly clear previous user's monitoring before switching
                if (currentUserId != null && monitoringJob?.isActive == true) {
                    Log.d(TAG, "Stopping monitoring for previous user: $currentUserId")
                    stopMonitoring()
                    // Wait a bit to ensure the previous monitoring job is fully stopped
                    kotlinx.coroutines.delay(100)
                }

                val previousUserId = currentUserId
                currentUserId = userId

                // Clear any existing monitoring data to prevent cross-user contamination
                totalBytesUsed.set(0L)
                sessionBytesUsed.set(0L)
                isVpnSessionActive = false

                loadPersistedData()
                Log.d(TAG, "Switched from user: $previousUserId to user: $userId")
            }
        }
    }

    /**
     * Clear current user (logout)
     */
    suspend fun clearUser() {
        if (currentUserId != null) {
            stopMonitoring()
            // CRITICAL FIX: Save current data usage before clearing in-memory state
            // DO NOT clear currentUserId yet - saveDataUsage needs it!
            saveDataUsage()
            Log.d(TAG, "Saved final data usage before logout: ${formatBytes(totalBytesUsed.get())}")
        }

        // NOW clear everything including currentUserId
        currentUserId = null
        totalBytesUsed.set(0L)
        sessionBytesUsed.set(0L)
        isVpnSessionActive = false
        updateDataUsageInfo()
        Log.d(TAG, "Cleared current user state. Previous user's data is persisted.")
    }

    /**
     * Load persisted data usage from encrypted storage
     */
    private fun loadPersistedData() {
        if (currentUserId == null) return

        try {
            val prefs = getPrefs()
            val storedBytes = prefs.getLong(PREF_TOTAL_BYTES, 0L)
            val storedTimestamp = prefs.getLong(PREF_LAST_UPDATE, 0L)
            val storedHash = prefs.getString(PREF_INTEGRITY_HASH, "")
            val storedSessionBytes = prefs.getLong(PREF_SESSION_BYTES, 0L)
            val wasVpnActive = prefs.getBoolean(PREF_VPN_SESSION_ACTIVE, false)
            val storedBaselineRx = prefs.getLong(PREF_BASELINE_RX, 0L)
            val storedBaselineTx = prefs.getLong(PREF_BASELINE_TX, 0L)
            val storedVpnStartTime = prefs.getLong(PREF_VPN_START_TIME, 0L)

            // CRITICAL FIX: Always load the stored data, even if integrity check fails
            // The integrity check was too strict and causing data loss
            totalBytesUsed.set(storedBytes)
            Log.d(TAG, "Loaded total data usage: ${formatBytes(storedBytes)} for user $currentUserId")

            // Verify integrity - but DON'T reset data if it fails, just log warning
            if (!storedHash.isNullOrEmpty()) {
                val expectedHash = calculateIntegrityHash(storedBytes, storedTimestamp, currentUserId!!)
                if (storedHash != expectedHash) {
                    Log.w(TAG, "Data integrity check failed for user $currentUserId, but keeping data anyway")
                    Log.d(TAG, "Expected hash: $expectedHash, Stored hash: $storedHash")
                } else {
                    Log.d(TAG, "Data integrity check passed for user $currentUserId")
                }
            } else {
                Log.d(TAG, "No integrity hash found, assuming first-time user")
            }

            // Restore session data if VPN was active when app was closed
            if (wasVpnActive == true && isVpnStillActive()) {
                sessionBytesUsed.set(storedSessionBytes)
                baselineRxBytes = storedBaselineRx
                baselineTxBytes = storedBaselineTx
                vpnStartTime = storedVpnStartTime
                isVpnSessionActive = true

                // Resume monitoring if VPN is still active
                if (monitoringJob?.isActive != true) {
                    startMonitoringInternal(restoreSession = true)
                }

                Log.d(TAG, "Restored active VPN session - Total: ${formatBytes(storedBytes)}, Session: ${formatBytes(storedSessionBytes)}")
            } else {
                sessionBytesUsed.set(0L)
                isVpnSessionActive = false
                Log.d(TAG, "VPN no longer active, cleared session data - Total: ${formatBytes(storedBytes)}")
            }

            Log.d(TAG, "Successfully loaded persisted data for user $currentUserId: Total=${formatBytes(storedBytes)}, Session=${formatBytes(sessionBytesUsed.get())}")

            updateDataUsageInfo()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load persisted data for user $currentUserId", e)
            // CRITICAL FIX: Don't reset data on exception - just initialize with zero and let monitoring rebuild
            Log.w(TAG, "Initializing with zero data due to loading error, but this won't overwrite any saved data")
            updateDataUsageInfo()
        }
    }

    /**
     * Check if VPN is still active (to restore session data)
     */
    private fun isVpnStillActive(): Boolean {
        return try {
            // Method 1: Check ConnectivityManager (Most reliable)
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val activeNetwork = connectivityManager.activeNetwork
            val caps = connectivityManager.getNetworkCapabilities(activeNetwork)
            if (caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true) {
                Log.d(TAG, "VPN still active (ConnectivityManager check)")
                return true
            }

            // Method 2: Fallback to NetworkInterfaces
            val networkInterfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (networkInterfaces.hasMoreElements()) {
                val networkInterface = networkInterfaces.nextElement()
                if ((networkInterface.name.startsWith("tun") || networkInterface.name.startsWith("ipsec")) && networkInterface.isUp) {
                    Log.d(TAG, "VPN still active - found interface: ${networkInterface.name}")
                    return true
                }
            }
            false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check VPN status: ${e.message}")
            false
        }
    }

    /**
     * Save data usage to persistent storage with integrity check
     */
    private fun saveDataUsage() {
        if (currentUserId == null) return

        try {
            val currentBytes = totalBytesUsed.get()
            val currentSessionBytes = sessionBytesUsed.get()
            val timestamp = System.currentTimeMillis()
            val hash = calculateIntegrityHash(currentBytes, timestamp, currentUserId!!)

            getPrefs().edit()
                .putLong(PREF_TOTAL_BYTES, currentBytes)
                .putLong(PREF_SESSION_BYTES, currentSessionBytes)
                .putLong(PREF_LAST_UPDATE, timestamp)
                .putString(PREF_INTEGRITY_HASH, hash)
                .putBoolean(PREF_VPN_SESSION_ACTIVE, isVpnSessionActive)
                .putLong(PREF_BASELINE_RX, baselineRxBytes)
                .putLong(PREF_BASELINE_TX, baselineTxBytes)
                .putLong(PREF_VPN_START_TIME, vpnStartTime)
                .apply()

            Log.v(TAG, "Saved data usage for user $currentUserId: Total=${formatBytes(currentBytes)}, Session=${formatBytes(currentSessionBytes)}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save data usage for user $currentUserId", e)
        }
    }

    /**
     * Start monitoring data usage when VPN connects
     */
    fun startMonitoring() {
        startMonitoringInternal(restoreSession = false)
    }

    /**
     * Internal method to start monitoring with optional session restoration
     */
    private fun startMonitoringInternal(restoreSession: Boolean = false) {
        Log.d(TAG, "Starting data usage monitoring (restoreSession=$restoreSession)")

        if (!restoreSession) {
            // Record baseline traffic stats for new session using app-specific UID
            val appUid = android.os.Process.myUid()
            val uidRxBytes = TrafficStats.getUidRxBytes(appUid)
            val uidTxBytes = TrafficStats.getUidTxBytes(appUid)

            // Use UID-specific traffic if available, otherwise fall back to total
            baselineRxBytes = if (uidRxBytes != TrafficStats.UNSUPPORTED.toLong()) {
                uidRxBytes
            } else {
                TrafficStats.getTotalRxBytes()
            }

            baselineTxBytes = if (uidTxBytes != TrafficStats.UNSUPPORTED.toLong()) {
                uidTxBytes
            } else {
                TrafficStats.getTotalTxBytes()
            }

            vpnStartTime = System.currentTimeMillis()
            sessionBytesUsed.set(0L)

            Log.d(TAG, "Baseline set: RX=${formatBytes(baselineRxBytes)}, TX=${formatBytes(baselineTxBytes)}")
        }

        // Initialize speed tracking with same UID logic
        val appUid = android.os.Process.myUid()
        val uidRxBytes = TrafficStats.getUidRxBytes(appUid)
        val uidTxBytes = TrafficStats.getUidTxBytes(appUid)

        lastRxBytes = if (uidRxBytes != TrafficStats.UNSUPPORTED.toLong()) {
            uidRxBytes
        } else {
            TrafficStats.getTotalRxBytes()
        }

        lastTxBytes = if (uidTxBytes != TrafficStats.UNSUPPORTED.toLong()) {
            uidTxBytes
        } else {
            TrafficStats.getTotalTxBytes()
        }

        lastSpeedUpdateTime = System.currentTimeMillis()

        // Mark session as active
        isVpnSessionActive = true

        // Cancel any existing monitoring
        monitoringJob?.cancel()

        // Start new monitoring job
        monitoringJob = scope.launch {
            while (isActive) {
                try {
                    updateDataUsageStats()
                    delay(UPDATE_INTERVAL_MS)
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating data usage stats", e)
                    delay(UPDATE_INTERVAL_MS * 2) // Back off on error
                }
            }
        }
    }

    /**
     * Stop monitoring when VPN disconnects
     */
    fun stopMonitoring() {
        Log.d(TAG, "Stopping data usage monitoring")
        isVpnSessionActive = false
        monitoringJob?.cancel()

        // Final update to capture any remaining usage
        scope.launch {
            updateDataUsageStats()
            saveDataUsage()
        }
    }

    /**
     * Update data usage statistics using multiple methods for accuracy
     */
    private suspend fun updateDataUsageStats() {
        try {
            val currentTime = System.currentTimeMillis()

            // Get app-specific traffic (more accurate than total device traffic)
            val appUid = android.os.Process.myUid()
            val currentRxBytes = TrafficStats.getUidRxBytes(appUid)
            val currentTxBytes = TrafficStats.getUidTxBytes(appUid)

            // Fallback to total traffic if UID stats unavailable
            val actualRxBytes = if (currentRxBytes != TrafficStats.UNSUPPORTED.toLong()) {
                currentRxBytes
            } else {
                TrafficStats.getTotalRxBytes()
            }

            val actualTxBytes = if (currentTxBytes != TrafficStats.UNSUPPORTED.toLong()) {
                currentTxBytes
            } else {
                TrafficStats.getTotalTxBytes()
            }

            // Calculate session usage since VPN started (for real-time display only)
            val sessionRx = max(0L, actualRxBytes - baselineRxBytes)
            val sessionTx = max(0L, actualTxBytes - baselineTxBytes)
            val currentSessionUsage = sessionRx + sessionTx

            // Update session usage (for display purposes only)
            sessionBytesUsed.set(currentSessionUsage)

            // NOTE: We DO NOT add session delta to totalBytesUsed anymore!
            // The server quota API is now the source of truth for total usage.
            // Local session tracking is only for real-time speed/session display.

            // Calculate real-time speeds (Mbps) for UI
            var downloadSpeedMbps = 0.0
            var uploadSpeedMbps = 0.0

            if (lastSpeedUpdateTime > 0) {
                val timeDeltaMs = currentTime - lastSpeedUpdateTime
                if (timeDeltaMs > 0) {
                    val timeDeltaSeconds = timeDeltaMs / 1000.0

                    // Calculate bytes transferred since last measurement
                    val rxDelta = max(0L, actualRxBytes - lastRxBytes)
                    val txDelta = max(0L, actualTxBytes - lastTxBytes)

                    // Convert to Mbps: (bytes / seconds) * 8 bits/byte / 1,000,000 bits/Mbps
                    downloadSpeedMbps = (rxDelta / timeDeltaSeconds * 8.0) / 1_000_000.0
                    uploadSpeedMbps = (txDelta / timeDeltaSeconds * 8.0) / 1_000_000.0

                    Log.v(TAG, "Speed: ↓${String.format("%.2f", downloadSpeedMbps)} Mbps ↑${String.format("%.2f", uploadSpeedMbps)} Mbps (Session: ${formatBytes(currentSessionUsage)})")
                }
            }

            // Update last values for next speed calculation
            lastRxBytes = actualRxBytes
            lastTxBytes = actualTxBytes
            lastSpeedUpdateTime = currentTime

            // Update UI with usage and speed data
            withContext(Dispatchers.Main) {
                updateDataUsageInfo(downloadSpeedMbps, uploadSpeedMbps)
            }

            // Save session state (but not adding to total anymore)
            saveDataUsage()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to update data usage stats", e)
        }
    }

    /**
     * Get data usage from VPN network interfaces (tun/tap)
     */
    private fun getVpnInterfaceUsage(): Long {
        return try {
            val networkInterfaces = java.net.NetworkInterface.getNetworkInterfaces()
            val vpnUsage = 0L

            while (networkInterfaces.hasMoreElements()) {
                val networkInterface = networkInterfaces.nextElement()

                // Look for VPN interfaces (typically tun0, tun1, etc.)
                if (networkInterface.name.startsWith("tun") && networkInterface.isUp) {
                    // Note: Android doesn't provide per-interface byte counters directly
                    // This is a placeholder for VPN-specific monitoring
                    // In practice, this would require native code or root access
                    Log.v(TAG, "VPN interface detected: ${networkInterface.name}")
                }
            }

            vpnUsage
        } catch (e: Exception) {
            Log.v(TAG, "Failed to get VPN interface usage: ${e.message}")
            0L
        }
    }

    /**
     * Update the data usage info for UI consumption
     */
    private fun updateDataUsageInfo(downloadSpeedMbps: Double = 0.0, uploadSpeedMbps: Double = 0.0) {
        // Use server-synced usage if available, otherwise use local tracking
        val currentTotal = serverUsedBytes ?: totalBytesUsed.get()
        val currentSession = sessionBytesUsed.get()

        // Use a safe limit to avoid divide-by-zero if server returned 0
        val limitBytes = when {
            isUnlimited -> dataLimitBytes
            dataLimitBytes > 0L -> dataLimitBytes
            else -> DEFAULT_FREE_LIMIT_BYTES
        }

        // Calculate usage percentage (0 for unlimited users)
        val usagePercentage = if (isUnlimited) 0f else (currentTotal.toDouble() / limitBytes * 100).toFloat()

        // Calculate remaining bytes
        val remainingBytes = if (isUnlimited) Long.MAX_VALUE else max(0L, limitBytes - currentTotal)

        // Check if over limit
        val isOverLimit = !isUnlimited && currentTotal >= limitBytes

        val info = DataUsageInfo(
            totalBytesUsed = currentTotal,
            sessionBytesUsed = currentSession,
            limitBytes = limitBytes,
            usagePercentage = usagePercentage,
            isNearLimit = !isUnlimited && usagePercentage > 80f,
            formattedTotal = formatBytes(currentTotal),
            formattedSession = formatBytes(currentSession),
            formattedLimit = if (isUnlimited) "Unlimited" else formatBytes(limitBytes),
            downloadSpeedMbps = downloadSpeedMbps,
            uploadSpeedMbps = uploadSpeedMbps,
            isUnlimited = isUnlimited,
            isOverLimit = isOverLimit,
            formattedRemaining = if (isUnlimited) "Unlimited" else formatBytes(remainingBytes),
            resetDate = resetDate  // Add reset date from API
        )

        _dataUsage.value = info

        // Log warning if approaching limit (only for limited users)
        if (!isUnlimited && usagePercentage > 80f) {
            Log.w(TAG, "Data usage approaching limit: ${usagePercentage}%")
        }
    }

    /**
     * Reset data usage counters (admin function)
     */
    fun resetDataUsage() {
        totalBytesUsed.set(0L)
        sessionBytesUsed.set(0L)

        getPrefs().edit().clear().apply()
        updateDataUsageInfo()

        Log.i(TAG, "Data usage counters reset")
    }

    /**
     * Set auth token for API calls
     * Only clears cached data if token actually changes (logout), not on screen navigation
     */
    fun setAuthToken(token: String?) {
        // Only reset data if token actually changed (prevents flickering on screen navigation)
        if (token != previousAuthToken) {
            authToken = token
            previousAuthToken = token

            if (token == null) {
                // Only clear on logout (token becomes null)
                totalBytesUsed.set(0L)
                sessionBytesUsed.set(0L)
                serverUsedBytes = null
                resetDate = null
                lastServerSync = 0L
                updateDataUsageInfo()
                Log.d(TAG, "Auth token cleared on logout")
            } else {
                // New token - may be new user, clear counters to prepare for fresh sync
                totalBytesUsed.set(0L)
                sessionBytesUsed.set(0L)
                serverUsedBytes = null
                resetDate = null
                lastServerSync = 0L
                updateDataUsageInfo()
                Log.d(TAG, "Auth token set - awaiting server sync")
            }
        }
        // If token is same as previous, do nothing (normal screen navigation)
    }

    /**
     * Sync data usage quota from server
     * Call this on app launch and periodically to refresh quota display
     */
    suspend fun syncQuotaFromServer() {
        val token = authToken
        if (token == null) {
            Log.w(TAG, "Cannot sync quota: no auth token")
            return
        }

        // Rate limit syncing
        val now = System.currentTimeMillis()
        if (now - lastServerSync < SERVER_SYNC_INTERVAL_MS && lastServerSync > 0) {
            Log.d(TAG, "Skipping quota sync, last sync was ${(now - lastServerSync) / 1000}s ago")
            return
        }

        try {
            Log.d(TAG, "Syncing quota from server...")
            val response = withContext(Dispatchers.IO) {
                RetrofitClient.instance.getUsageQuota("Bearer $token")
            }

            if (response.isSuccessful) {
                val quota = response.body()
                if (quota != null) {
                    // Use the correct field names from API response
                    val safeUsed = max(0L, quota.bytesUsed)
                    val effectiveLimit = when {
                        quota.isUnlimited -> Long.MAX_VALUE
                        quota.bytesLimit != null && quota.bytesLimit > 0 -> quota.bytesLimit
                        else -> DEFAULT_FREE_LIMIT_BYTES // fallback for bad server responses
                    }

                    // Update server-synced values
                    serverUsedBytes = safeUsed
                    dataLimitBytes = effectiveLimit
                    isUnlimited = quota.isUnlimited
                    resetDate = quota.resetDate  // Capture reset date from API
                    lastServerSync = now

                    Log.d(TAG, "Quota synced: used=${formatBytes(safeUsed)}, " +
                            "limit=${if (quota.isUnlimited) "Unlimited" else formatBytes(effectiveLimit)}, " +
                            "rawLimit=${quota.bytesLimit}, remaining=${quota.bytesRemaining ?: 0L}, " +
                            "usagePercentage=${quota.usagePercentage}%, resetDate=${resetDate}")

                    // Update UI with server data
                    withContext(Dispatchers.Main) {
                        updateDataUsageInfo()
                    }
                }
            } else {
                Log.w(TAG, "Failed to sync quota: ${response.code()} - ${response.message()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing quota from server", e)
            // Keep using local/cached values on error
        }
    }

    /**
     * Pre-flight check before VPN connection
     * Returns CanConnectResult with allowed/blocked status
     */
    suspend fun checkCanConnect(): CanConnectResult? {
        val token = authToken
        if (token == null) {
            Log.w(TAG, "Cannot check connection: no auth token")
            // Allow connection if no token (will fail at VPN level anyway)
            return CanConnectResult(allowed = true, reason = null, message = null, resetDate = null)
        }

        try {
            Log.d(TAG, "Checking can-connect with server...")
            val response = withContext(Dispatchers.IO) {
                RetrofitClient.instance.checkCanConnect("Bearer $token")
            }

            if (response.isSuccessful) {
                val canConnectResp = response.body()
                if (canConnectResp != null) {
                    Log.d(TAG, "Can connect check: allowed=${canConnectResp.allowed}, reason=${canConnectResp.reason}, message=${canConnectResp.message}, bytesUsed=${canConnectResp.bytesUsed}")

                    // Fail-open if server returns a malformed/empty block response
                    if (!canConnectResp.allowed && canConnectResp.reason.isNullOrBlank() && canConnectResp.message.isNullOrBlank()) {
                        Log.w(TAG, "Can-connect response blocked without reason/message; allowing connection to avoid false lock-out")
                        return CanConnectResult(true, null, null, canConnectResp.resetDate)
                    }

                    return CanConnectResult(
                        allowed = canConnectResp.allowed,
                        reason = canConnectResp.reason,
                        message = canConnectResp.message,
                        resetDate = canConnectResp.resetDate
                    )
                }
            } else {
                Log.w(TAG, "Failed to check can-connect: ${response.code()} - ${response.message()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking can-connect", e)
        }

        // On error, allow connection (fail gracefully)
        // The VPN server will enforce limits if needed
        return CanConnectResult(allowed = true, reason = null, message = null, resetDate = null)
    }

    /**
     * Get current data usage for server reporting
     */
    fun getDataUsageForServerReport(): DataUsageReport {
        return DataUsageReport(
            totalBytes = totalBytesUsed.get(),
            sessionBytes = sessionBytesUsed.get(),
            timestamp = System.currentTimeMillis(),
            deviceId = getDeviceIdentifier(),
            vpnSessionDuration = if (vpnStartTime > 0) System.currentTimeMillis() - vpnStartTime else 0L
        )
    }

    /**
     * Get a privacy-preserving device identifier
     */
    private fun getDeviceIdentifier(): String {
        return try {
            // Use an app-scoped stored UUID instead of Android ID or Serial to avoid privacy warnings
            val devPrefs = context.getSharedPreferences("vpn_device_prefs", Context.MODE_PRIVATE)
            var id = devPrefs.getString("device_id", null)
            if (id.isNullOrBlank()) {
                id = java.util.UUID.randomUUID().toString().take(8)
                devPrefs.edit().putString("device_id", id).apply()
            }
            id
        } catch (e: Exception) {
            "unknown"
        }
    }

    /**
     * Format bytes into human-readable string
     */
    private fun formatBytes(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var size = bytes.toDouble()
        var unitIndex = 0

        while (size >= 1024 && unitIndex < units.size - 1) {
            size /= 1024
            unitIndex++
        }

        return if (unitIndex <= 1) {
            "${size.toInt()} ${units[unitIndex]}"
        } else {
            "%.1f %s".format(size, units[unitIndex])
        }
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        monitoringJob?.cancel()
        scope.cancel()
        saveDataUsage()
    }

    companion object {
        private const val UPDATE_INTERVAL_MS = 2000L // Update every 2 seconds
        private const val PREF_TOTAL_BYTES = "total_bytes_used"
        private const val PREF_LAST_UPDATE = "last_update_timestamp"
        private const val PREF_INTEGRITY_HASH = "integrity_hash"
        private const val PREF_SESSION_BYTES = "session_bytes_used"
        private const val PREF_VPN_SESSION_ACTIVE = "vpn_session_active"
        private const val PREF_BASELINE_RX = "baseline_rx_bytes"
        private const val PREF_BASELINE_TX = "baseline_tx_bytes"
        private const val PREF_VPN_START_TIME = "vpn_start_time"
        private const val DEFAULT_FREE_LIMIT_BYTES = 5L * 1024 * 1024 * 1024 // 5GB fallback when server limit is invalid
    }
}

/**
 * Data usage information for UI display
 */
data class DataUsageInfo(
    val totalBytesUsed: Long = 0L,
    val sessionBytesUsed: Long = 0L,
    val limitBytes: Long = 5L * 1024 * 1024 * 1024, // 5GB default
    val usagePercentage: Float = 0f,
    val isNearLimit: Boolean = false,
    val formattedTotal: String = "0 B",
    val formattedSession: String = "0 B",
    val formattedLimit: String = "5.0 GB",
    val downloadSpeedMbps: Double = 0.0,
    val uploadSpeedMbps: Double = 0.0,
    // Server-synced fields
    val isUnlimited: Boolean = false,
    val isOverLimit: Boolean = false,
    val formattedRemaining: String = "5.0 GB",
    val resetDate: String? = null  // ISO 8601 format: "2026-02-01T00:00:00Z"
)

/**
 * Data usage report for server synchronization
 */
data class DataUsageReport(
    val totalBytes: Long,
    val sessionBytes: Long,
    val timestamp: Long,
    val deviceId: String,
    val vpnSessionDuration: Long
)

/**
 * Result of pre-flight can-connect check
 */
data class CanConnectResult(
    val allowed: Boolean,
    val reason: String?,
    val message: String?,
    val resetDate: String?
)

