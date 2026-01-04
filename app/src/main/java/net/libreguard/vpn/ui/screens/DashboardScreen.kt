package net.libreguard.vpn.ui.screens

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
import androidx.compose.ui.text.style.TextOverflow
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
    val isQuickConnectMode by viewModel.isQuickConnectMode.collectAsState()

    // Real data usage from ViewModel
    val dataUsageInfo by viewModel.dataUsageInfo.collectAsState()

    // Connection duration from ViewModel (persists across navigation)
    val connectionTime by viewModel.connectionDuration.collectAsState()

    // VPN IP from ViewModel (persists across navigation)
    val vpnIP by viewModel.vpnIP.collectAsState()

    // Real-time speeds from DataUsageManager
    val downloadSpeed = remember(dataUsageInfo) { dataUsageInfo.downloadSpeedMbps }
    val uploadSpeed = remember(dataUsageInfo) { dataUsageInfo.uploadSpeedMbps }

    // Real session data from ViewModel (in MB)
    val sessionData = remember(dataUsageInfo) {
        dataUsageInfo.sessionBytesUsed / (1024.0 * 1024.0)
    }

    // Real monthly data from ViewModel (in MB) - now from server
    val monthlyData = remember(dataUsageInfo) {
        dataUsageInfo.totalBytesUsed / (1024.0 * 1024.0)
    }

    // Real limit from ViewModel (in MB) - now from server
    val monthlyLimit = remember(dataUsageInfo) {
        if (dataUsageInfo.isUnlimited) Double.MAX_VALUE
        else dataUsageInfo.limitBytes / (1024.0 * 1024.0)
    }

    // Check if user has unlimited data (Pro user)
    val isUnlimited = remember(dataUsageInfo) { dataUsageInfo.isUnlimited }

    // Check if user is over limit
    val isOverLimit = remember(dataUsageInfo) { dataUsageInfo.isOverLimit }

    // IP addresses
    var userIP by remember { mutableStateOf("Loading...") }

    // Fetch user's real IP address from ipify API once on load
    LaunchedEffect(Unit) {
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
                        userIP = ip
                    }
                }
                connection.disconnect()
            }
        } catch (e: Exception) {
            android.util.Log.e("DashboardScreen", "Failed to fetch user IP: ${e.message}")
            userIP = "Unknown"
        }
    }

    LaunchedEffect(authToken) {
        viewModel.setAuthToken(authToken)
        viewModel.loadRemoteServers()

        // CRITICAL FIX: Delay quota sync to stagger API requests
        // This prevents simultaneous 401s that cascade into multiple logouts
        kotlinx.coroutines.delay(2000L)

        // Sync server quota after delay
        viewModel.syncServerQuota()
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
            .padding(top = 0.dp, bottom = 0.dp)
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

        // Unified Quick Connect / Manual Server Selection Button (when disconnected)
        if (!isConnected && !isConnecting) {
            Spacer(modifier = Modifier.height(10.dp))

            // Single unified button that shows Quick Connect or manually selected server
            // Use fillMaxWidth for manual mode to fit content, wrapContentWidth for Quick Connect
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = if (isQuickConnectMode) {
                        Modifier.wrapContentWidth()
                    } else {
                        Modifier.fillMaxWidth()
                    },
                    shape = RoundedCornerShape(10.dp),
                    color = if (isQuickConnectMode) Primary.copy(alpha = 0.1f) else CardBackground,
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                        brush = androidx.compose.ui.graphics.SolidColor(
                            if (isQuickConnectMode) Primary else MutedForeground
                        )
                    ),
                    onClick = {
                        if (isQuickConnectMode) {
                            // Quick Connect mode: auto-select and connect
                            viewModel.quickConnect()
                        } else {
                            // Manual mode: navigate to server list to change selection
                            onNavigateToServers()
                        }
                    }
                ) {
                    Box(
                        modifier = if (isQuickConnectMode) {
                            Modifier.wrapContentWidth()
                        } else {
                            Modifier.fillMaxWidth()
                        }
                    ) {
                        Row(
                            modifier = Modifier
                                .then(
                                    if (isQuickConnectMode) {
                                        Modifier.wrapContentWidth()
                                    } else {
                                        Modifier.fillMaxWidth()
                                    }
                                )
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isQuickConnectMode) {
                                // Quick Connect UI
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .background(Primary.copy(alpha = 0.2f), RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Bolt,
                                        null,
                                        tint = Primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        "Quick Connect",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = Primary
                                    )
                                    Text(
                                        text = "Auto-select best server",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Primary.copy(alpha = 0.7f)
                                    )
                                }
                            } else {
                                // Manual Server Selection UI - Centered
                                if (selectedServer != null) {
                                    Text(
                                        text = getFlagEmoji(selectedServer?.country ?: ""),
                                        style = MaterialTheme.typography.titleLarge
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = selectedServer?.serverName ?: "",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = Foreground,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = selectedServer?.country ?: "",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MutedForeground
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(36.dp)) // Space for X button
                                } else {
                                    Icon(
                                        Icons.Default.List,
                                        null,
                                        tint = MutedForeground,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "Select Server",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = Foreground
                                    )
                                }
                            }
                        }

                        // Clear selection button (X icon) - only show in manual mode with server selected
                        // Positioned in center-right
                        if (!isQuickConnectMode && selectedServer != null) {
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .padding(end = 8.dp)
                                    .size(28.dp),
                                shape = CircleShape,
                                color = Secondary,
                                onClick = {
                                    viewModel.clearServerSelection()
                                }
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Clear selection and return to Quick Connect",
                                        tint = MutedForeground,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Main center section (matches design: "flex-1 flex flex-col items-center justify-center")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Status Indicator Circle
            val shieldSize = when {
                isConnected -> 140.dp
                isConnecting -> 140.dp
                else -> 150.dp
            }
            val innerShieldSize = 112.dp
            val iconSize = 64.dp

            Box(
                modifier = Modifier
                    .size(shieldSize),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(shieldSize)
                        .clip(CircleShape)
                        .background(statusConfig.color.copy(alpha = 0.15f))
                )
                Box(
                    modifier = Modifier
                        .size(innerShieldSize)
                        .clip(CircleShape)
                        .background(statusConfig.color.copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Shield, null, tint = statusConfig.color, modifier = Modifier.size(iconSize))
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
                        modifier = Modifier
                            .size((shieldSize.value * animatedScale).dp)
                            .clip(CircleShape)
                            .background(statusConfig.color.copy(alpha = animatedAlpha * 0.3f))
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Status text
            Text(
                text = statusConfig.text,
                style = MaterialTheme.typography.headlineMedium,
                color = statusConfig.color
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = statusConfig.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MutedForeground
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Connect/Disconnect button (design: big rounded)
            Button(
                onClick = {
                    if (isConnected) {
                        viewModel.disconnect()
                    } else if (!isConnecting) {
                        if (isQuickConnectMode) {
                            // Quick Connect mode: auto-select and connect
                            viewModel.quickConnect()
                        } else {
                            // Manual mode: connect to selected server or navigate to select one
                            if (selectedServer != null) {
                                viewModel.connectToVpn()
                            } else {
                                onNavigateToServers()
                            }
                        }
                    }
                },
                enabled = !isConnecting,
                modifier = Modifier
                    .height(if (isConnected) 48.dp else 56.dp)
                    .padding(horizontal = 18.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = PrimaryForeground)
            ) {
                Text(text = statusConfig.buttonText, style = MaterialTheme.typography.titleMedium)
            }

            // Connected-only stats block (kept in the weighted center section so it doesn't leave bottom whitespace)
            if (isConnected) {
                Spacer(modifier = Modifier.height(12.dp))

                // Connection stats row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatItemCompact(icon = Icons.Default.Schedule, value = connectionTime, label = "Duration")
                    StatItemCompact(icon = Icons.Default.Speed, value = "${downloadSpeed.toFixed(1)} Mbps", label = "Speed")
                    StatItemCompact(icon = Icons.Default.Language, value = selectedServer?.country ?: "-", label = "Location")
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Bandwidth card
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = CardBackground,
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Bandwidth Usage", style = MaterialTheme.typography.labelLarge, color = Foreground)
                            Text(
                                if (isUnlimited) "Unlimited"
                                else "${totalPercentage.toFixed(1)}% of ${dataUsageInfo.formattedLimit}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isOverLimit) Destructive else MutedForeground
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Multi-layer bar (monthly = gray, session = primary) - hide for unlimited
                        if (!isUnlimited) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Secondary.copy(alpha = 0.3f))
                            ) {
                                // Monthly
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth((monthlyPercentage / 100f).coerceIn(0f, 1f))
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (isOverLimit) Destructive.copy(alpha = 0.6f) else MutedForeground.copy(alpha = 0.4f))
                            )
                            // Session
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(((sessionData / monthlyLimit)).toFloat().coerceIn(0f, 1f))
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Primary)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        } // End of !isUnlimited block

                        // Legend - use server-formatted values
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(if (isOverLimit) Destructive.copy(alpha = 0.6f) else MutedForeground.copy(alpha = 0.4f)))
                                Text("Monthly total", style = MaterialTheme.typography.labelSmall, color = MutedForeground)
                            }
                            Text(
                                text = dataUsageInfo.formattedTotal,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isOverLimit) Destructive else Foreground
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Primary))
                                Text("This session", style = MaterialTheme.typography.labelSmall, color = MutedForeground)
                            }
                            Text("${sessionData.toFixed(1)} MB", style = MaterialTheme.typography.labelSmall, color = Primary)
                        }

                        // Show remaining data for free users
                        if (!isUnlimited) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Remaining", style = MaterialTheme.typography.labelSmall, color = MutedForeground)
                                Text(
                                    text = if (isOverLimit) "0 B" else dataUsageInfo.formattedRemaining,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isOverLimit) Destructive else Foreground
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider(color = Border)
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.ArrowDownward, null, tint = MutedForeground, modifier = Modifier.size(14.dp))
                                Text("${downloadSpeed.toFixed(1)} Mbps", style = MaterialTheme.typography.bodySmall, color = Foreground)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.ArrowUpward, null, tint = MutedForeground, modifier = Modifier.size(14.dp))
                                Text("${uploadSpeed.toFixed(1)} Mbps", style = MaterialTheme.typography.bodySmall, color = Foreground)
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

/**
 * Get flag emoji from country name
 */
private fun getFlagEmoji(country: String): String {
    return when (country.lowercase()) {
        "usa", "united states" -> "🇺🇸"
        "uk", "united kingdom" -> "🇬🇧"
        "japan" -> "🇯🇵"
        "germany" -> "🇩🇪"
        "netherlands" -> "🇳🇱"
        "canada" -> "🇨🇦"
        "france" -> "🇫🇷"
        "australia" -> "🇦🇺"
        "singapore" -> "🇸🇬"
        "switzerland" -> "🇨🇭"
        "sweden" -> "🇸🇪"
        "norway" -> "🇳🇴"
        "italy" -> "🇮🇹"
        "spain" -> "🇪🇸"
        "brazil" -> "🇧🇷"
        "india" -> "🇮🇳"
        "south korea", "korea" -> "🇰🇷"
        "hong kong" -> "🇭🇰"
        "ireland" -> "🇮🇪"
        "poland" -> "🇵🇱"
        else -> "🏳️"
    }
}
