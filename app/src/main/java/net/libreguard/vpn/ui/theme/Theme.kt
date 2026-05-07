package net.libreguard.vpn.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// LibreGuard VPN Light Color Scheme
private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightPrimaryForeground,
    primaryContainer = LightPrimary.copy(alpha = 0.12f),
    onPrimaryContainer = LightPrimary,
    secondary = LightSecondary,
    onSecondary = LightSecondaryForeground,
    secondaryContainer = LightSecondary,
    onSecondaryContainer = LightSecondaryForeground,
    tertiary = LightAccent,
    onTertiary = LightAccentForeground,
    tertiaryContainer = LightAccent.copy(alpha = 0.12f),
    onTertiaryContainer = LightAccent,
    background = LightBackground,
    onBackground = LightForeground,
    surface = LightBackground,
    onSurface = LightForeground,
    surfaceVariant = LightCardBackground,
    onSurfaceVariant = LightCardForeground,
    surfaceTint = LightPrimary,
    error = LightDestructive,
    onError = LightDestructiveForeground,
    errorContainer = LightDestructive.copy(alpha = 0.12f),
    onErrorContainer = LightDestructive,
    outline = LightBorder,
    outlineVariant = LightBorder.copy(alpha = 0.5f),
    scrim = Color.Black.copy(alpha = 0.32f),
    inverseSurface = LightForeground,
    inverseOnSurface = LightBackground,
    inversePrimary = LightPrimary.copy(alpha = 0.8f)
)

// LibreGuard VPN Dark Color Scheme
private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimaryBase,
    onPrimary = DarkPrimaryForegroundBase,
    primaryContainer = DarkPrimaryBase.copy(alpha = 0.2f),
    onPrimaryContainer = DarkPrimaryForegroundBase,
    secondary = DarkSecondaryBase,
    onSecondary = DarkSecondaryForegroundBase,
    secondaryContainer = DarkSecondaryBase,
    onSecondaryContainer = DarkSecondaryForegroundBase,
    tertiary = DarkAccentBase,
    onTertiary = DarkAccentForegroundBase,
    tertiaryContainer = DarkAccentBase.copy(alpha = 0.2f),
    onTertiaryContainer = DarkAccentForegroundBase,
    background = DarkBackgroundBase,
    onBackground = DarkForegroundBase,
    surface = DarkBackgroundBase,
    onSurface = DarkForegroundBase,
    surfaceVariant = DarkCardBackgroundBase,
    onSurfaceVariant = DarkCardForegroundBase,
    surfaceTint = DarkPrimaryBase,
    error = DarkDestructiveBase,
    onError = DarkDestructiveForegroundBase,
    errorContainer = DarkDestructiveBase.copy(alpha = 0.18f),
    onErrorContainer = DarkDestructiveForegroundBase,
    outline = DarkBorderBase,
    outlineVariant = DarkBorderBase.copy(alpha = 0.5f),
    scrim = Color.Black.copy(alpha = 0.5f),
    inverseSurface = DarkForegroundBase,
    inverseOnSurface = DarkBackgroundBase,
    inversePrimary = DarkPrimaryBase.copy(alpha = 0.8f)
)

@Composable
fun LibreGuardVPNTheme(
    darkTheme: Boolean = false,
    // Dynamic color is disabled to use our custom design system
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val extendedColors = if (darkTheme) DarkExtendedColors else LightExtendedColors

    // Update status bar color
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalLibreGuardExtendedColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}

// Custom color extensions for VPN-specific UI elements
object LibreGuardColors {
    val statusConnected: Color
        @Composable get() = StatusConnected
    val statusConnecting: Color
        @Composable get() = StatusConnecting
    val statusDisconnected: Color
        @Composable get() = StatusDisconnected
    val cardBackground: Color
        @Composable get() = CardBackground
    val cardForeground: Color
        @Composable get() = CardForeground
    val muted: Color
        @Composable get() = Muted
    val mutedForeground: Color
        @Composable get() = MutedForeground
    val border: Color
        @Composable get() = Border
    val primary: Color
        @Composable get() = Primary
    val primaryForeground: Color
        @Composable get() = PrimaryForeground
    val destructive: Color
        @Composable get() = Destructive
    val destructiveForeground: Color
        @Composable get() = DestructiveForeground
}
