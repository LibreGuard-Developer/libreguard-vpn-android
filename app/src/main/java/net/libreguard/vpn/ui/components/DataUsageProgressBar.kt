package net.libreguard.vpn.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.libreguard.vpn.service.data.DataUsageInfo

/**
 * Data Usage Progress Bar with color coding and animations
 * Green: < 50%, Yellow: 50-80%, Orange/Red: > 80%
 */
@Composable
fun DataUsageProgressBar(
    dataUsage: DataUsageInfo,
    modifier: Modifier = Modifier
) {
    val animatedProgress by animateFloatAsState(
        targetValue = dataUsage.usagePercentage / 100f,
        animationSpec = tween(durationMillis = 1000),
        label = "DataUsageProgress"
    )

    val progressColor = when {
        dataUsage.usagePercentage < 50f -> Color(0xFF4CAF50) // Green
        dataUsage.usagePercentage < 80f -> Color(0xFFFFC107) // Yellow
        else -> Color(0xFFF44336) // Red
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Data Usage",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "${dataUsage.usagePercentage.toInt()}%",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = progressColor
                )
            }

            // Progress Bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp))
            ) {
                // Background
                Canvas(
                    modifier = Modifier.fillMaxSize()
                ) {
                    drawRoundedProgressBar(
                        progress = 1f,
                        color = Color.Gray.copy(alpha = 0.2f),
                        strokeWidth = size.height
                    )
                }

                // Progress
                Canvas(
                    modifier = Modifier.fillMaxSize()
                ) {
                    drawRoundedProgressBar(
                        progress = animatedProgress,
                        color = progressColor,
                        strokeWidth = size.height
                    )
                }
            }

            // Usage Statistics
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Total Used",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = dataUsage.formattedTotal,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Limit",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = dataUsage.formattedLimit,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Session Usage (when connected)
            if (dataUsage.sessionBytesUsed > 0) {
                Divider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "This Session:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = dataUsage.formattedSession,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Warning message when near limit
            if (dataUsage.isNearLimit) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFFFF3E0) // Light orange background
                    )
                ) {
                    Text(
                        text = "⚠️ Approaching data limit! ${(100 - dataUsage.usagePercentage).toInt()}% remaining",
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFE65100), // Dark orange text
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * Draw a rounded progress bar
 */
private fun DrawScope.drawRoundedProgressBar(
    progress: Float,
    color: Color,
    strokeWidth: Float
) {
    val progressWidth = size.width * progress

    drawLine(
        color = color,
        start = Offset(strokeWidth / 2, size.height / 2),
        end = Offset(progressWidth - strokeWidth / 2, size.height / 2),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round
    )
}

/**
 * Compact data usage indicator for the status bar area
 */
@Composable
fun CompactDataUsageIndicator(
    dataUsage: DataUsageInfo,
    modifier: Modifier = Modifier
) {
    val progressColor = when {
        dataUsage.usagePercentage < 50f -> Color(0xFF4CAF50)
        dataUsage.usagePercentage < 80f -> Color(0xFFFFC107)
        else -> Color(0xFFF44336)
    }

    val animatedProgress by animateFloatAsState(
        targetValue = (dataUsage.usagePercentage.coerceIn(0f, 100f) / 100f),
        animationSpec = tween(durationMillis = 600)
    )

    Row(
        modifier = modifier
            .wrapContentWidth()
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Circular progress
        Box(modifier = Modifier.size(36.dp)) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidth = 4.dp.toPx()
                val canvasSize = this.size
                val radius = (canvasSize.minDimension - strokeWidth) / 2f
                val center = Offset(canvasSize.width / 2f, canvasSize.height / 2f)

                // Background circle
                drawCircle(
                    color = Color.Gray.copy(alpha = 0.2f),
                    radius = radius,
                    center = center,
                    style = Stroke(strokeWidth)
                )

                // Progress arc
                val sweepAngle = 360f * animatedProgress
                drawArc(
                    color = progressColor,
                    startAngle = -90f,
                    sweepAngle = sweepAngle,
                    useCenter = false,
                    style = Stroke(strokeWidth, cap = StrokeCap.Round),
                    topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f),
                    size = Size(canvasSize.width - strokeWidth, canvasSize.height - strokeWidth)
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = "${dataUsage.usagePercentage.toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = progressColor,
                fontSize = 14.sp
            )

            Text(
                text = dataUsage.formattedTotal,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )

            if (dataUsage.sessionBytesUsed > 0) {
                Text(
                    text = "Session: ${dataUsage.formattedSession}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 11.sp
                )
            }
        }
    }
}
