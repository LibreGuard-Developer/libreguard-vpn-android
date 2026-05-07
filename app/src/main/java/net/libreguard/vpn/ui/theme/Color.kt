package net.libreguard.vpn.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// LibreGuard VPN Design System Colors
// Based on the updated design-reference tokens

// Light palette
val LightPrimary = Color(0xFF1570EF)
val LightPrimaryForeground = Color(0xFFFFFFFF)
val LightBackground = Color(0xFFFFFFFF)
val LightForeground = Color(0xFF1A1A1A)
val LightCardBackground = Color(0xFFF8FAFC)
val LightCardForeground = Color(0xFF1A1A1A)
val LightSecondary = Color(0xFFE2E8F0)
val LightSecondaryForeground = Color(0xFF1A1A1A)
val LightMuted = Color(0xFFF1F5F9)
val LightMutedForeground = Color(0xFF64748B)
val LightAccent = Color(0xFF1570EF)
val LightAccentForeground = Color(0xFFFFFFFF)
val LightDestructive = Color(0xFFEF4444)
val LightDestructiveForeground = Color(0xFFFFFFFF)
val LightBorder = Color(0x261570EF)
val LightInputBackground = Color(0xFFFFFFFF)
val LightSwitchBackground = Color(0xFFCBD5E1)
val LightRing = Color(0xFF1570EF)
val LightStatusConnected = Color(0xFF10B981)
val LightStatusConnecting = Color(0xFFF59E0B)
val LightStatusDisconnected = Color(0xFF94A3B8)

// Dark palette
val DarkPrimaryBase = Color(0xFF1570EF)
val DarkPrimaryForegroundBase = Color(0xFFFFFFFF)
val DarkBackgroundBase = Color(0xFF0A0A0A)
val DarkForegroundBase = Color(0xFFF5F5F5)
val DarkCardBackgroundBase = Color(0xFF1A1A1A)
val DarkCardForegroundBase = Color(0xFFF5F5F5)
val DarkSecondaryBase = Color(0xFF2A2A2A)
val DarkSecondaryForegroundBase = Color(0xFFF5F5F5)
val DarkMutedBase = Color(0xFF262626)
val DarkMutedForegroundBase = Color(0xFF94A3B8)
val DarkAccentBase = Color(0xFF1570EF)
val DarkAccentForegroundBase = Color(0xFFFFFFFF)
val DarkDestructiveBase = Color(0xFFEF4444)
val DarkDestructiveForegroundBase = Color(0xFFFFFFFF)
val DarkBorderBase = Color(0x331570EF)
val DarkInputBackgroundBase = Color(0xFF1A1A1A)
val DarkSwitchBackgroundBase = Color(0xFF3A3A3A)
val DarkRingBase = Color(0xFF1570EF)
val DarkStatusConnectedBase = Color(0xFF10B981)
val DarkStatusConnectingBase = Color(0xFFF59E0B)
val DarkStatusDisconnectedBase = Color(0xFF64748B)

@Immutable
data class LibreGuardExtendedColors(
    val muted: Color,
    val mutedForeground: Color,
    val cardBackground: Color,
    val cardForeground: Color,
    val border: Color,
    val inputBackground: Color,
    val switchBackground: Color,
    val ring: Color,
    val statusConnected: Color,
    val statusConnecting: Color,
    val statusDisconnected: Color,
    val isDark: Boolean
)

val LightExtendedColors = LibreGuardExtendedColors(
    muted = LightMuted,
    mutedForeground = LightMutedForeground,
    cardBackground = LightCardBackground,
    cardForeground = LightCardForeground,
    border = LightBorder,
    inputBackground = LightInputBackground,
    switchBackground = LightSwitchBackground,
    ring = LightRing,
    statusConnected = LightStatusConnected,
    statusConnecting = LightStatusConnecting,
    statusDisconnected = LightStatusDisconnected,
    isDark = false
)

val DarkExtendedColors = LibreGuardExtendedColors(
    muted = DarkMutedBase,
    mutedForeground = DarkMutedForegroundBase,
    cardBackground = DarkCardBackgroundBase,
    cardForeground = DarkCardForegroundBase,
    border = DarkBorderBase,
    inputBackground = DarkInputBackgroundBase,
    switchBackground = DarkSwitchBackgroundBase,
    ring = DarkRingBase,
    statusConnected = DarkStatusConnectedBase,
    statusConnecting = DarkStatusConnectingBase,
    statusDisconnected = DarkStatusDisconnectedBase,
    isDark = true
)

val LocalLibreGuardExtendedColors = staticCompositionLocalOf { LightExtendedColors }

val Primary: Color
    @Composable get() = MaterialTheme.colorScheme.primary

val PrimaryForeground: Color
    @Composable get() = MaterialTheme.colorScheme.onPrimary

val Background: Color
    @Composable get() = MaterialTheme.colorScheme.background

val Foreground: Color
    @Composable get() = MaterialTheme.colorScheme.onBackground

val CardBackground: Color
    @Composable get() = LocalLibreGuardExtendedColors.current.cardBackground

val CardForeground: Color
    @Composable get() = LocalLibreGuardExtendedColors.current.cardForeground

val Secondary: Color
    @Composable get() = MaterialTheme.colorScheme.secondary

val SecondaryForeground: Color
    @Composable get() = MaterialTheme.colorScheme.onSecondary

val Muted: Color
    @Composable get() = LocalLibreGuardExtendedColors.current.muted

val MutedForeground: Color
    @Composable get() = LocalLibreGuardExtendedColors.current.mutedForeground

val Accent: Color
    @Composable get() = MaterialTheme.colorScheme.tertiary

val AccentForeground: Color
    @Composable get() = MaterialTheme.colorScheme.onTertiary

val Destructive: Color
    @Composable get() = MaterialTheme.colorScheme.error

val DestructiveForeground: Color
    @Composable get() = MaterialTheme.colorScheme.onError

val Border: Color
    @Composable get() = LocalLibreGuardExtendedColors.current.border

val InputBackground: Color
    @Composable get() = LocalLibreGuardExtendedColors.current.inputBackground

val SwitchBackground: Color
    @Composable get() = LocalLibreGuardExtendedColors.current.switchBackground

val Ring: Color
    @Composable get() = LocalLibreGuardExtendedColors.current.ring

val StatusConnected: Color
    @Composable get() = LocalLibreGuardExtendedColors.current.statusConnected

val StatusConnecting: Color
    @Composable get() = LocalLibreGuardExtendedColors.current.statusConnecting

val StatusDisconnected: Color
    @Composable get() = LocalLibreGuardExtendedColors.current.statusDisconnected

val IsDarkMode: Boolean
    @Composable get() = LocalLibreGuardExtendedColors.current.isDark

// Chart Colors
val Chart1 = Color(0xFFE97451)
val Chart2 = Color(0xFF5BA3A8)
val Chart3 = Color(0xFF4A6FA5)
val Chart4 = Color(0xFFD4A72C)
val Chart5 = Color(0xFFD68F2E)

// Legacy Colors (kept for backward compatibility)
val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)
val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)
