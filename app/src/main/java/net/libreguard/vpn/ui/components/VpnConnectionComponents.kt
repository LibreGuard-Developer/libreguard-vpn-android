package net.libreguard.vpn.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import net.libreguard.vpn.ui.theme.*

/**
 * VPN Connection Status
 */
enum class VpnConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}

/**
 * VPN Status configuration
 */
data class VpnStatusConfig(
    val color: Color,
    val text: String,
    val description: String,
    val buttonText: String
)

/**
 * Get configuration based on connection status
 */
@Composable
fun getStatusConfig(status: VpnConnectionStatus): VpnStatusConfig {
    return when (status) {
        VpnConnectionStatus.CONNECTED -> VpnStatusConfig(
            color = StatusConnected,
            text = "Protected",
            description = "Your connection is secure",
            buttonText = "Disconnect"
        )
        VpnConnectionStatus.CONNECTING -> VpnStatusConfig(
            color = StatusConnecting,
            text = "Connecting",
            description = "Establishing secure connection...",
            buttonText = "Cancel"
        )
        VpnConnectionStatus.DISCONNECTED -> VpnStatusConfig(
            color = StatusDisconnected,
            text = "Not Protected",
            description = "Your connection is not secure",
            buttonText = "Connect"
        )
    }
}

/**
 * VPN Connection Shield - The main connect/disconnect button
 */
@Composable
fun VpnConnectionShield(
    status: VpnConnectionStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val config = getStatusConfig(status)

    // Animation for breathing effect when connected
    val infiniteTransition = rememberInfiniteTransition(label = "shieldPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (status == VpnConnectionStatus.CONNECTED) 1.02f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    // Animation for connecting ripple
    val rippleScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (status == VpnConnectionStatus.CONNECTING) 1.2f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOut),
            repeatMode = RepeatMode.Restart
        ),
        label = "rippleScale"
    )
    val rippleAlpha by infiniteTransition.animateFloat(
        initialValue = if (status == VpnConnectionStatus.CONNECTING) 0.8f else 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOut),
            repeatMode = RepeatMode.Restart
        ),
        label = "rippleAlpha"
    )

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = tween(100),
        label = "pressScale"
    )

    Box(
        modifier = modifier
            .scale(pulseScale * pressScale),
        contentAlignment = Alignment.Center
    ) {
        // Ripple effect for connecting state
        if (status == VpnConnectionStatus.CONNECTING) {
            Box(
                modifier = Modifier
                    .size(160.dp)
                    .scale(rippleScale)
                    .clip(CircleShape)
                    .background(config.color.copy(alpha = rippleAlpha))
            )
        }

        // Outer glow ring
        Box(
            modifier = Modifier
                .size(160.dp)
                .clip(CircleShape)
                .background(config.color.copy(alpha = 0.08f))
                .shadow(
                    elevation = 0.dp,
                    shape = CircleShape,
                    ambientColor = config.color.copy(alpha = 0.2f),
                    spotColor = config.color.copy(alpha = 0.2f)
                ),
            contentAlignment = Alignment.Center
        ) {
            // Inner circle
            Box(
                modifier = Modifier
                    .size(128.dp)
                    .clip(CircleShape)
                    .background(config.color.copy(alpha = 0.15f))
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null
                    ) { onClick() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = config.text,
                    modifier = Modifier.size(64.dp),
                    tint = config.color
                )
            }
        }
    }
}

/**
 * VPN Status Text Display
 */
@Composable
fun VpnStatusText(
    status: VpnConnectionStatus,
    modifier: Modifier = Modifier
) {
    val config = getStatusConfig(status)

    // Animate text scale when connecting
    val infiniteTransition = rememberInfiniteTransition(label = "textPulse")
    val textScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (status == VpnConnectionStatus.CONNECTING) 1.05f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "textScale"
    )

    Column(
        modifier = modifier.scale(textScale),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = config.text,
            style = MaterialTheme.typography.headlineMedium,
            color = config.color
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = config.description,
            style = MaterialTheme.typography.bodyMedium,
            color = MutedForeground
        )
    }
}

/**
 * VPN Connect/Disconnect Button
 */
@Composable
fun VpnConnectButton(
    status: VpnConnectionStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val config = getStatusConfig(status)
    val enabled = status != VpnConnectionStatus.CONNECTING

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.95f else 1f,
        animationSpec = tween(100),
        label = "buttonScale"
    )

    Button(
        onClick = onClick,
        modifier = modifier
            .scale(scale)
            .height(56.dp)
            .padding(horizontal = 48.dp),
        enabled = enabled,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Primary,
            contentColor = PrimaryForeground,
            disabledContainerColor = Primary.copy(alpha = 0.5f),
            disabledContentColor = PrimaryForeground.copy(alpha = 0.7f)
        ),
        interactionSource = interactionSource
    ) {
        Text(
            text = config.buttonText,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

