package net.libreguard.vpn.ui.theme

import androidx.compose.ui.graphics.Color

// LibreGuard VPN Design System Colors
// Based on the new design system from Libreguardvpnappui

// Primary Colors
val Primary = Color(0xFF1570EF)  // Striking blue from logo
val PrimaryForeground = Color(0xFFFFFFFF)

// Background Colors
val Background = Color(0xFFFFFFFF)  // Pure white background
val Foreground = Color(0xFF1A1A1A)  // Dark text

// Card Colors (using CardBackground to avoid conflict with Compose's Card composable)
val CardBackground = Color(0xFFF8FAFC)  // Very light blue-gray for cards
val CardForeground = Color(0xFF1A1A1A)

// Secondary Colors
val Secondary = Color(0xFFE2E8F0)  // Light gray-blue
val SecondaryForeground = Color(0xFF1A1A1A)

// Muted Colors
val Muted = Color(0xFFF1F5F9)
val MutedForeground = Color(0xFF64748B)

// Accent Colors
val Accent = Color(0xFF1570EF)
val AccentForeground = Color(0xFFFFFFFF)

// Destructive Colors
val Destructive = Color(0xFFEF4444)
val DestructiveForeground = Color(0xFFFFFFFF)

// Border Colors
val Border = Color(0x261570EF)  // Light blue border with ~15% opacity

// Input Colors
val InputBackground = Color(0xFFFFFFFF)
val SwitchBackground = Color(0xFFCBD5E1)

// Ring (Focus) Color
val Ring = Color(0xFF1570EF)

// VPN Status Colors
val StatusConnected = Color(0xFF10B981)    // Green for connected
val StatusConnecting = Color(0xFFF59E0B)   // Amber for connecting
val StatusDisconnected = Color(0xFF94A3B8) // Slate for disconnected

// Chart Colors
val Chart1 = Color(0xFFE97451)  // Orange-red
val Chart2 = Color(0xFF5BA3A8)  // Teal
val Chart3 = Color(0xFF4A6FA5)  // Blue-gray
val Chart4 = Color(0xFFD4A72C)  // Gold
val Chart5 = Color(0xFFD68F2E)  // Amber

// Dark Mode Colors
object DarkColors {
    val Background = Color(0xFF0A0A0A)
    val Foreground = Color(0xFFF5F5F5)
    val CardBackground = Color(0xFF0A0A0A)
    val CardForeground = Color(0xFFF5F5F5)
    val Primary = Color(0xFFF5F5F5)
    val PrimaryForeground = Color(0xFF1A1A1A)
    val Secondary = Color(0xFF262626)
    val SecondaryForeground = Color(0xFFF5F5F5)
    val Muted = Color(0xFF262626)
    val MutedForeground = Color(0xFF737373)
    val Accent = Color(0xFF262626)
    val AccentForeground = Color(0xFFF5F5F5)
    val Destructive = Color(0xFF7F1D1D)
    val DestructiveForeground = Color(0xFFFCA5A5)
    val Border = Color(0xFF262626)
    val Ring = Color(0xFF525252)
}

// Legacy Colors (kept for backward compatibility)
val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)
val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)
