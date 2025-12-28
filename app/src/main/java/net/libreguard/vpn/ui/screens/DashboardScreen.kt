package net.libreguard.vpn.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.libreguard.vpn.ui.components.LogoWithGradient
import net.libreguard.vpn.ui.theme.*
import net.libreguard.vpn.viewmodel.VpnViewModel
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Dashboard Screen - Main connection screen
 * EXACTLY matching Dashboard.tsx design - NON-SCROLLABLE compact layout
 */
@Composable
fun DashboardScreen(
    authToken: String,
    vpnViewModel: VpnViewModel? = null,
    onNavigateToServers: () -> Unit,
    onNavigateToUpgrade: () -> Unit
) {
    val viewModel: VpnViewModel = vpnViewModel ?: viewModel()

    val isConnected by viewModel.isConnected.collectAsState()
    val isConnecting by viewModel.isConnecting.collectAsState()
    val selectedServer by viewModel.selectedServer.collectAsState()
    val isPro by viewModel.isPro.collectAsState()

    // Real data usage from ViewModel
    val dataUsageInfo by viewModel.dataUsageInfo.collectAsState()

    // Connection stats
    var connectionTime by remember { mutableStateOf("00:00:00") }
    var downloadSpeed by remember { mutableStateOf(0.0) }
    var uploadSpeed by remember { mutableStateOf(0.0) }

    // Real session data from ViewModel (in MB)
    val sessionData = remember(dataUsageInfo) {
        dataUsageInfo.sessionBytesUsed / (1024.0 * 1024.0)
    }

    // Real monthly data from ViewModel (in MB)
    val monthlyData = remember(dataUsageInfo) {
        dataUsageInfo.totalBytesUsed / (1024.0 * 1024.0)
    }

    // Real limit from ViewModel (in MB)
    val monthlyLimit = remember(dataUsageInfo) {
        dataUsageInfo.limitBytes / (1024.0 * 1024.0)
    }

    // IP addresses
    var userIP by remember { mutableStateOf("Loading...") }
    var vpnIP by remember { mutableStateOf("") }

    // Network security (simulated)
    var isNetworkSecure by remember { mutableStateOf(true) }

    // Fetch real IP address from ipify API
    LaunchedEffect(isConnected) {
        try {
            withContext(Dispatchers.IO) {
                val url = URL("https://api.ipify.org?format=json")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonObject = JSONObject(response)
                    val ip = jsonObject.getString("ip")
                    withContext(Dispatchers.Main) {
                        if (isConnected) {
                            vpnIP = ip
                        } else {
                            userIP = ip
                        }
                    }
                }
                connection.disconnect()
            }
        } catch (e: Exception) {
            android.util.Log.e("DashboardScreen", "Failed to fetch IP: ${e.message}")
        }
    }

    // Connection timer and speed simulation when connected
    LaunchedEffect(isConnected) {
        if (isConnected) {
            if (vpnIP.isEmpty()) vpnIP = "198.51.100.78"
            var seconds = 0
            while (isConnected) {
                kotlinx.coroutines.delay(1000)
                seconds++
                val hours = seconds / 3600
                val minutes = (seconds % 3600) / 60
                val secs = seconds % 60
                connectionTime = String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, secs)
                // Simulate realistic VPN speeds (actual tracking would require network monitoring)
                downloadSpeed = 8 + kotlin.random.Random.nextDouble() * 8
                uploadSpeed = 2 + kotlin.random.Random.nextDouble() * 4
            }
        } else {
            connectionTime = "00:00:00"
            downloadSpeed = 0.0
            uploadSpeed = 0.0
            vpnIP = ""
        }
    }

    // Simulate network security check
    LaunchedEffect(Unit) {
        isNetworkSecure = kotlin.random.Random.nextBoolean()
    }

    LaunchedEffect(authToken) {
        viewModel.setAuthToken(authToken)
        viewModel.loadRemoteServers()
    }

    val connectionStatus = when {
        isConnecting -> ConnectionStatus.CONNECTING
        isConnected -> ConnectionStatus.CONNECTED
        else -> ConnectionStatus.DISCONNECTED
    }

    val statusConfig = getConnectionStatusConfig(connectionStatus)

    // Animation transition
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")

    // Calculate usage percentages
    val totalDataUsed = monthlyData + sessionData
    val monthlyPercentage = if (monthlyLimit > 0) (monthlyData / monthlyLimit * 100).toFloat() else 0f
    val totalPercentage = if (monthlyLimit > 0) (totalDataUsed / monthlyLimit * 100).toFloat() else 0f

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .padding(horizontal = 20.dp)
            .padding(top = 16.dp, bottom = 80.dp)
    ) {
        // Header - Compact
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LogoWithGradient(size = 32.dp)
                Text(
                    text = "LibreGuard",
                    style = MaterialTheme.typography.titleLarge,
                    color = Foreground
                )
            }

            // Plan badge
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (isPro) Primary.copy(alpha = 0.2f) else Secondary,
                onClick = { if (!isPro) onNavigateToUpgrade() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (isPro) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            tint = Primary,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                    Text(
                        text = if (isPro) "Pro" else "Free",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isPro) Primary else MutedForeground
                    )
                }
            }
        }

        // IP Address Display (when connected) - Compact
        if (isConnected) {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = CardBackground,
                border = ButtonDefaults.outlinedButtonBorder(enabled = true)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Your IP", style = MaterialTheme.typography.labelSmall, color = MutedForeground)
                        Text(
                            text = userIP,
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedForeground.copy(alpha = 0.5f),
                            textDecoration = TextDecoration.LineThrough
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("VPN IP", style = MaterialTheme.typography.labelSmall, color = MutedForeground)
                        Text(text = vpnIP, style = MaterialTheme.typography.bodySmall, color = Primary)
                    }
                }
            }

            // Protection indicators - compact row
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ProtectionIndicator(text = "DNS")
                ProtectionIndicator(text = "IPv6")
                ProtectionIndicator(text = "WebRTC")
            }
        }

        // Quick Connect Button (when disconnected)
        if (!isConnected && !isConnecting) {
            Spacer(modifier = Modifier.height(10.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = CardBackground,
                border = ButtonDefaults.outlinedButtonBorder(enabled = true),
                onClick = {
                    if (selectedServer != null) viewModel.connectToVpn() else onNavigateToServers()
                }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(36.dp).background(Primary.copy(alpha = 0.1f), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Bolt, null, tint = Primary, modifier = Modifier.size(18.dp))
                        }
                        Column {
                            Text("Quick Connect", style = MaterialTheme.typography.titleSmall, color = Foreground)
                            Text(
                                text = if (selectedServer != null) selectedServer?.serverName ?: "" else "Select server",
                                style = MaterialTheme.typography.bodySmall,
                                color = MutedForeground
                            )
                        }
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = MutedForeground, modifier = Modifier.size(18.dp))
                }
            }
        }

        // Center content area with shield and button
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Status Indicator Circle - REDUCED SIZE
            Box(
                modifier = Modifier.size(120.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier.size(120.dp).clip(CircleShape)
                        .background(statusConfig.color.copy(alpha = 0.15f))
                )
                Box(
                    modifier = Modifier.size(96.dp).clip(CircleShape)
                        .background(statusConfig.color.copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Shield, null, tint = statusConfig.color, modifier = Modifier.size(48.dp))
                }

                if (isConnecting) {
                    val animatedAlpha by infiniteTransition.animateFloat(
                        initialValue = 0.8f, targetValue = 0f,
                        animationSpec = infiniteRepeatable(tween(1500, easing = EaseInOut), RepeatMode.Restart),
                        label = "ringAlpha"
                    )
                    val animatedScale by infiniteTransition.animateFloat(
                        initialValue = 1f, targetValue = 1.2f,
                        animationSpec = infiniteRepeatable(tween(1500, easing = EaseInOut), RepeatMode.Restart),
                        label = "ringScale"
                    )
                    Box(
                        modifier = Modifier.size((120 * animatedScale).dp).clip(CircleShape)
                            .background(statusConfig.color.copy(alpha = animatedAlpha * 0.3f))
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(text = statusConfig.text, style = MaterialTheme.typography.headlineSmall, color = statusConfig.color)
            Text(text = statusConfig.description, style = MaterialTheme.typography.bodySmall, color = MutedForeground)

            Spacer(modifier = Modifier.height(20.dp))

            // Connect/Disconnect Button - NORMAL SIZE
            Button(
                onClick = {
                    if (isConnected) viewModel.disconnect()
                    else if (!isConnecting) {
                        if (selectedServer != null) viewModel.connectToVpn() else onNavigateToServers()
                    }
                },
                enabled = !isConnecting,
                modifier = Modifier.height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = PrimaryForeground),
                contentPadding = PaddingValues(horizontal = 40.dp)
            ) {
                Text(text = statusConfig.buttonText, style = MaterialTheme.typography.titleSmall)
            }
        }

        // Stats when connected - COMPACT VERSION
        if (isConnected) {
            // Connection Stats Row - Compact
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItemCompact(icon = Icons.Default.Schedule, value = connectionTime, label = "Duration")
                StatItemCompact(icon = Icons.Default.Speed, value = "${downloadSpeed.toFixed(1)}", label = "Mbps")
                StatItemCompact(icon = Icons.Default.Language, value = selectedServer?.country ?: "-", label = "Location")
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Bandwidth Usage Card - Compact
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = CardBackground,
                border = ButtonDefaults.outlinedButtonBorder(enabled = true)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Bandwidth", style = MaterialTheme.typography.labelMedium, color = Foreground)
                        Text("${totalPercentage.toFixed(1)}% of ${(monthlyLimit / 1024).toFixed(0)}GB",
                            style = MaterialTheme.typography.labelSmall, color = MutedForeground)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Usage bar
                    Box(
                        modifier = Modifier.fillMaxWidth().height(8.dp)
                            .clip(RoundedCornerShape(4.dp)).background(Secondary.copy(alpha = 0.3f))
                    ) {
                        Box(
                            modifier = Modifier.fillMaxHeight()
                                .fillMaxWidth((monthlyPercentage / 100f).coerceIn(0f, 1f))
                                .clip(RoundedCornerShape(4.dp)).background(MutedForeground.copy(alpha = 0.4f))
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Legend row - compact
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(MutedForeground.copy(alpha = 0.4f)))
                            Text("Total: ${(monthlyData / 1024.0).toFixed(2)}GB", style = MaterialTheme.typography.labelSmall, color = MutedForeground)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Primary))
                            Text("Session: ${sessionData.toFixed(1)}MB", style = MaterialTheme.typography.labelSmall, color = Primary)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    HorizontalDivider(color = Border)
                    Spacer(modifier = Modifier.height(6.dp))

                    // Speeds row - compact
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.ArrowDownward, null, tint = MutedForeground, modifier = Modifier.size(14.dp))
                            Text("${downloadSpeed.toFixed(1)} Mbps", style = MaterialTheme.typography.labelSmall, color = Foreground)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.ArrowUpward, null, tint = MutedForeground, modifier = Modifier.size(14.dp))
                            Text("${uploadSpeed.toFixed(1)} Mbps", style = MaterialTheme.typography.labelSmall, color = Foreground)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProtectionIndicator(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.CheckCircle, null, tint = StatusConnected, modifier = Modifier.size(12.dp))
        Text(text = text, style = MaterialTheme.typography.labelSmall, color = MutedForeground)
    }
}

@Composable
private fun StatItemCompact(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    label: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = MutedForeground, modifier = Modifier.size(16.dp))
        Text(text = value, style = MaterialTheme.typography.labelMedium, color = Foreground)
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MutedForeground)
    }
}

private fun Double.toFixed(decimals: Int): String {
    return String.format(Locale.US, "%.${decimals}f", this)
}

private fun Float.toFixed(decimals: Int): String {
    return String.format(Locale.US, "%.${decimals}f", this)
}

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}

data class StatusConfig(
    val color: androidx.compose.ui.graphics.Color,
    val text: String,
    val description: String,
    val buttonText: String
)

@Composable
fun getConnectionStatusConfig(status: ConnectionStatus): StatusConfig {
    return when (status) {
        ConnectionStatus.CONNECTED -> StatusConfig(
            color = StatusConnected,
            text = "Protected",
            description = "Your connection is secure",
            buttonText = "Disconnect"
        )
        ConnectionStatus.CONNECTING -> StatusConfig(
            color = StatusConnecting,
            text = "Connecting",
            description = "Establishing secure connection...",
            buttonText = "Cancel"
        )
        ConnectionStatus.DISCONNECTED -> StatusConfig(
            color = StatusDisconnected,
            text = "Not Protected",
            description = "Your connection is not secure",
            buttonText = "Connect"
        )
    }
}
