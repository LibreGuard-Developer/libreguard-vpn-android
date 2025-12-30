package net.libreguard.vpn.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.libreguard.vpn.R
import net.libreguard.vpn.ui.theme.Primary

/**
 * LibreGuard Logo with gradient overlay for smooth edges
 * Used in headers and branding throughout the app
 */
@Composable
fun LogoWithGradient(
    size: Dp = 96.dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        // Logo image
        Image(
            painter = painterResource(id = R.drawable.logo_primary),
            contentDescription = "LibreGuard",
            modifier = Modifier.size(size)
        )

        // White gradient overlay for smooth edges (optional, can be removed if not needed)
        Box(
            modifier = Modifier
                .size(size)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Transparent,
                            Color.White.copy(alpha = 0.1f),
                            Color.White.copy(alpha = 0.3f)
                        ),
                        radius = size.value * 0.8f
                    )
                )
        )
    }
}

/**
 * Simple logo without gradient overlay
 */
@Composable
fun Logo(
    size: Dp = 96.dp,
    modifier: Modifier = Modifier
) {
    Image(
        painter = painterResource(id = R.drawable.logo_primary),
        contentDescription = "LibreGuard",
        modifier = modifier.size(size)
    )
}
