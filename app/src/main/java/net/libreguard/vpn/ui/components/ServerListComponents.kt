package net.libreguard.vpn.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.libreguard.vpn.ui.theme.*

/**
 * Server data model for UI
 */
data class ServerItemData(
    val id: String,
    val country: String,
    val city: String,
    val flag: String,
    val ping: Int,
    val load: Int,
    val isPremium: Boolean = false
)

/**
 * Get color based on ping latency
 */
@Composable
private fun getPingColor(ping: Int): Color {
    return when {
        ping <= 100 -> StatusConnected
        ping <= 200 -> Primary
        else -> MutedForeground
    }
}

/**
 * Get color based on server load
 */
@Composable
private fun getLoadColor(load: Int): Color {
    return when {
        load < 40 -> StatusConnected
        load < 70 -> StatusConnecting
        else -> Destructive
    }
}

/**
 * Server list item component matching the new design
 */
@Composable
fun ServerListItem(
    server: ServerItemData,
    isSelected: Boolean,
    isFavorite: Boolean,
    onSelect: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1f,
        animationSpec = tween(100),
        label = "itemScale"
    )

    val borderColor by animateColorAsState(
        targetValue = if (isSelected) Primary else Border,
        label = "borderColor"
    )

    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) Primary.copy(alpha = 0.05f) else CardBackground,
        label = "backgroundColor"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { onSelect() },
        shape = RoundedCornerShape(12.dp),
        color = backgroundColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Flag emoji with PRO label
                Box(
                    modifier = Modifier
                        .padding(end = 16.dp)
                        .width(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        // PRO label above flag
                        if (server.isPremium) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Primary
                            ) {
                                Text(
                                    text = "PRO",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                        }
                        // Flag emoji
                        Text(
                            text = server.flag,
                            fontSize = 28.sp
                        )
                    }
                }

                // Server info
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = server.city,
                        style = MaterialTheme.typography.titleSmall,
                        color = Foreground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = server.country,
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedForeground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Stats
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Ping
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "${server.ping}ms",
                            style = MaterialTheme.typography.labelSmall,
                            color = getPingColor(server.ping)
                        )
                    }

                    // Load
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "${server.load}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = getLoadColor(server.load)
                        )
                    }

                    // Favorite button
                    IconButton(
                        onClick = onToggleFavorite,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.Star,
                            contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                            tint = if (isFavorite) Primary else MutedForeground,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Chevron
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = if (isSelected) Primary else MutedForeground,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Load bar
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Secondary.copy(alpha = 0.3f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(server.load / 100f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp))
                        .background(getLoadColor(server.load))
                )
            }
        }
    }
}

/**
 * Compact server item for the main screen
 */
@Composable
fun CompactServerItem(
    server: ServerItemData,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        color = CardBackground,
        border = androidx.compose.foundation.BorderStroke(1.dp, Border)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = server.flag,
                fontSize = 24.sp
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.city,
                    style = MaterialTheme.typography.titleSmall,
                    color = Foreground
                )
                Text(
                    text = "${server.ping}ms",
                    style = MaterialTheme.typography.bodySmall,
                    color = getPingColor(server.ping)
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Select server",
                tint = MutedForeground
            )
        }
    }
}
