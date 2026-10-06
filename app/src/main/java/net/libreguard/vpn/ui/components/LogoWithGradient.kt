package net.libreguard.vpn.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.libreguard.vpn.R
import net.libreguard.vpn.ui.theme.IsDarkMode
import net.libreguard.vpn.ui.theme.Primary

/**
 * LibreGuard logo with a subtle external halo so it transitions cleanly on dark backgrounds.
 */
@Composable
fun LogoWithGradient(
    modifier: Modifier = Modifier,
    size: Dp = 96.dp
) {
    val haloPadding = 3.dp
    val outerSize = size + (haloPadding * 2)
    val shape = RoundedCornerShape((size * 0.18f).coerceAtLeast(7.dp))
    val haloColor = Primary.copy(alpha = if (IsDarkMode) 0.20f else 0.08f)
    val haloFill = Primary.copy(alpha = if (IsDarkMode) 0.06f else 0.025f)
    val borderColor = Primary.copy(alpha = if (IsDarkMode) 0.22f else 0.10f)

    Box(
        modifier = modifier.size(outerSize),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(outerSize)
                .shadow(
                    elevation = if (IsDarkMode) 4.dp else 2.dp,
                    shape = shape,
                    ambientColor = haloColor,
                    spotColor = haloColor
                )
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = shape,
                color = haloFill
            ) {}
        }

        Image(
            painter = painterResource(id = R.drawable.logo_primary),
            contentDescription = "LibreGuard shield",
            modifier = Modifier
                .size(size)
                .clip(shape)
                .border(width = 0.75.dp, color = borderColor, shape = shape)
        )
    }
}

/**
 * Shield logo for the dashboard, with a very slight fade at the image edges.
 */
@Composable
fun LogoWithEdgeFade(
    modifier: Modifier = Modifier,
    size: Dp = 96.dp
) {
    Image(
        painter = painterResource(id = R.drawable.logo_primary),
        contentDescription = "LibreGuard shield",
        modifier = modifier
            .size(size)
            .fadeEdges(1.dp)
    )
}

private fun Modifier.fadeEdges(fadeWidth: Dp): Modifier =
    graphicsLayer {
        compositingStrategy = CompositingStrategy.Offscreen
    }.drawWithContent {
        drawContent()
        if (this.size.width <= 0f || this.size.height <= 0f) return@drawWithContent

        val fadePixels = fadeWidth.toPx().coerceAtMost(this.size.minDimension / 2f)
        val horizontalFade = (fadePixels / this.size.width).coerceAtMost(0.5f)
        val verticalFade = (fadePixels / this.size.height).coerceAtMost(0.5f)

        drawRect(
            brush = Brush.linearGradient(
                colorStops = arrayOf(
                    0f to Color.Transparent,
                    horizontalFade to Color.White,
                    (1f - horizontalFade) to Color.White,
                    1f to Color.Transparent
                ),
                start = Offset.Zero,
                end = Offset(this.size.width, 0f)
            ),
            blendMode = BlendMode.DstIn
        )
        drawRect(
            brush = Brush.linearGradient(
                colorStops = arrayOf(
                    0f to Color.Transparent,
                    verticalFade to Color.White,
                    (1f - verticalFade) to Color.White,
                    1f to Color.Transparent
                ),
                start = Offset.Zero,
                end = Offset(0f, this.size.height)
            ),
            blendMode = BlendMode.DstIn
        )
    }

/**
 * Crops the transparent margins from the supplied wordmark for a compact header fit.
 */
@Composable
fun LogoWordmark(
    modifier: Modifier = Modifier,
    width: Dp = 140.dp,
    height: Dp = 24.dp
) {
    Image(
        painter = painterResource(id = R.drawable.logo_wordmark),
        contentDescription = "LibreGuard VPN",
        contentScale = ContentScale.Crop,
        modifier = modifier.size(width, height)
    )
}

/**
 * Simple logo without external halo.
 */
@Composable
fun Logo(
    modifier: Modifier = Modifier,
    size: Dp = 96.dp
) {
    Image(
        painter = painterResource(id = R.drawable.logo_primary),
        contentDescription = "LibreGuard VPN",
        modifier = modifier.size(size)
    )
}
