# Android App Integration: Server Load & Latency

This document describes the changes needed in the LibreGuard Android app to support:
1. **Real latency measurement** using port 5001 ping endpoint
2. **Server load display** using data from ManagementPanel API (fetched from Prometheus)

## Architecture (Simplified)

```
┌─────────────────────────────────────────────────────────────────────┐
│                      LATENCY MEASUREMENT                            │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│   Android App ────── HTTP GET ────────> VPN Server:5001/ping        │
│                      (direct, no auth)  Returns: {pong, timestamp}  │
│                      Measures RTT                                   │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│                      SERVER LOAD                                    │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│   Android App ←── GET /api/vpn/servers ←── ManagementPanel          │
│                   (includes load %)         (queries Prometheus)    │
│                                                                     │
│   Load is included in the server list response - no extra API call! │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

## Summary of Changes

### Already Done ✅

1. **`RemoteVpnServer` model updated** with new fields:
   - `load: Int?` - Server load percentage (0-100)
   - `activeConnections: Int?` - Number of active VPN connections
   - `latencyPingPort: Int = 5001` - Port for latency measurement
   - `loadDataFresh: Boolean = false` - Whether load data is recent

2. **`PingService.kt` created** - Service for latency measurement

### Still Needed

1. **Update ViewModel** to call `PingService.pingServers()` after loading servers
2. **Update ServerListScreen** to use `server.load` instead of random value

## Changes to Make

### 1. Update ViewModel for Latency Measurement

In your ViewModel (wherever you load servers), add:

```kotlin
import net.libreguard.vpn.network.PingService

// After successfully loading servers:
private fun measureLatencies(servers: List<RemoteVpnServer>) {
    viewModelScope.launch {
        try {
            val latencies = PingService.pingServers(servers)
            _serverLatencies.value = latencies
        } catch (e: Exception) {
            Log.w("VpnViewModel", "Failed to measure latencies: ${e.message}")
        }
    }
}
```

### 2. Update ServerListScreen for Real Load

**File**: `app/src/main/java/net/libreguard/vpn/ui/screens/ServerListScreen.kt`

Change this line in `ServerCard`:
```kotlin
// FROM:
val load = remember { (20..80).random() }

// TO:
val load = server.load ?: 0  // Use real load from API
```

Also update load bar visibility:
```kotlin
// Only show load bar if we have real data
if (server.load != null) {
    // ... load bar code ...
}
```

## API Response Format

The `/api/vpn/servers` endpoint now returns:

```json
{
  "servers": [
    {
      "id": 1,
      "serverName": "DE-MULTI-1",
      "serverIp": "1.2.3.4",
      "serverHostname": "de1.libreguard.net",
      "country": "Germany",
      "city": "Frankfurt",
      "linkSpeed": 1000,
      "pricingTier": "Free",
      "load": 45,                  // NEW: Server load percentage
      "activeConnections": null,   // NEW: May be null
      "latencyPingPort": 5001,     // NEW: Port for ping
      "loadDataFresh": true        // NEW: Is data fresh?
    }
  ]
}
```

## Testing

### Test Latency Measurement

1. Build and run the app
2. Navigate to Server List screen
3. Latency values should appear next to each server
4. Values should be realistic (20-200ms depending on distance)

### Test Load Display

1. Ensure ManagementPanel can reach Prometheus (10.200.0.1:9090)
2. Load percentages should show real values instead of random numbers

### Verify Ping Endpoint

```bash
adb shell curl http://<server-ip>:5001/ping
# Should return: {"pong":true,"timestamp":1703868000000}
```

## Security Considerations

1. **Port 5001 is HTTP-only** - Intentional for latency measurement (no TLS overhead)
2. **No authentication on /ping** - Only returns timestamp, no sensitive data
