package net.libreguard.vpn.util

import android.os.Build

/**
 * Utility for generating user-friendly device names from Android device metadata.
 * Uses Build.MANUFACTURER and Build.MODEL to create readable device identifiers.
 */
object DeviceNameGenerator {

    /**
     * Generate a user-friendly device name using device manufacturer, model, and Android version.
     *
     * Examples:
     * - "Samsung Galaxy S21 (Android 14)"
     * - "Google Pixel 6 (Android 13)"
     * - "OnePlus 9 Pro (Android 12)"
     *
     * @return Formatted device name string
     */
    fun generateDeviceName(): String {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase() else it.toString()
        }
        val model = Build.MODEL
        val androidVersion = Build.VERSION.RELEASE

        // Check if model already contains manufacturer to avoid duplication
        // e.g., "Samsung Galaxy S21" already contains "Samsung"
        val deviceName = if (model.contains(manufacturer, ignoreCase = true)) {
            model
        } else {
            "$manufacturer $model"
        }

        return "$deviceName (Android $androidVersion)"
    }

    /**
     * Format device ID to show last 8 characters for user identification.
     * Useful for distinguishing between similar devices.
     *
     * @param deviceId Full device identifier
     * @return Formatted string like "...abc1def2"
     */
    fun formatDeviceIdentifier(deviceId: String): String {
        return if (deviceId.length > 8) {
            "...${deviceId.takeLast(8)}"
        } else {
            deviceId
        }
    }

    /**
     * Get the current device's identifier suffix for comparison.
     *
     * @param deviceId Full device identifier
     * @return Last 8 characters or full string if shorter
     */
    fun getDeviceIdentifierSuffix(deviceId: String): String {
        return deviceId.takeLast(8)
    }
}

