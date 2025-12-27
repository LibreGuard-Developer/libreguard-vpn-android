package net.libreguard.vpn.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// LibreGuard VPN Light Color Scheme
private val LightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = PrimaryForeground,
    primaryContainer = Primary.copy(alpha = 0.1f),
    onPrimaryContainer = Primary,
    secondary = Secondary,
    onSecondary = SecondaryForeground,
    secondaryContainer = Secondary,
    onSecondaryContainer = SecondaryForeground,
    tertiary = Accent,
    onTertiary = AccentForeground,
    tertiaryContainer = Accent.copy(alpha = 0.1f),
    onTertiaryContainer = Accent,
    background = Background,
    onBackground = Foreground,
    surface = Background,
    onSurface = Foreground,
    surfaceVariant = CardBackground,
    onSurfaceVariant = CardForeground,
    surfaceTint = Primary,
    error = Destructive,
    onError = DestructiveForeground,
    errorContainer = Destructive.copy(alpha = 0.1f),
    onErrorContainer = Destructive,
    outline = Border,
    outlineVariant = Border.copy(alpha = 0.5f),
    scrim = Color.Black.copy(alpha = 0.32f),
    inverseSurface = Foreground,
    inverseOnSurface = Background,
    inversePrimary = Primary.copy(alpha = 0.8f)
)

// LibreGuard VPN Dark Color Scheme
private val DarkColorScheme = darkColorScheme(
    primary = DarkColors.Primary,
    onPrimary = DarkColors.PrimaryForeground,
    primaryContainer = DarkColors.Primary.copy(alpha = 0.2f),
    onPrimaryContainer = DarkColors.Primary,
    secondary = DarkColors.Secondary,
    onSecondary = DarkColors.SecondaryForeground,
    secondaryContainer = DarkColors.Secondary,
    onSecondaryContainer = DarkColors.SecondaryForeground,
    tertiary = DarkColors.Accent,
    onTertiary = DarkColors.AccentForeground,
    tertiaryContainer = DarkColors.Accent.copy(alpha = 0.2f),
    onTertiaryContainer = DarkColors.Accent,
    background = DarkColors.Background,
    onBackground = DarkColors.Foreground,
    surface = DarkColors.Background,
    onSurface = DarkColors.Foreground,
    surfaceVariant = DarkColors.CardBackground,
    onSurfaceVariant = DarkColors.CardForeground,
    surfaceTint = DarkColors.Primary,
    error = DarkColors.Destructive,
    onError = DarkColors.DestructiveForeground,
    errorContainer = DarkColors.Destructive.copy(alpha = 0.2f),
    onErrorContainer = DarkColors.DestructiveForeground,
    outline = DarkColors.Border,
    outlineVariant = DarkColors.Border.copy(alpha = 0.5f),
    scrim = Color.Black.copy(alpha = 0.5f),
    inverseSurface = DarkColors.Foreground,
    inverseOnSurface = DarkColors.Background,
    inversePrimary = DarkColors.Primary.copy(alpha = 0.8f)
)

@Composable
fun LibreGuardVPNTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is disabled to use our custom design system
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    // Update status bar color
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

// Custom color extensions for VPN-specific UI elements
object LibreGuardColors {
    val statusConnected = StatusConnected
    val statusConnecting = StatusConnecting
    val statusDisconnected = StatusDisconnected
    val cardBackground = CardBackground
    val cardForeground = CardForeground
    val muted = Muted
    val mutedForeground = MutedForeground
    val border = Border
    val primary = Primary
    val primaryForeground = PrimaryForeground
    val destructive = Destructive
    val destructiveForeground = DestructiveForeground
}
