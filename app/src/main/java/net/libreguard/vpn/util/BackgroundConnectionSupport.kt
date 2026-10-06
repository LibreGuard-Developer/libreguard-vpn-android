package net.libreguard.vpn.util

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

internal data class BackgroundConnectionStatus(
    val batteryExempt: Boolean?,
    val exactAlarmAvailable: Boolean?,
    val isXiaomiFamily: Boolean
) {
    fun needsAttention(usesIkev2: Boolean): Boolean =
        batteryExempt != true || (usesIkev2 && exactAlarmAvailable != true) || isXiaomiFamily
}

internal enum class BackgroundSettingsDestination {
    BATTERY_REQUEST, BATTERY_SETTINGS, EXACT_ALARM_REQUEST, APP_DETAILS
}

internal interface BackgroundConnectionPlatform {
    val sdkInt: Int
    val manufacturer: String
    val brand: String
    fun isBatteryExempt(): Boolean
    fun canScheduleExactAlarms(): Boolean
    fun openSettings(destination: BackgroundSettingsDestination): Boolean
}

/** Reads access and opens settings only in response to an explicit user action. */
internal class BackgroundConnectionSupport(private val platform: BackgroundConnectionPlatform) {
    constructor(context: Context) : this(AndroidBackgroundConnectionPlatform(context.applicationContext))
    val sdkInt: Int get() = platform.sdkInt

    fun readStatus() = BackgroundConnectionStatus(
        batteryExempt = runCatching { platform.isBatteryExempt() }.getOrNull(),
        exactAlarmAvailable = if (platform.sdkInt < Build.VERSION_CODES.S) true
            else runCatching { platform.canScheduleExactAlarms() }.getOrNull(),
        isXiaomiFamily = listOf(platform.manufacturer, platform.brand).any {
            it.trim().lowercase(java.util.Locale.ROOT) in setOf("xiaomi", "redmi", "poco")
        }
    )

    fun openBatterySettings(): Boolean = openFirstAvailable(
        BackgroundSettingsDestination.BATTERY_REQUEST,
        BackgroundSettingsDestination.BATTERY_SETTINGS,
        BackgroundSettingsDestination.APP_DETAILS
    )

    fun openExactAlarmSettings(): Boolean = if (platform.sdkInt < Build.VERSION_CODES.S) false
        else openFirstAvailable(
            BackgroundSettingsDestination.EXACT_ALARM_REQUEST,
            BackgroundSettingsDestination.APP_DETAILS
        )

    fun openAppDetails(): Boolean = openFirstAvailable(BackgroundSettingsDestination.APP_DETAILS)

    private fun openFirstAvailable(vararg destinations: BackgroundSettingsDestination): Boolean =
        destinations.any { platform.openSettings(it) }
}

private class AndroidBackgroundConnectionPlatform(private val context: Context) : BackgroundConnectionPlatform {
    override val sdkInt: Int get() = Build.VERSION.SDK_INT
    override val manufacturer: String get() = Build.MANUFACTURER
    override val brand: String get() = Build.BRAND

    override fun isBatteryExempt(): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    override fun canScheduleExactAlarms(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    // A user-requested exemption protects core VPN connectivity under Doze.
    @SuppressLint("BatteryLife")
    override fun openSettings(destination: BackgroundSettingsDestination): Boolean {
        val packageUri = Uri.parse("package:${context.packageName}")
        val intent = when (destination) {
            BackgroundSettingsDestination.BATTERY_REQUEST ->
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri)
            BackgroundSettingsDestination.BATTERY_SETTINGS -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            BackgroundSettingsDestination.EXACT_ALARM_REQUEST ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri)
                } else return false
            BackgroundSettingsDestination.APP_DETAILS -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            // Resolving first can hide otherwise available system activities.
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}

internal interface BackgroundTipStore {
    fun hasBeenShown(): Boolean
    fun markShown()
}

internal class BackgroundConnectionTipPreferences(context: Context) : BackgroundTipStore {
    // Excluded from backup and transfer to keep the tip specific to this installation.
    private val prefs = context.getSharedPreferences("background_connection_prefs", Context.MODE_PRIVATE)
    override fun hasBeenShown() = prefs.getBoolean("tip_shown", false)
    override fun markShown() { prefs.edit().putBoolean("tip_shown", true).apply() }
}

internal fun shouldShowBackgroundTip(
    store: BackgroundTipStore,
    status: BackgroundConnectionStatus,
    usesIkev2: Boolean,
    isForeground: Boolean,
    isConnected: Boolean
): Boolean = isForeground && isConnected && !store.hasBeenShown() && status.needsAttention(usesIkev2)

internal object BackgroundConnectionDiagnostics {
    private var lastSnapshot: String? = null

    @Synchronized
    fun record(sdkInt: Int, protocol: String, status: BackgroundConnectionStatus) {
        // Diagnostics must never interrupt a connection attempt or settings refresh.
        runCatching {
            val battery = status.batteryExempt?.toString() ?: "unknown"
            val exact = status.exactAlarmAvailable?.toString() ?: "unknown"
            val snapshot = "sdk=$sdkInt protocol=$protocol batteryExempt=$battery exactAlarmAvailable=$exact"
            if (snapshot != lastSnapshot) {
                CrashlyticsReporter.setCustomKey("background_sdk", sdkInt)
                CrashlyticsReporter.setCustomKey("background_protocol", protocol)
                CrashlyticsReporter.setCustomKey("background_battery_exempt", battery)
                CrashlyticsReporter.setCustomKey("background_exact_alarm_available", exact)
                CrashlyticsReporter.log("Background connection access: $snapshot")
                Log.i("BackgroundConnection", snapshot)
                lastSnapshot = snapshot
            }
        }
    }
}
