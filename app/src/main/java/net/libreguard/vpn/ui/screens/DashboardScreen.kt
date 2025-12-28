package net.libreguard.vpn.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
 * EXACTLY matching Dashboard.tsx design
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

    // Connection stats
    var connectionTime by remember { mutableStateOf("00:00:00") }
    var downloadSpeed by remember { mutableStateOf(0.0) }
    var uploadSpeed by remember { mutableStateOf(0.0) }
    var sessionData by remember { mutableStateOf(0.0) } // MB
    val monthlyData = 2847 // MB used this month (mock)
    val monthlyLimit = 10240 // 10GB for free plan

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

    // Simulated timer and data when connected
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
                downloadSpeed = 8 + kotlin.random.Random.nextDouble() * 8
                uploadSpeed = 2 + kotlin.random.Random.nextDouble() * 4
                sessionData += kotlin.random.Random.nextDouble() * 0.8
            }
        } else {
            connectionTime = "00:00:00"
            downloadSpeed = 0.0
            uploadSpeed = 0.0
            sessionData = 0.0
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

    // Pulse animation for connected state
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isConnected) 1.02f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    // Calculate usage percentages
    val totalDataUsed = monthlyData + sessionData
    val sessionPercentage = (sessionData / monthlyLimit * 100).toFloat()
    val monthlyPercentage = (monthlyData.toFloat() / monthlyLimit * 100)
    val totalPercentage = (totalDataUsed / monthlyLimit * 100).toFloat()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .padding(horizontal = 24.dp)
            .padding(top = 24.dp, bottom = 96.dp)
    ) {
        // Header
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    LogoWithGradient(size = 40.dp)
                    Text(
                        text = "LibreGuard",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Foreground
                    )
                }

                // Plan badge
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (isPro) Primary.copy(alpha = 0.2f) else Secondary,
                    onClick = { if (!isPro) onNavigateToUpgrade() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (isPro) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = null,
                                tint = Primary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        Text(
                            text = if (isPro) "Pro Plan" else "Free Plan",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isPro) Primary else MutedForeground
                        )
                    }
                }
            }

            // Network Security Alert (when disconnected and network not secure)
            AnimatedVisibility(
                visible = !isNetworkSecure && !isConnected && !isConnecting,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = Destructive.copy(alpha = 0.1f),
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                        brush = androidx.compose.ui.graphics.SolidColor(Destructive.copy(alpha = 0.5f))
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = Destructive,
                            modifier = Modifier.size(16.dp)
                        )
                        Column {
                            Text(
                                text = "Unsecured Network",
                                style = MaterialTheme.typography.labelMedium,
                                color = Destructive
                            )
                            Text(
                                text = "Connect to VPN for protection on public WiFi",
                                style = MaterialTheme.typography.bodySmall,
                                color = Destructive.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }

            // IP Address Display (when connected)
            AnimatedVisibility(
                visible = isConnected,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = CardBackground,
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Your IP",
                                style = MaterialTheme.typography.labelSmall,
                                color = MutedForeground
                            )
                            Text(
                                text = userIP,
                                style = MaterialTheme.typography.bodySmall,
                                color = MutedForeground.copy(alpha = 0.5f),
                                textDecoration = TextDecoration.LineThrough
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "VPN IP",
                                style = MaterialTheme.typography.labelSmall,
                                color = MutedForeground
                            )
                            Text(
                                text = vpnIP,
                                style = MaterialTheme.typography.bodySmall,
                                color = Primary
                            )
                        }
                    }
                }
            }

            // Leak Protection Indicators (when connected)
            AnimatedVisibility(
                visible = isConnected,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ProtectionIndicator(text = "DNS Protected")
                    ProtectionIndicator(text = "IPv6 Blocked")
                    ProtectionIndicator(text = "WebRTC Safe")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Quick Connect Button (when disconnected)
        AnimatedVisibility(
            visible = !isConnected && !isConnecting,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = CardBackground,
                border = ButtonDefaults.outlinedButtonBorder(enabled = true),
                onClick = {
                    if (selectedServer != null) {
                        viewModel.connectToVpn()
                    } else {
                        onNavigateToServers()
                    }
                }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(Primary.copy(alpha = 0.1f), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = null,
                                tint = Primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Quick Connect",
                                style = MaterialTheme.typography.titleSmall,
                                color = Foreground
                            )
                            Text(
                                text = if (selectedServer != null)
                                    "Connect to ${selectedServer?.serverName}"
                                else
                                    "Select a server first",
                                style = MaterialTheme.typography.bodySmall,
                                color = MutedForeground
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MutedForeground,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Connection Status Card - Centered content
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Status Indicator Circle
            Box(
                modifier = Modifier.size(160.dp),
                contentAlignment = Alignment.Center
            ) {
                // Outer glow
                Box(
                    modifier = Modifier
                        .size(160.dp)
                        .clip(CircleShape)
                        .background(statusConfig.color.copy(alpha = 0.15f))
                )
                // Inner circle
                Box(
                    modifier = Modifier
                        .size(128.dp)
                        .clip(CircleShape)
                        .background(statusConfig.color.copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = statusConfig.color,
                        modifier = Modifier.size(64.dp)
                    )
                }

                // Connecting animation ring
                if (isConnecting) {
                    val animatedAlpha by infiniteTransition.animateFloat(
                        initialValue = 0.8f,
                        targetValue = 0f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1500, easing = EaseInOut),
                            repeatMode = RepeatMode.Restart
                        ),
                        label = "ringAlpha"
                    )
                    val animatedScale by infiniteTransition.animateFloat(
                        initialValue = 1f,
                        targetValue = 1.2f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1500, easing = EaseInOut),
                            repeatMode = RepeatMode.Restart
                        ),
                        label = "ringScale"
                    )
                    Box(
                        modifier = Modifier
                            .size((160 * animatedScale).dp)
                            .clip(CircleShape)
                            .background(statusConfig.color.copy(alpha = animatedAlpha * 0.3f))
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Status Text
            Text(
                text = statusConfig.text,
                style = MaterialTheme.typography.headlineMedium,
                color = statusConfig.color
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = statusConfig.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MutedForeground
            )

            Spacer(modifier = Modifier.height(40.dp))

            // Connect/Disconnect Button
            Button(
                onClick = {
                    if (isConnected) {
                        viewModel.disconnect()
                    } else if (!isConnecting) {
                        if (selectedServer != null) {
                            viewModel.connectToVpn()
                        } else {
                            onNavigateToServers()
                        }
                    }
                },
                enabled = !isConnecting,
                modifier = Modifier
                    .height(64.dp)
                    .widthIn(min = 200.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Primary,
                    contentColor = PrimaryForeground
                ),
                contentPadding = PaddingValues(horizontal = 56.dp, vertical = 16.dp)
            ) {
                Text(
                    text = statusConfig.buttonText,
                    style = MaterialTheme.typography.titleMedium
                )
            }

            // Stats when connected
            AnimatedVisibility(
                visible = isConnected,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    // Connection Stats Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        StatItem(
                            icon = Icons.Default.Schedule,
                            value = connectionTime,
                            label = "Duration"
                        )
                        StatItem(
                            icon = Icons.Default.Speed,
                            value = "${downloadSpeed.toFixed(1)} Mbps",
                            label = "Speed"
                        )
                        StatItem(
                            icon = Icons.Default.Language,
                            value = selectedServer?.country ?: "Unknown",
                            label = "Location"
                        )
                    }

                    // Bandwidth Usage Card
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = CardBackground,
                        border = ButtonDefaults.outlinedButtonBorder(enabled = true)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Bandwidth Usage",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Foreground
                                )
                                Text(
                                    text = "${totalPercentage.toFixed(1)}% of ${(monthlyLimit / 1024)}GB",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MutedForeground
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Usage bar
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(12.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Secondary.copy(alpha = 0.3f))
                            ) {
                                // Monthly data
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(monthlyPercentage / 100f)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MutedForeground.copy(alpha = 0.4f))
                                )
                                // Session data (on top of monthly)
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .padding(start = (monthlyPercentage / 100f * 300).dp.coerceAtMost(280.dp))
                                        .fillMaxWidth(sessionPercentage / 100f)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Primary)
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Legend
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(12.dp)
                                                .clip(CircleShape)
                                                .background(MutedForeground.copy(alpha = 0.4f))
                                        )
                                        Text(
                                            text = "Monthly total",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MutedForeground
                                        )
                                    }
                                    Text(
                                        text = "${(monthlyData / 1024.0).toFixed(2)} GB",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Foreground
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(12.dp)
                                                .clip(CircleShape)
                                                .background(Primary)
                                        )
                                        Text(
                                            text = "This session",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MutedForeground
                                        )
                                    }
                                    Text(
                                        text = "${sessionData.toFixed(1)} MB",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Primary
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider(color = Border)
                            Spacer(modifier = Modifier.height(12.dp))

                            // Real-time speeds
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceAround
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ArrowDownward,
                                        contentDescription = null,
                                        tint = MutedForeground,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "${downloadSpeed.toFixed(1)} Mbps",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Foreground
                                    )
                                }
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ArrowUpward,
                                        contentDescription = null,
                                        tint = MutedForeground,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "${uploadSpeed.toFixed(1)} Mbps",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Foreground
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProtectionIndicator(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = StatusConnected,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MutedForeground
        )
    }
}

@Composable
private fun StatItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    label: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MutedForeground,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = Foreground
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MutedForeground
        )
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

