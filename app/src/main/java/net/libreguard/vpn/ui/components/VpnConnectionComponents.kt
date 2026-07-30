package net.libreguard.vpn.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.expandVertically
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
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
            description = "Secure tunnel active",
            buttonText = "Disconnect"
        )
        VpnConnectionStatus.CONNECTING -> VpnStatusConfig(
            color = StatusConnecting,
            text = "Connecting",
            description = "Establishing secure tunnel...",
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

@Composable
private fun rememberConnectionProgress(status: VpnConnectionStatus): Float {
    val progress = remember { Animatable(0f) }

    LaunchedEffect(status) {
        when (status) {
            VpnConnectionStatus.DISCONNECTED -> {
                progress.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing)
                )
            }

            VpnConnectionStatus.CONNECTING -> {
                if (progress.value <= 0.01f || progress.value >= 0.96f) {
                    progress.snapTo(0.06f)
                }

                if (progress.value < 0.16f) {
                    progress.animateTo(
                        targetValue = 0.16f,
                        animationSpec = tween(durationMillis = 260, easing = LinearOutSlowInEasing)
                    )
                }

                while (currentCoroutineContext().isActive) {
                    val target = (progress.value + (0.92f - progress.value) * 0.24f)
                        .coerceAtMost(0.92f)
                    val remaining = (0.92f - progress.value).coerceAtLeast(0.04f)

                    progress.animateTo(
                        targetValue = target,
                        animationSpec = tween(
                            durationMillis = (550 + remaining * 2200).toInt(),
                            easing = LinearOutSlowInEasing
                        )
                    )
                }
            }

            VpnConnectionStatus.CONNECTED -> {
                if (progress.value < 0.82f) {
                    progress.snapTo(0.82f)
                }
                progress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 620, easing = FastOutSlowInEasing)
                )
            }
        }
    }

    return progress.value
}

@Composable
fun VpnConnectionHero(
    status: VpnConnectionStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showProgressBar: Boolean = true,
    compact: Boolean = false
) {
    val progress = rememberConnectionProgress(status)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        VpnConnectionShield(
            status = status,
            onClick = onClick,
            progress = progress,
            compact = compact
        )

        // Keep the status copy close enough to the hero that connected-state cards retain room.
        Spacer(modifier = Modifier.height(if (compact) 8.dp else 12.dp))

        VpnStatusText(
            status = status,
            progress = progress,
            showProgressBar = showProgressBar,
            compact = compact
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
    modifier: Modifier = Modifier,
    progress: Float? = null,
    compact: Boolean = false
) {
    val config = getStatusConfig(status)
    val connectionProgress = progress ?: rememberConnectionProgress(status)
    val primaryColor = Primary
    val surfaceColor = CardBackground

    val transition = updateTransition(targetState = status, label = "shieldState")
    val shieldScale by transition.animateFloat(
        transitionSpec = {
            spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioLowBouncy)
        },
        label = "shieldScale"
    ) { targetStatus ->
        when (targetStatus) {
            VpnConnectionStatus.DISCONNECTED -> 0.98f
            VpnConnectionStatus.CONNECTING -> 1.01f
            VpnConnectionStatus.CONNECTED -> 1.04f
        }
    }
    val iconScale by transition.animateFloat(
        transitionSpec = { tween(durationMillis = 420, easing = FastOutSlowInEasing) },
        label = "iconScale"
    ) { targetStatus ->
        when (targetStatus) {
            VpnConnectionStatus.DISCONNECTED -> 0.96f
            VpnConnectionStatus.CONNECTING -> 1f
            VpnConnectionStatus.CONNECTED -> 1.05f
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "shieldEnergy")
    val orbitRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orbitRotation"
    )
    val haloPulse by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "haloPulse"
    )
    val connectedPulse by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "connectedPulse"
    )

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = tween(100),
        label = "pressScale"
    )

    val activePulseScale = when (status) {
        VpnConnectionStatus.CONNECTING -> 1f + haloPulse * 0.015f
        VpnConnectionStatus.CONNECTED -> 1f + connectedPulse * 0.024f
        VpnConnectionStatus.DISCONNECTED -> 1f
    }
    val shieldDiameter = if (compact) 176.dp else 188.dp
    val glowDiameter = if (compact) 144.dp else 154.dp
    val buttonDiameter = if (compact) 120.dp else 128.dp
    val iconDiameter = if (compact) 60.dp else 64.dp

    Box(
        modifier = modifier
            .scale(shieldScale * activePulseScale * pressScale),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(shieldDiameter)) {
            val strokeWidth = size.minDimension * 0.055f
            val ringInset = strokeWidth / 2f + 8f
            val ringTopLeft = Offset(ringInset, ringInset)
            val ringSize = Size(size.width - ringInset * 2f, size.height - ringInset * 2f)
            val ringRadius = size.minDimension * 0.47f

            drawCircle(
                color = config.color.copy(
                    alpha = when (status) {
                        VpnConnectionStatus.DISCONNECTED -> 0.05f
                        VpnConnectionStatus.CONNECTING -> 0.08f + haloPulse * 0.08f
                        VpnConnectionStatus.CONNECTED -> 0.10f + connectedPulse * 0.06f
                    }
                ),
                radius = ringRadius
            )

            if (status == VpnConnectionStatus.CONNECTING) {
                drawCircle(
                    color = config.color.copy(alpha = (1f - haloPulse) * 0.10f),
                    radius = size.minDimension * (0.46f + haloPulse * 0.09f)
                )
            }

            drawArc(
                color = config.color.copy(alpha = 0.13f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = ringTopLeft,
                size = ringSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            val progressSweep = (360f * connectionProgress).coerceIn(0f, 360f)
            if (progressSweep > 1f) {
                drawArc(
                    brush = Brush.sweepGradient(
                        listOf(
                            primaryColor.copy(alpha = 0.18f),
                            config.color.copy(alpha = 0.95f),
                            primaryColor.copy(alpha = 0.78f),
                            config.color.copy(alpha = 0.95f),
                            primaryColor.copy(alpha = 0.18f)
                        )
                    ),
                    startAngle = -90f,
                    sweepAngle = progressSweep,
                    useCenter = false,
                    topLeft = ringTopLeft,
                    size = ringSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }

            if (status == VpnConnectionStatus.CONNECTING) {
                rotate(orbitRotation) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            listOf(
                                Color.Transparent,
                                primaryColor.copy(alpha = 0.12f),
                                config.color.copy(alpha = 0.92f),
                                primaryColor.copy(alpha = 0.48f),
                                Color.Transparent
                            )
                        ),
                        startAngle = -110f,
                        sweepAngle = 92f,
                        useCenter = false,
                        topLeft = ringTopLeft,
                        size = ringSize,
                        style = Stroke(width = strokeWidth * 0.9f, cap = StrokeCap.Round)
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .size(glowDiameter)
                .clip(CircleShape)
                .background(
                    brush = Brush.radialGradient(
                        listOf(
                            config.color.copy(
                                alpha = when (status) {
                                    VpnConnectionStatus.DISCONNECTED -> 0.04f
                                    VpnConnectionStatus.CONNECTING -> 0.12f
                                    VpnConnectionStatus.CONNECTED -> 0.16f
                                }
                            ),
                            config.color.copy(alpha = 0.03f),
                            Color.Transparent
                        )
                    )
                )
        )

        Box(
            modifier = Modifier
                .size(buttonDiameter)
                .shadow(
                    elevation = if (status == VpnConnectionStatus.CONNECTED) 18.dp else 10.dp,
                    shape = CircleShape,
                    ambientColor = config.color.copy(alpha = 0.22f),
                    spotColor = config.color.copy(alpha = 0.22f)
                )
                .clip(CircleShape)
                .background(
                    brush = Brush.verticalGradient(
                        listOf(
                            surfaceColor.copy(alpha = 0.98f),
                            config.color.copy(alpha = 0.16f),
                            surfaceColor.copy(alpha = 0.98f)
                        )
                    )
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = null
                ) { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Shield,
                contentDescription = config.text,
                modifier = Modifier
                    .size(iconDiameter)
                    .graphicsLayer {
                        scaleX = iconScale
                        scaleY = iconScale
                    },
                tint = config.color
            )
        }
    }
}

/**
 * VPN Status Text Display
 */
@Composable
fun VpnStatusText(
    status: VpnConnectionStatus,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    showProgressBar: Boolean = true,
    compact: Boolean = false
) {
    val connectionProgress = progress ?: rememberConnectionProgress(status)
    val titleColor by animateColorAsState(
        targetValue = getStatusConfig(status).color,
        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
        label = "titleColor"
    )
    val transition = updateTransition(targetState = status, label = "statusTextState")
    val textScale by transition.animateFloat(
        transitionSpec = { spring(stiffness = Spring.StiffnessLow, dampingRatio = Spring.DampingRatioNoBouncy) },
        label = "textScale"
    ) { targetStatus ->
        when (targetStatus) {
            VpnConnectionStatus.CONNECTING -> 1.02f
            VpnConnectionStatus.CONNECTED -> 1.01f
            VpnConnectionStatus.DISCONNECTED -> 1f
        }
    }
    val shouldShowProgress = showProgressBar &&
        (status == VpnConnectionStatus.CONNECTING ||
            (status == VpnConnectionStatus.CONNECTED && connectionProgress < 0.999f))

    Column(
        modifier = modifier.scale(textScale),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AnimatedContent(
            targetState = status,
            transitionSpec = {
                (fadeIn(animationSpec = tween(320, delayMillis = 80)) +
                    scaleIn(initialScale = 0.97f, animationSpec = tween(320, delayMillis = 80))) togetherWith
                    (fadeOut(animationSpec = tween(160)) +
                        scaleOut(targetScale = 0.98f, animationSpec = tween(160)))
            },
            label = "statusCopy"
        ) { targetStatus ->
            val config = getStatusConfig(targetStatus)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = config.text,
                    style = MaterialTheme.typography.headlineMedium,
                    color = titleColor
                )
                Spacer(modifier = Modifier.height(if (compact) 4.dp else 6.dp))
                Text(
                    text = config.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MutedForeground
                )
            }
        }

        AnimatedVisibility(
            visible = shouldShowProgress,
            enter = fadeIn(animationSpec = tween(220)) + expandVertically(expandFrom = Alignment.Top),
            exit = fadeOut(animationSpec = tween(180)) + shrinkVertically(shrinkTowards = Alignment.Top)
        ) {
            Column(
                modifier = Modifier.padding(top = if (compact) 10.dp else 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                VpnConnectionProgressBar(
                    progress = connectionProgress,
                    status = status,
                    modifier = Modifier
                        .width(if (compact) 210.dp else 224.dp)
                        .height(if (compact) 8.dp else 10.dp)
                )

                Spacer(modifier = Modifier.height(if (compact) 4.dp else 6.dp))

                Text(
                    text = if (status == VpnConnectionStatus.CONNECTED) {
                        "Tunnel established"
                    } else {
                        "Securing tunnel"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = titleColor.copy(alpha = 0.9f)
                )
            }
        }
    }
}

@Composable
private fun VpnConnectionProgressBar(
    progress: Float,
    status: VpnConnectionStatus,
    modifier: Modifier = Modifier
) {
    val barColor = when (status) {
        VpnConnectionStatus.CONNECTED -> StatusConnected
        VpnConnectionStatus.CONNECTING -> StatusConnecting
        VpnConnectionStatus.DISCONNECTED -> StatusDisconnected
    }
    val primaryColor = Primary
    val shimmerTransition = rememberInfiniteTransition(label = "progressShimmer")
    val shimmer by shimmerTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer"
    )

    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
    ) {
        val radius = size.height / 2f
        val progressWidth = (size.width * progress.coerceIn(0f, 1f)).coerceAtLeast(0f)

        drawRoundRect(
            color = barColor.copy(alpha = 0.12f),
            cornerRadius = CornerRadius(radius, radius)
        )

        if (progressWidth <= 0f) return@Canvas

        drawRoundRect(
            color = barColor.copy(alpha = 0.24f),
            size = Size(progressWidth, size.height),
            cornerRadius = CornerRadius(radius, radius)
        )

        drawRoundRect(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    primaryColor.copy(alpha = 0.88f),
                    barColor,
                    Color.White.copy(alpha = if (status == VpnConnectionStatus.CONNECTED) 0.76f else 0.48f)
                ),
                startX = 0f,
                endX = progressWidth.coerceAtLeast(1f)
            ),
            size = Size(progressWidth, size.height),
            cornerRadius = CornerRadius(radius, radius)
        )

        drawCircle(
            color = barColor.copy(alpha = if (status == VpnConnectionStatus.CONNECTED) 0.30f else 0.24f),
            radius = size.height * 1.05f,
            center = Offset(progressWidth.coerceAtLeast(radius), size.height / 2f)
        )

        if (status == VpnConnectionStatus.CONNECTING && progressWidth > size.height) {
            val shimmerWidth = size.width * 0.22f
            val shimmerStart = (progressWidth + shimmerWidth) * shimmer - shimmerWidth

            drawRoundRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = 0.40f),
                        Color.Transparent
                    ),
                    startX = shimmerStart,
                    endX = shimmerStart + shimmerWidth
                ),
                topLeft = Offset(shimmerStart, 0f),
                size = Size(shimmerWidth, size.height),
                cornerRadius = CornerRadius(radius, radius)
            )
        }
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

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = tween(100),
        label = "buttonScale"
    )

    Button(
        onClick = onClick,
        modifier = modifier
            .scale(scale)
            .height(56.dp)
            .padding(horizontal = 48.dp),
        enabled = true,
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

