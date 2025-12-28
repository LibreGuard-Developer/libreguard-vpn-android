package net.libreguard.vpn.ui.screens

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import net.libreguard.vpn.data.ConnectionHistoryManager
import net.libreguard.vpn.data.DailyUsage
import net.libreguard.vpn.ui.theme.*
import net.libreguard.vpn.viewmodel.VpnViewModel
import java.util.Locale

/**
 * Statistics Screen - Usage data and analytics
 * Based on design from Statistics.tsx
 * Features: Ripple effects on cards, real connection history
 */
@Composable
fun StatisticsScreen(viewModel: VpnViewModel) {
    val context = LocalContext.current
    val historyManager = remember { ConnectionHistoryManager(context) }

    var timeRange by remember { mutableStateOf("week") }

    // Get real data from history manager based on selected time range
    val recentConnections = remember { historyManager.getRecentConnections(5) }
    val hasData = recentConnections.isNotEmpty()

    // Reactive data that changes with timeRange
    val dailyStats = remember(timeRange) {
        if (timeRange == "week") historyManager.getDailyStats()
        else historyManager.getDailyStatsForMonth()
    }

    val totalDuration = remember(timeRange) {
        if (timeRange == "week") historyManager.getTotalDurationThisWeek()
        else historyManager.getTotalDurationThisMonth()
    }

    val mostActiveDay = remember { historyManager.getMostActiveDay() }
    val peakUsageTime = remember { historyManager.getPeakUsageTime() }

    // Calculate totals
    val totalUpload = dailyStats.sumOf { it.upload }
    val totalDownload = dailyStats.sumOf { it.download }
    val totalData = totalUpload + totalDownload

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
    ) {
        // Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = "Statistics",
                style = MaterialTheme.typography.headlineSmall,
                color = Foreground
            )
            Text(
                text = "Track your VPN usage",
                style = MaterialTheme.typography.bodySmall,
                color = MutedForeground
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
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
                        value = String.format(Locale.US, "%.2f GB", totalData / 1024.0),
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
                        value = String.format(Locale.US, "%.2f GB", totalDownload / 1024.0),
                        label = "Downloaded",
                        modifier = Modifier.weight(1f),
                        onClick = { }
                    )
                    StatSummaryCard(
                        icon = Icons.Default.ArrowUpward,
                        iconColor = androidx.compose.ui.graphics.Color(0xFFA78BFA),
                        value = String.format(Locale.US, "%.2f GB", totalUpload / 1024.0),
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
                                totalData / dailyStats.size / 1024.0
                            } else 0.0

                            InsightItem(
                                "Your daily average is",
                                String.format(Locale.US, "%.2f GB", dailyAverage)
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
    val downloadPercent = (day.download / maxValue * 100).toFloat()
    val uploadPercent = (day.upload / maxValue * 100).toFloat()

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
                text = String.format(Locale.US, "%.2f GB", (day.upload + day.download) / 1024.0),
                style = MaterialTheme.typography.bodySmall,
                color = MutedForeground
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Secondary.copy(alpha = 0.3f)),
            horizontalArrangement = Arrangement.Start
        ) {
            // Download bar
            if (downloadPercent > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(downloadPercent / 100f)
                        .background(androidx.compose.ui.graphics.Color(0xFF60A5FA))
                )
            }
            // Upload bar
            if (uploadPercent > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth((uploadPercent / (100f - downloadPercent)).coerceIn(0f, 1f))
                        .background(androidx.compose.ui.graphics.Color(0xFFA78BFA))
                )
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

