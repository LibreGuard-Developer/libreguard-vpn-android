# Android App Integration: Server Load & Latency

This document describes the changes needed in the LibreGuard Android app to support:
1. **Real latency measurement** using port 5001 ping endpoint
2. **Server load display** using data from ManagementPanel API

## Summary of Changes

### 1. Update `RemoteVpnServer` Model

**File**: `app/src/main/java/net/libreguard/vpn/network/ApiService.kt`

Add these fields to the `RemoteVpnServer` data class:

```kotlin
data class RemoteVpnServer(
    val id: Int,
    val serverName: String,
    val serverIp: String,
    val serverHostname: String?,
    val country: String,
    val city: String,
    val linkSpeed: Int,
    val pricingTier: String,
    // NEW FIELDS:
    val load: Int? = null,                  // Server load percentage (0-100)
    val activeConnections: Int? = null,     // Number of active VPN connections
    val latencyPingPort: Int = 5001,        // Port for latency measurement
    val loadDataFresh: Boolean = false      // Whether load data is recent
)
```

### 2. Create Latency Ping Service

**New File**: `app/src/main/java/net/libreguard/vpn/network/PingService.kt`

```kotlin
package net.libreguard.vpn.network

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Service for measuring latency to VPN servers via /ping endpoint on port 5001.
 */
object PingService {
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    
    /**
     * Ping a single server and return latency in milliseconds.
     * Returns null if the server is unreachable.
     */
    suspend fun pingServer(serverIp: String, port: Int = 5001): Int? = withContext(Dispatchers.IO) {
        try {
            val url = "http://$serverIp:$port/ping"
            val request = Request.Builder()
                .url(url)
                .get()
                .build()
            
            val startTime = System.currentTimeMillis()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val latency = (System.currentTimeMillis() - startTime).toInt()
                    latency
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Ping multiple servers in parallel and return a map of serverId to latency.
     * Servers that fail to respond will not be included in the result.
     */
    suspend fun pingServers(servers: List<RemoteVpnServer>): Map<Int, Int> = coroutineScope {
        servers.map { server ->
            async {
                val latency = pingServer(server.serverIp, server.latencyPingPort)
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
    }
}
```

### 3. Update ViewModel for Latency Measurement

**File**: `app/src/main/java/net/libreguard/vpn/viewmodel/ViewModel.kt`

Find the `loadRemoteServers()` function and update it to trigger latency measurement:

```kotlin
// In VpnViewModel class, add this function:

/**
 * Measure latency to all servers in the background.
 * Updates serverLatencies StateFlow as results come in.
 */
private fun measureLatencies(servers: List<RemoteVpnServer>) {
    viewModelScope.launch {
        try {
            val latencies = PingService.pingServers(servers)
            _serverLatencies.value = latencies
        } catch (e: Exception) {
            // Log error but don't crash - latency is optional
            Log.w("VpnViewModel", "Failed to measure latencies: ${e.message}")
        }
    }
}

// In loadRemoteServers(), after successfully loading servers, call:
// measureLatencies(serverList)
```

Add the import at the top:
```kotlin
import net.libreguard.vpn.network.PingService
```

### 4. Update ServerListScreen for Real Load

**File**: `app/src/main/java/net/libreguard/vpn/ui/screens/ServerListScreen.kt`

Change this line in `ServerCard`:
```kotlin
// FROM:
val load = remember { (20..80).random() }

// TO:
val load = server.load ?: 0  // Use real load from API, default to 0 if unavailable
```

Also update the load bar visibility:
```kotlin
// Only show load bar if we have real data
if (server.load != null) {
    Spacer(modifier = Modifier.height(8.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Secondary)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(load / 100f)
                .clip(RoundedCornerShape(2.dp))
                .background(getLoadColor(load))
        )
    }
}
```

## Testing

### Test Latency Measurement

1. Build and run the app
2. Navigate to Server List screen
3. Observe that latency values appear next to each server (not just "...")
4. Values should be realistic (e.g., 20-200ms depending on distance)

### Test Load Display

1. Ensure ManagementPanel is running and receiving load reports
2. Build and run the app
3. Navigate to Server List screen
4. Load percentages should show real values instead of random numbers
5. Load bars should reflect actual server load

### Verify Ping Endpoint

Test manually from Android device:
```bash
adb shell curl http://<server-ip>:5001/ping
# Should return: {"pong":true,"timestamp":1703868000000}
```

## Security Considerations

1. **Port 5001 is HTTP-only** - Intentional for latency measurement (no TLS overhead)
2. **No authentication on /ping** - Only returns timestamp, no sensitive data
3. **Parallel pings** - May trigger rate limiting on some networks

## Firewall Notes

Ensure port 5001 is open on all VPN servers:
- Must be accessible from mobile networks
- Test from different carriers/networks
