package net.libreguard.vpn.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import net.libreguard.vpn.service.data.DataUsageInfo
import net.libreguard.vpn.ui.components.LogoWithGradient
import net.libreguard.vpn.ui.components.VpnConnectionHero
import net.libreguard.vpn.ui.components.VpnConnectionStatus
import net.libreguard.vpn.ui.components.getStatusConfig
import net.libreguard.vpn.ui.theme.*
import net.libreguard.vpn.util.getFlagEmoji
import net.libreguard.vpn.viewmodel.VpnProtocol
import net.libreguard.vpn.viewmodel.VpnViewModel
import java.util.Locale

/**
 * Dashboard Screen - Main connection screen
 * Dashboard connection screen with adaptive spacing and a bounded scroll fallback.
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
    val selectedProtocol by viewModel.selectedProtocol.collectAsState()
    val activeProtocolLabel = when (selectedProtocol) {
        VpnProtocol.IKEV2_IPSEC -> "IKEv2/IPSec"
        else -> selectedProtocol.displayName
    }
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

    // User's real IP from ViewModel (captured before VPN connection, persists across navigation)
    val userIP by viewModel.userIP.collectAsState()

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
        isConnecting -> VpnConnectionStatus.CONNECTING
        isConnected -> VpnConnectionStatus.CONNECTED
        else -> VpnConnectionStatus.DISCONNECTED
    }

    val statusConfig = getStatusConfig(connectionStatus)
    val onConnectionToggle = {
        if (isConnected || isConnecting) {
            viewModel.disconnect()
        } else if (isQuickConnectMode) {
            viewModel.quickConnect()
        } else if (selectedServer != null) {
            viewModel.connectToVpn()
        } else {
            onNavigateToServers()
        }
    }

    // Calculate usage percentages - don't add sessionData as totalBytesUsed (from server) already includes it
    val totalDataUsed = monthlyData
    val monthlyPercentage = if (monthlyLimit > 0) (monthlyData / monthlyLimit * 100).toFloat() else 0f
    val totalPercentage = if (monthlyLimit > 0) (totalDataUsed / monthlyLimit * 100).toFloat() else 0f

    Column(
        modifier = Modifier
            .fillMaxSize()
            .animateContentSize(animationSpec = tween(durationMillis = 280))
            .background(Background)
            .padding(horizontal = LibreGuardDimens.screenHorizontalPadding)
            .padding(top = LibreGuardDimens.screenTopPadding, bottom = 0.dp)
    ) {
        // Header - Compact
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(LibreGuardDimens.headerContentSpacing)
            ) {
                LogoWithGradient(size = 40.dp)
                Text(
                    text = "LibreGuard",
                    style = MaterialTheme.typography.headlineMedium,
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
        AnimatedVisibility(
            visible = isConnected,
            enter = fadeIn(tween(220)) + expandVertically(expandFrom = Alignment.Top),
            exit = fadeOut(tween(180)) + shrinkVertically(shrinkTowards = Alignment.Top)
        ) {
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
                ProtectionIndicator(text = "WebRTC")
                ProtectionIndicator(text = activeProtocolLabel)
                ProtectionIndicator(text = "DNS")
            }
        }

        // Unified Quick Connect / Manual Server Selection Button (when disconnected)
        // Only show if: in Quick Connect mode OR (in manual mode AND have a server selected)
        AnimatedVisibility(
            visible = !isConnected && !isConnecting && (isQuickConnectMode || selectedServer != null),
            enter = fadeIn(tween(220)) + expandVertically(expandFrom = Alignment.Top),
            exit = fadeOut(tween(180)) + shrinkVertically(shrinkTowards = Alignment.Top)
        ) {
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
                        } else if (selectedServer != null) {
                            // Manual mode with server selected: connect to selected server
                            viewModel.connectToVpn()
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
                                // Manual Server Selection UI - only show if server is selected
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

        AdaptiveConnectionViewport(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 12.dp),
            status = connectionStatus,
            statusButtonText = statusConfig.buttonText,
            onConnectionToggle = onConnectionToggle,
            isConnected = isConnected,
            isConnecting = isConnecting,
            isQuickConnectMode = isQuickConnectMode,
            hasSelectedServer = selectedServer != null,
            connectionTime = connectionTime,
            downloadSpeed = downloadSpeed,
            uploadSpeed = uploadSpeed,
            location = selectedServer?.country ?: "-",
            dataUsageInfo = dataUsageInfo,
            isUnlimited = isUnlimited,
            isOverLimit = isOverLimit,
            sessionData = sessionData,
            monthlyLimit = monthlyLimit,
            monthlyPercentage = monthlyPercentage,
            totalPercentage = totalPercentage
        )

        // Data Usage Card - Fixed at Bottom (Free Plan Only, when disconnected)
        // Matches design-reference: Dashboard.tsx Data Usage Card at bottom
        AnimatedVisibility(
            visible = !isPro && !isConnected && !isConnecting,
            enter = fadeIn(tween(220)) + expandVertically(expandFrom = Alignment.Bottom),
            exit = fadeOut(tween(180)) + shrinkVertically(shrinkTowards = Alignment.Bottom)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Cache values to prevent flickering on tab switches
            // rememberUpdatedState keeps the last value until new data arrives
            val cachedMonthlyPercentage = rememberUpdatedState(monthlyPercentage)
            val cachedDataUsageTotal = rememberUpdatedState(dataUsageInfo.formattedTotal)
            val cachedDataUsageRemaining = rememberUpdatedState(dataUsageInfo.formattedRemaining)

            // Get color based on cached percentage
            val usageColor = when {
                cachedMonthlyPercentage.value >= 100f -> Destructive
                cachedMonthlyPercentage.value > 90f -> Destructive
                cachedMonthlyPercentage.value > 70f -> StatusConnecting
                else -> Primary
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 0.dp)
                    .padding(bottom = 16.dp),  // ~2mm separation from nav bar
                shape = RoundedCornerShape(12.dp),
                color = CardBackground,
                border = ButtonDefaults.outlinedButtonBorder(enabled = true),
                shadowElevation = 4.dp
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    // Header row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Monthly Data Usage",
                            style = MaterialTheme.typography.labelSmall,
                            color = Foreground
                        )
                        Text(
                            text = "${cachedDataUsageTotal.value} / ${dataUsageInfo.formattedLimit}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MutedForeground
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Progress Bar
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Secondary)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth((cachedMonthlyPercentage.value / 100f).coerceIn(0f, 1f))
                                .clip(RoundedCornerShape(4.dp))
                                .background(usageColor)
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Percentage and Remaining
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${cachedMonthlyPercentage.value.toFixed(1)}% used",
                            style = MaterialTheme.typography.labelSmall,
                            color = usageColor
                        )
                        Text(
                            text = "${cachedDataUsageRemaining.value} left",
                            style = MaterialTheme.typography.labelSmall,
                            color = MutedForeground
                        )
                    }

                    // Warning if close to limit (over 80%)
                    if (cachedMonthlyPercentage.value >= 80f) {
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider(color = Border)
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (cachedMonthlyPercentage.value >= 100f) Destructive else StatusConnecting,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = when {
                                    cachedMonthlyPercentage.value >= 100f -> "Out of data! • Upgrade to Pro for unlimited"
                                    cachedMonthlyPercentage.value > 90f -> "Almost out of data! • Upgrade to Pro for unlimited"
                                    else -> "Running low • Upgrade to Pro for unlimited"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (cachedMonthlyPercentage.value >= 100f) Destructive else StatusConnecting
                            )
                        }
                    }

                    // Reset date display
                    val formattedResetDate = formatResetDate(dataUsageInfo.resetDate)
                    if (formattedResetDate != null) {
                        Spacer(modifier = Modifier.height(if (cachedMonthlyPercentage.value >= 80f) 8.dp else 6.dp))
                        if (cachedMonthlyPercentage.value < 80f) {
                            HorizontalDivider(color = Border)
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Next reset",
                                style = MaterialTheme.typography.labelSmall,
                                color = MutedForeground
                            )
                            Text(
                                text = formattedResetDate,
                                style = MaterialTheme.typography.labelSmall,
                                color = Foreground
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AdaptiveConnectionViewport(
    modifier: Modifier,
    status: VpnConnectionStatus,
    statusButtonText: String,
    onConnectionToggle: () -> Unit,
    isConnected: Boolean,
    isConnecting: Boolean,
    isQuickConnectMode: Boolean,
    hasSelectedServer: Boolean,
    connectionTime: String,
    downloadSpeed: Double,
    uploadSpeed: Double,
    location: String,
    dataUsageInfo: DataUsageInfo,
    isUnlimited: Boolean,
    isOverLimit: Boolean,
    sessionData: Double,
    monthlyLimit: Double,
    monthlyPercentage: Float,
    totalPercentage: Float
) {
    val scrollState = rememberScrollState()

    BoxWithConstraints(
        modifier = modifier.testTag("dashboard_connection_viewport")
    ) {
        val density = LocalDensity.current
        val viewportHeightPx = with(density) {
            if (maxHeight == Dp.Infinity) 0 else maxHeight.roundToPx()
        }

        Layout(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .animateContentSize(animationSpec = tween(durationMillis = 280))
                .verticalScroll(scrollState)
                .testTag("dashboard_connection_scroll"),
            content = {
                VpnConnectionHero(
                    status = status,
                    onClick = onConnectionToggle,
                    showProgressBar = true
                )

                Button(
                    onClick = onConnectionToggle,
                    enabled = isConnected || isConnecting || isQuickConnectMode || hasSelectedServer,
                    modifier = Modifier
                        .height(if (isConnected) 48.dp else 56.dp)
                        .padding(horizontal = 18.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Primary,
                        contentColor = PrimaryForeground
                    )
                ) {
                    Text(text = statusButtonText, style = MaterialTheme.typography.titleMedium)
                }

                AnimatedVisibility(
                    visible = isConnected,
                    enter = fadeIn(tween(220)) + expandVertically(expandFrom = Alignment.Top),
                    exit = fadeOut(tween(180)) + shrinkVertically(shrinkTowards = Alignment.Top)
                ) {
                    ConnectionStatsRow(
                        connectionTime = connectionTime,
                        downloadSpeed = downloadSpeed,
                        location = location
                    )
                }

                AnimatedVisibility(
                    visible = isConnected,
                    enter = fadeIn(tween(240)) + expandVertically(expandFrom = Alignment.Top),
                    exit = fadeOut(tween(180)) + shrinkVertically(shrinkTowards = Alignment.Top)
                ) {
                    BandwidthUsageCard(
                        dataUsageInfo = dataUsageInfo,
                        isUnlimited = isUnlimited,
                        isOverLimit = isOverLimit,
                        sessionData = sessionData,
                        monthlyLimit = monthlyLimit,
                        monthlyPercentage = monthlyPercentage,
                        totalPercentage = totalPercentage,
                        downloadSpeed = downloadSpeed,
                        uploadSpeed = uploadSpeed
                    )
                }
            }
        ) { measurables, constraints ->
            val layoutWidth = constraints.maxWidth
            val childConstraints = constraints.copy(
                minWidth = 0,
                maxWidth = layoutWidth,
                minHeight = 0,
                maxHeight = Constraints.Infinity
            )
            val placeables = measurables
                .map { it.measure(childConstraints) }
                .filter { it.height > 0 }

            val minimumGapPx = with(density) { 8.dp.roundToPx() }
            val spaceCount = placeables.size + 1
            val contentHeight = placeables.sumOf { it.height }
            val minimumLayoutHeight = contentHeight + minimumGapPx * spaceCount
            val layoutHeight = maxOf(viewportHeightPx, minimumLayoutHeight)
            val extraSpace = (layoutHeight - minimumLayoutHeight).coerceAtLeast(0)
            val adaptiveGapPx = minimumGapPx + if (spaceCount > 0) {
                extraSpace / spaceCount
            } else {
                0
            }

            layout(width = layoutWidth, height = layoutHeight) {
                var y = adaptiveGapPx
                placeables.forEach { placeable ->
                    val x = ((layoutWidth - placeable.width) / 2).coerceAtLeast(0)
                    placeable.placeRelative(x, y)
                    y += placeable.height + adaptiveGapPx
                }
            }
        }
    }
}

@Composable
private fun ConnectionStatsRow(
    connectionTime: String,
    downloadSpeed: Double,
    location: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .testTag("connection_stats"),
    ) {
        StatItemCompact(
            modifier = Modifier.weight(1f),
            icon = Icons.Default.Schedule,
            value = connectionTime,
            label = "Duration"
        )
        StatItemCompact(
            modifier = Modifier.weight(1f),
            icon = Icons.Default.Speed,
            value = "${downloadSpeed.toFixed(1)} Mbps",
            label = "Speed"
        )
        StatItemCompact(
            modifier = Modifier.weight(1f),
            icon = Icons.Default.Language,
            value = location,
            label = "Location"
        )
    }
}

@Composable
private fun BandwidthUsageCard(
    dataUsageInfo: DataUsageInfo,
    isUnlimited: Boolean,
    isOverLimit: Boolean,
    sessionData: Double,
    monthlyLimit: Double,
    monthlyPercentage: Float,
    totalPercentage: Float,
    downloadSpeed: Double,
    uploadSpeed: Double
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .testTag("bandwidth_usage_card"),
        shape = RoundedCornerShape(12.dp),
        color = CardBackground,
        border = ButtonDefaults.outlinedButtonBorder(enabled = true)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Bandwidth Usage",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = Foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (isUnlimited) "Unlimited"
                    else "${totalPercentage.toFixed(1)}% of ${dataUsageInfo.formattedLimit}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isOverLimit) Destructive else MutedForeground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (!isUnlimited) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Secondary.copy(alpha = 0.3f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth((monthlyPercentage / 100f).coerceIn(0f, 1f))
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (isOverLimit) Destructive.copy(alpha = 0.6f)
                                else MutedForeground.copy(alpha = 0.4f)
                            )
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(
                                if (monthlyLimit > 0) {
                                    (sessionData / monthlyLimit).toFloat().coerceIn(0f, 1f)
                                } else {
                                    0f
                                }
                            )
                            .clip(RoundedCornerShape(4.dp))
                            .background(Primary)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (isOverLimit) Destructive.copy(alpha = 0.6f)
                                else MutedForeground.copy(alpha = 0.4f)
                            )
                    )
                    Text(
                        text = "Monthly total",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedForeground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = dataUsageInfo.formattedTotal,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isOverLimit) Destructive else Foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Primary)
                    )
                    Text(
                        text = "This session",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedForeground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${sessionData.toFixed(1)} MB",
                    style = MaterialTheme.typography.labelSmall,
                    color = Primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (!isUnlimited) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("bandwidth_remaining"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Remaining",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedForeground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isOverLimit) "0 B" else dataUsageInfo.formattedRemaining,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isOverLimit) Destructive else Foreground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = Border)
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("bandwidth_realtime_speeds"),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                Row(
                    modifier = Modifier.testTag("bandwidth_download_speed"),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.ArrowDownward,
                        null,
                        tint = MutedForeground,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        "${downloadSpeed.toFixed(1)} Mbps",
                        style = MaterialTheme.typography.bodySmall,
                        color = Foreground
                    )
                }
                Row(
                    modifier = Modifier.testTag("bandwidth_upload_speed"),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.ArrowUpward,
                        null,
                        tint = MutedForeground,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        "${uploadSpeed.toFixed(1)} Mbps",
                        style = MaterialTheme.typography.bodySmall,
                        color = Foreground
                    )
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
    label: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, tint = MutedForeground, modifier = Modifier.size(16.dp))
        Text(
            text = value,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.labelMedium,
            color = Foreground,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = label,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.labelSmall,
            color = MutedForeground,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun Double.toFixed(decimals: Int): String {
    return String.format(Locale.US, "%.${decimals}f", this)
}

private fun Float.toFixed(decimals: Int): String {
    return String.format(Locale.US, "%.${decimals}f", this)
}



/**
 * Format ISO 8601 date string to "MMM DD, YYYY" format
 * Example: "2026-02-01T00:00:00Z" -> "Feb 01, 2026"
 */
private fun formatResetDate(isoDate: String?): String? {
    if (isoDate.isNullOrBlank()) return null
    return try {
        // Parse ISO 8601 format: "2026-02-01T00:00:00Z"
        val parts = isoDate.split("T")[0].split("-")
        if (parts.size == 3) {
            val year = parts[0]
            val month = parts[1].toIntOrNull() ?: return null
            val day = parts[2]

            val months = arrayOf(
                "", "Jan", "Feb", "Mar", "Apr", "May", "Jun",
                "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
            )

            if (month in 1..12) {
                "${months[month]} ${day.padStart(2, '0')}, $year"
            } else null
        } else null
    } catch (e: Exception) {
        null
    }
}
