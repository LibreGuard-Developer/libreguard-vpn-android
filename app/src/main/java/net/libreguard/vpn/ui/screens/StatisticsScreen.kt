package net.libreguard.vpn.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import net.libreguard.vpn.data.DailyUsage
import net.libreguard.vpn.data.formatDataAmount
import net.libreguard.vpn.ui.components.ScreenHeader
import net.libreguard.vpn.ui.theme.*
import net.libreguard.vpn.viewmodel.VpnViewModel
import kotlinx.coroutines.delay

/**
 * Statistics Screen - Usage data and analytics
 * Based on design from Statistics.tsx
 * Features: Ripple effects on cards, real connection history, per-user data isolation
 * Now uses ViewModel's user-scoped ConnectionHistoryManager for proper per-user stats
 */
@Composable
fun StatisticsScreen(
    viewModel: VpnViewModel,
    onNavigateBack: () -> Unit = {}
) {
    // CRITICAL: Use ViewModel's ConnectionHistoryManager for per-user data isolation
    val historyManager = remember { viewModel.getHistoryManager() }

    var timeRange by remember { mutableStateOf("week") }

    // Observe statistics refresh trigger for real-time updates
    val statisticsRefreshTrigger by viewModel.statisticsRefreshTrigger.collectAsState()

    // Observe real-time session data from ViewModel
    val dataUsageInfo by viewModel.dataUsageInfo.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()

    // Force periodic refresh when connected for real-time stats display
    var refreshCounter by remember { mutableStateOf(0L) }
    LaunchedEffect(isConnected) {
        while (isConnected) {
            delay(2000L)  // Update every 2 seconds for live stats
            refreshCounter++
        }
    }

    // Current session data in MB (real-time from DataUsageManager)
    // Re-compute when dataUsageInfo changes OR when refreshCounter increments
    val currentSessionMB = remember(dataUsageInfo, refreshCounter) {
        dataUsageInfo.sessionBytesUsed / (1024.0 * 1024.0)
    }

    // Get real data from history manager based on selected time range
    // Re-fetch when statisticsRefreshTrigger changes (connection added/updated)
    // Also include refreshCounter for live updates during active session
    val recentConnections = remember(statisticsRefreshTrigger, refreshCounter) {
        historyManager.getRecentConnections(5)
    }
    val hasData = recentConnections.isNotEmpty() || isConnected

    // Reactive data that changes with timeRange, refresh trigger, or live counter
    val historicalDailyStats = remember(timeRange, statisticsRefreshTrigger, refreshCounter) {
        if (timeRange == "week") historyManager.getDailyStats()
        else historyManager.getDailyStatsForMonth()
    }

    val dailyStats = remember(historicalDailyStats, isConnected, currentSessionMB) {
        applyLiveSessionToToday(historicalDailyStats, if (isConnected) currentSessionMB else 0.0)
    }

    val totalDuration = remember(timeRange, statisticsRefreshTrigger, refreshCounter) {
        if (timeRange == "week") historyManager.getTotalDurationThisWeek()
        else historyManager.getTotalDurationThisMonth()
    }

    val mostActiveDay = remember(statisticsRefreshTrigger, refreshCounter) { historyManager.getMostActiveDay() }
    val peakUsageTime = remember(statisticsRefreshTrigger, refreshCounter) { historyManager.getPeakUsageTime() }

    // Calculate totals from the same data source the chart uses so the page stays internally consistent.
    val totalUpload = dailyStats.sumOf { it.upload }
    val totalDownload = dailyStats.sumOf { it.download }
    val totalData = totalUpload + totalDownload

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
    ) {
        ScreenHeader(
            title = "Statistics",
            subtitle = "Track your VPN usage",
            onBack = onNavigateBack,
            backLabel = "Back"
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = LibreGuardDimens.screenHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            // Time Range Selector
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = CardBackground,
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(4.dp)
                    ) {
                        TimeRangeButton(
                            text = "This Week",
                            isSelected = timeRange == "week",
                            onClick = { timeRange = "week" },
                            modifier = Modifier.weight(1f)
                        )
                        TimeRangeButton(
                            text = "This Month",
                            isSelected = timeRange == "month",
                            onClick = { timeRange = "month" },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Summary Cards with ripple
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatSummaryCard(
                        icon = Icons.Default.DataUsage,
                        iconColor = Primary,
                        value = formatDataAmount(totalData),
                        label = "Total Data",
                        modifier = Modifier.weight(1f),
                        onClick = { /* Could show detailed breakdown */ }
                    )
                    StatSummaryCard(
                        icon = Icons.Default.Schedule,
                        iconColor = StatusConnected,
                        value = formatDuration(totalDuration),
                        label = "Connected",
                        modifier = Modifier.weight(1f),
                        onClick = { /* Could show time breakdown */ }
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatSummaryCard(
                        icon = Icons.Default.ArrowDownward,
                        iconColor = androidx.compose.ui.graphics.Color(0xFF60A5FA),
                        value = formatDataAmount(totalDownload),
                        label = "Downloaded",
                        modifier = Modifier.weight(1f),
                        onClick = { }
                    )
                    StatSummaryCard(
                        icon = Icons.Default.ArrowUpward,
                        iconColor = androidx.compose.ui.graphics.Color(0xFFA78BFA),
                        value = formatDataAmount(totalUpload),
                        label = "Uploaded",
                        modifier = Modifier.weight(1f),
                        onClick = { }
                    )
                }
            }

            // Usage Chart
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = CardBackground,
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Daily Usage",
                                style = MaterialTheme.typography.titleSmall,
                                color = Foreground
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                LegendItem(
                                    color = androidx.compose.ui.graphics.Color(0xFF60A5FA),
                                    label = "Download"
                                )
                                LegendItem(
                                    color = androidx.compose.ui.graphics.Color(0xFFA78BFA),
                                    label = "Upload"
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        if (dailyStats.isNotEmpty() && totalData > 0) {
                            val maxValue = dailyStats.maxOfOrNull { it.upload + it.download } ?: 1.0
                            dailyStats.forEach { day ->
                                UsageBar(day = day, maxValue = maxValue)
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        } else {
                            // Empty state
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = Icons.Default.DataUsage,
                                        contentDescription = null,
                                        tint = MutedForeground,
                                        modifier = Modifier.size(48.dp)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "No usage data yet",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MutedForeground
                                    )
                                    Text(
                                        text = "Connect to VPN to start tracking",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MutedForeground
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Recent Connections (real or mock)
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = CardBackground,
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = null,
                                tint = MutedForeground,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Recent Connections",
                                style = MaterialTheme.typography.titleSmall,
                                color = Foreground
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        if (recentConnections.isNotEmpty()) {
                            // Real connection history
                            recentConnections.forEachIndexed { index, connection ->
                                ConnectionRow(
                                    location = "${connection.serverName}, ${connection.country}",
                                    time = connection.timeAgo,
                                    duration = connection.formattedDuration,
                                    data = connection.formattedData
                                )
                                if (index < recentConnections.lastIndex) {
                                    HorizontalDivider(color = Border, modifier = Modifier.padding(vertical = 8.dp))
                                }
                            }
                        } else {
                            // Empty state
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = Icons.Default.History,
                                        contentDescription = null,
                                        tint = MutedForeground,
                                        modifier = Modifier.size(48.dp)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "No connection history",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MutedForeground
                                    )
                                    Text(
                                        text = "Your recent connections will appear here",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MutedForeground
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Insights
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = CardBackground,
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.TrendingUp,
                                contentDescription = null,
                                tint = Primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Insights",
                                style = MaterialTheme.typography.titleSmall,
                                color = Foreground
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        if (hasData) {
                            val dailyAverage = if (dailyStats.isNotEmpty()) {
                                totalData / dailyStats.size
                            } else 0.0

                            InsightItem(
                                "Your daily average is",
                                formatDataAmount(dailyAverage)
                            )
                            InsightItem(
                                "Most active day:",
                                mostActiveDay
                            )
                            InsightItem("Peak usage time:", peakUsageTime)
                        } else {
                            // Empty state
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Insights will appear after you connect to VPN",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MutedForeground
                                )
                            }
                        }
                    }
                }
            }

            // Privacy Notice Card
            item {
                PrivacyNoticeCard()
            }
        }
    }
}

private enum class UsageSegment {
    Download,
    Upload
}

// Helper composables
@Composable
private fun PrivacyNoticeCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = CardBackground.copy(alpha = 0.5f),
        border = ButtonDefaults.outlinedButtonBorder(enabled = true)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = Primary.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = "All statistics are stored locally on your device",
                style = MaterialTheme.typography.bodySmall,
                color = MutedForeground
            )
        }
    }
}


@Composable
private fun TimeRangeButton(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isSelected) Primary else androidx.compose.ui.graphics.Color.Transparent,
            contentColor = if (isSelected) PrimaryForeground else MutedForeground
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
    ) {
        Text(text = text, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun StatSummaryCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: androidx.compose.ui.graphics.Color,
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    val interactionSource = remember { MutableInteractionSource() }

    Surface(
        modifier = modifier.clickable(
            interactionSource = interactionSource,
            indication = ripple(
                bounded = true,
                color = Primary.copy(alpha = 0.3f)
            ),
            onClick = onClick
        ),
        shape = RoundedCornerShape(12.dp),
        color = CardBackground,
        border = ButtonDefaults.outlinedButtonBorder(enabled = true)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(iconColor.copy(alpha = 0.1f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = Foreground
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MutedForeground
            )
        }
    }
}

@Composable
private fun LegendItem(
    color: androidx.compose.ui.graphics.Color,
    label: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, RoundedCornerShape(5.dp))
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MutedForeground
        )
    }
}

@Composable
private fun UsageBar(
    day: DailyUsage,
    maxValue: Double
) {
    val totalUsed = (day.download + day.upload).coerceAtLeast(0.0)
    val totalFraction = if (maxValue > 0) {
        (totalUsed / maxValue).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }

    var selectedSegment by remember(day.day) { mutableStateOf<UsageSegment?>(null) }
    var downloadPulseTrigger by remember(day.day) { mutableStateOf(0) }
    var uploadPulseTrigger by remember(day.day) { mutableStateOf(0) }
    val downloadPulseScale = remember(day.day) { Animatable(1f) }
    val uploadPulseScale = remember(day.day) { Animatable(1f) }

    LaunchedEffect(downloadPulseTrigger) {
        if (downloadPulseTrigger == 0) return@LaunchedEffect
        downloadPulseScale.snapTo(1f)
        downloadPulseScale.animateTo(1.08f, animationSpec = tween(140))
        downloadPulseScale.animateTo(
            1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            )
        )
    }

    LaunchedEffect(uploadPulseTrigger) {
        if (uploadPulseTrigger == 0) return@LaunchedEffect
        uploadPulseScale.snapTo(1f)
        uploadPulseScale.animateTo(1.08f, animationSpec = tween(140))
        uploadPulseScale.animateTo(
            1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            )
        )
    }

    val selectedLabel = when (selectedSegment) {
        UsageSegment.Download -> "${formatDataAmount(day.download)} used"
        UsageSegment.Upload -> "${formatDataAmount(day.upload)} used"
        null -> null
    }

    val selectedLabelColor = when (selectedSegment) {
        UsageSegment.Download -> androidx.compose.ui.graphics.Color(0xFF60A5FA)
        UsageSegment.Upload -> androidx.compose.ui.graphics.Color(0xFFA78BFA)
        null -> MutedForeground
    }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = day.day,
                style = MaterialTheme.typography.bodySmall,
                color = MutedForeground
            )
            Text(
                text = formatDataAmount(totalUsed),
                style = MaterialTheme.typography.bodySmall,
                color = MutedForeground
            )
        }

        AnimatedContent(
            targetState = selectedLabel,
            transitionSpec = { fadeIn(animationSpec = tween(180)) togetherWith fadeOut(animationSpec = tween(120)) },
            label = "usage_bar_selection"
        ) { label ->
            if (label != null) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = selectedLabelColor,
                    modifier = Modifier.padding(top = 4.dp)
                )
            } else {
                Spacer(modifier = Modifier.height(0.dp))
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Secondary.copy(alpha = 0.3f))
        ) {
            if (totalFraction > 0f) {
                Row(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(totalFraction)
                        .clip(RoundedCornerShape(4.dp))
                ) {
                    if (day.download > 0) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .weight(day.download.toFloat())
                                .graphicsLayer(
                                    scaleX = downloadPulseScale.value,
                                    scaleY = downloadPulseScale.value
                                )
                                .background(
                                    androidx.compose.ui.graphics.Color(0xFF60A5FA).copy(
                                        alpha = if (selectedSegment == UsageSegment.Download) 1f else 0.92f
                                    )
                                )
                                .clickable {
                                    selectedSegment = UsageSegment.Download
                                    downloadPulseTrigger++
                                }
                        )
                    }

                    if (day.upload > 0) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .weight(day.upload.toFloat())
                                .graphicsLayer(
                                    scaleX = uploadPulseScale.value,
                                    scaleY = uploadPulseScale.value
                                )
                                .background(
                                    androidx.compose.ui.graphics.Color(0xFFA78BFA).copy(
                                        alpha = if (selectedSegment == UsageSegment.Upload) 1f else 0.92f
                                    )
                                )
                                .clickable {
                                    selectedSegment = UsageSegment.Upload
                                    uploadPulseTrigger++
                                }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionRow(
    location: String,
    time: String,
    duration: String,
    data: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = location,
                style = MaterialTheme.typography.bodyMedium,
                color = Foreground
            )
            Text(
                text = time,
                style = MaterialTheme.typography.bodySmall,
                color = MutedForeground
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = data,
                style = MaterialTheme.typography.bodyMedium,
                color = Foreground
            )
            Text(
                text = duration,
                style = MaterialTheme.typography.bodySmall,
                color = MutedForeground
            )
        }
    }
}

@Composable
private fun InsightItem(prefix: String, value: String) {
    Row(
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        Text(
            text = "• $prefix ",
            style = MaterialTheme.typography.bodySmall,
            color = MutedForeground
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = Primary
        )
    }
}

private fun formatDuration(minutes: Int): String {
    val hours = minutes / 60
    val mins = minutes % 60
    return "${hours}h ${mins}m"
}

private fun applyLiveSessionToToday(stats: List<DailyUsage>, liveSessionMB: Double): List<DailyUsage> {
    if (liveSessionMB <= 0.0) return stats

    val (liveDownload, liveUpload) = estimateSplit(liveSessionMB)
    return stats.map { day ->
        if (day.day == "Today") {
            day.copy(
                download = day.download + liveDownload,
                upload = day.upload + liveUpload
            )
        } else {
            day
        }
    }
}

private fun estimateSplit(totalMB: Double): Pair<Double, Double> {
    return totalMB * 0.8 to totalMB * 0.2
}

