package net.libreguard.vpn.ui.screens

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import net.libreguard.vpn.ui.components.LogoWithGradient
import net.libreguard.vpn.ui.theme.*
import net.libreguard.vpn.viewmodel.VpnViewModel
import java.util.Locale

/**
 * Dashboard Screen - Main connection screen (Non-scrollable, compact layout)
 * Based on design from Dashboard.tsx
 */
@Composable
fun DashboardScreen(
    authToken: String,
    vpnViewModel: VpnViewModel? = null,
    onNavigateToServers: () -> Unit,
    onNavigateToUpgrade: () -> Unit
) {
    val viewModel: VpnViewModel = vpnViewModel ?: viewModel()
    val context = LocalContext.current

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
    val userIP = remember { "203.0.113.45" }
    var vpnIP by remember { mutableStateOf("") }

    // Simulated timer and data when connected
    LaunchedEffect(isConnected) {
        if (isConnected) {
            vpnIP = "198.51.100.78"
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

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.connectToVpn()
        }
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
    val monthlyPercentage = (monthlyData.toFloat() / monthlyLimit * 100)
    val sessionPercentage = (sessionData.toFloat() / monthlyLimit * 100)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .padding(horizontal = 16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                shape = RoundedCornerShape(16.dp),
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

        // IP Display when connected
        if (isConnected) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
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
                        Text("Your IP", style = MaterialTheme.typography.labelSmall, color = MutedForeground)
                        Text(userIP, style = MaterialTheme.typography.bodySmall, color = MutedForeground, textDecoration = TextDecoration.LineThrough)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("VPN IP", style = MaterialTheme.typography.labelSmall, color = MutedForeground)
                        Text(vpnIP, style = MaterialTheme.typography.bodySmall, color = Primary)
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            // Protection indicators
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ProtectionIndicator("DNS Protected")
                ProtectionIndicator("IPv6 Blocked")
                ProtectionIndicator("WebRTC Safe")
            }
        }

        // Quick Connect / Server Selection Box (when disconnected)
        if (!isConnected && !isConnecting) {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToServers() },
                shape = RoundedCornerShape(12.dp),
                color = CardBackground,
                border = ButtonDefaults.outlinedButtonBorder(enabled = true)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(Primary.copy(alpha = 0.1f), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (selectedServer != null) {
                                Text(
                                    text = getFlagEmoji(selectedServer!!.country),
                                    style = MaterialTheme.typography.titleLarge
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Bolt,
                                    contentDescription = null,
                                    tint = Primary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Column {
                            Text(
                                text = if (selectedServer != null) selectedServer!!.serverName else "Quick Connect",
                                style = MaterialTheme.typography.titleSmall,
                                color = Foreground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (selectedServer != null) selectedServer!!.country else "Tap to select a server",
                                style = MaterialTheme.typography.bodySmall,
                                color = MutedForeground
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MutedForeground
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(0.3f))

        // Connection Status Shield
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .scale(pulseScale)
                    .size(130.dp)
                    .clip(CircleShape)
                    .background(statusConfig.color.copy(alpha = 0.08f))
                    .clickable(enabled = !isConnecting && selectedServer != null) {
                        viewModel.requestVpnPermission(context) { vpnIntent ->
                            if (vpnIntent != null) {
                                vpnPermissionLauncher.launch(vpnIntent)
                            } else {
                                if (isConnected) viewModel.disconnect() else viewModel.connectToVpn()
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(CircleShape)
                        .background(statusConfig.color.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = statusConfig.text,
                        modifier = Modifier.size(50.dp),
                        tint = statusConfig.color
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = statusConfig.text,
                style = MaterialTheme.typography.headlineSmall,
                color = statusConfig.color
            )
            Text(
                text = statusConfig.description,
                style = MaterialTheme.typography.bodySmall,
                color = MutedForeground
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Connect Button
            Button(
                onClick = {
                    viewModel.requestVpnPermission(context) { vpnIntent ->
                        if (vpnIntent != null) {
                            vpnPermissionLauncher.launch(vpnIntent)
                        } else {
                            if (isConnected) viewModel.disconnect() else viewModel.connectToVpn()
                        }
                    }
                },
                modifier = Modifier
                    .width(180.dp)
                    .height(48.dp),
                enabled = !isConnecting && selectedServer != null,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Primary,
                    contentColor = PrimaryForeground
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
            ) {
                if (isConnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = PrimaryForeground,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(text = statusConfig.buttonText, style = MaterialTheme.typography.labelLarge)
            }

            if (selectedServer == null && !isConnected) {
                TextButton(onClick = onNavigateToServers) {
                    Text("Select a server first", color = Primary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Spacer(modifier = Modifier.weight(0.3f))

        // Stats (when connected)
        if (isConnected) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(icon = Icons.Default.Schedule, value = connectionTime, label = "Duration")
                StatItem(icon = Icons.Default.Speed, value = String.format(Locale.US, "%.1f Mbps", downloadSpeed), label = "Speed")
                StatItem(icon = Icons.Default.Public, value = selectedServer?.country ?: "N/A", label = "Location")
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Bandwidth Usage Card with multi-layer bar
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = CardBackground,
                border = ButtonDefaults.outlinedButtonBorder(enabled = true)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Bandwidth Usage", style = MaterialTheme.typography.titleSmall, color = Foreground)
                        Text(
                            text = String.format(Locale.US, "%.1f%% of %.0fGB", (monthlyData + sessionData) / monthlyLimit * 100, monthlyLimit / 1024.0),
                            style = MaterialTheme.typography.labelSmall,
                            color = MutedForeground
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Multi-layer progress bar
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(Secondary.copy(alpha = 0.3f))
                    ) {
                        // Monthly data (gray)
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(monthlyPercentage / 100f)
                                .background(MutedForeground.copy(alpha = 0.4f))
                        )
                        // Session data (blue) - positioned after monthly
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .padding(start = ((monthlyPercentage / 100f) * 1000).dp.coerceAtMost(1000.dp))
                                .fillMaxWidth((sessionPercentage / 100f).coerceIn(0f, 1f - monthlyPercentage / 100f))
                                .background(Primary)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Legend
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(modifier = Modifier.size(10.dp).background(MutedForeground.copy(alpha = 0.4f), RoundedCornerShape(5.dp)))
                            Text("Monthly total", style = MaterialTheme.typography.labelSmall, color = MutedForeground)
                            Text(String.format(Locale.US, "%.2f GB", monthlyData / 1024.0), style = MaterialTheme.typography.labelSmall, color = Foreground)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(modifier = Modifier.size(10.dp).background(Primary, RoundedCornerShape(5.dp)))
                            Text("Session", style = MaterialTheme.typography.labelSmall, color = MutedForeground)
                            Text(String.format(Locale.US, "%.1f MB", sessionData), style = MaterialTheme.typography.labelSmall, color = Primary)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Speed indicators
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Default.ArrowDownward, null, tint = MutedForeground, modifier = Modifier.size(14.dp))
                            Text(String.format(Locale.US, "%.1f Mbps", downloadSpeed), style = MaterialTheme.typography.bodySmall, color = Foreground)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Default.ArrowUpward, null, tint = MutedForeground, modifier = Modifier.size(14.dp))
                            Text(String.format(Locale.US, "%.1f Mbps", uploadSpeed), style = MaterialTheme.typography.bodySmall, color = Foreground)
                        }
                    }
                }
            }
        }

        // Current IP card (when disconnected)
        if (!isConnected && !isConnecting) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = CardBackground,
                border = ButtonDefaults.outlinedButtonBorder(enabled = true)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Your Current IP", style = MaterialTheme.typography.labelSmall, color = MutedForeground)
                    Text(userIP, style = MaterialTheme.typography.titleMedium, color = Foreground)
                    Text("Your IP is visible to websites", style = MaterialTheme.typography.labelSmall, color = MutedForeground)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

// Helper composables
@Composable
private fun ProtectionIndicator(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = StatusConnected,
            modifier = Modifier.size(12.dp)
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
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = Foreground,
            maxLines = 1
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MutedForeground
        )
    }
}

// Status configuration
private enum class ConnectionStatus {
    DISCONNECTED, CONNECTING, CONNECTED
}

private data class ConnectionStatusConfig(
    val color: androidx.compose.ui.graphics.Color,
    val text: String,
    val description: String,
    val buttonText: String
)

private fun getConnectionStatusConfig(status: ConnectionStatus): ConnectionStatusConfig {
    return when (status) {
        ConnectionStatus.CONNECTED -> ConnectionStatusConfig(
            color = StatusConnected,
            text = "Protected",
            description = "Your connection is secure",
            buttonText = "Disconnect"
        )
        ConnectionStatus.CONNECTING -> ConnectionStatusConfig(
            color = StatusConnecting,
            text = "Connecting",
            description = "Establishing secure connection...",
            buttonText = "Connecting..."
        )
        ConnectionStatus.DISCONNECTED -> ConnectionStatusConfig(
            color = StatusDisconnected,
            text = "Not Protected",
            description = "Your connection is not secure",
            buttonText = "Connect"
        )
    }
}

private fun getFlagEmoji(country: String): String {
    return when (country) {
        "USA", "United States" -> "🇺🇸"
        "UK", "United Kingdom" -> "🇬🇧"
        "Japan" -> "🇯🇵"
        "Germany" -> "🇩🇪"
        "Netherlands" -> "🇳🇱"
        "Canada" -> "🇨🇦"
        "France" -> "🇫🇷"
        "Australia" -> "🇦🇺"
        "Singapore" -> "🇸🇬"
        else -> "🏳️"
    }
}

