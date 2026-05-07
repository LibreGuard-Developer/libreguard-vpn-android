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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.libreguard.vpn.R
import net.libreguard.vpn.ui.theme.IsDarkMode
import net.libreguard.vpn.ui.theme.Primary

/**
 * LibreGuard Logo with a subtle external halo so it transitions cleanly on dark backgrounds.
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
            contentDescription = "LibreGuard",
            modifier = Modifier
                .size(size)
                .clip(shape)
                .border(width = 0.75.dp, color = borderColor, shape = shape)
        )
    }
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
        contentDescription = "LibreGuard",
        modifier = modifier.size(size)
    )
}
