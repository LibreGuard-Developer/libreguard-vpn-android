package net.libreguard.vpn.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.*

/**
 * Data class for storing VPN connection history
 */
data class ConnectionRecord(
    val id: String = UUID.randomUUID().toString(),
    val serverName: String,
    val country: String,
    val connectedAt: Long, // Timestamp when connected
    val disconnectedAt: Long?, // Timestamp when disconnected (null if still connected)
    val dataUsedMB: Double // Data used in MB
) {
    val durationMinutes: Int
        get() = if (disconnectedAt != null) {
            ((disconnectedAt - connectedAt) / 60000).toInt()
        } else {
            ((System.currentTimeMillis() - connectedAt) / 60000).toInt()
        }

    val formattedDuration: String
        get() {
            val mins = durationMinutes
            val hours = mins / 60
            val remainingMins = mins % 60
            return if (hours > 0) "${hours}h ${remainingMins}m" else "${remainingMins}m"
        }

    val formattedData: String
        get() = formatDataAmount(dataUsedMB)

    val timeAgo: String
        get() {
            val now = System.currentTimeMillis()
            val timestamp = disconnectedAt ?: connectedAt
            val diff = now - timestamp
            val minutes = diff / 60000
            val hours = minutes / 60
            val days = hours / 24

            return when {
                minutes < 60 -> "$minutes minutes ago"
                hours < 24 -> if (hours == 1L) "1 hour ago" else "$hours hours ago"
                days == 1L -> "Yesterday"
                days < 7 -> "$days days ago"
                else -> SimpleDateFormat("MMM d", Locale.US).format(Date(timestamp))
            }
        }
}

fun formatDataAmount(dataUsedMB: Double): String {
    val safeAmount = dataUsedMB.coerceAtLeast(0.0)
    return when {
        safeAmount >= 1024 -> {
            val gb = safeAmount / 1024.0
            if (gb >= 10) {
                String.format(Locale.US, "%.0f GB", gb)
            } else {
                String.format(Locale.US, "%.1f GB", gb)
            }
        }
        safeAmount >= 100 -> String.format(Locale.US, "%.0f MB", safeAmount)
        safeAmount >= 1 -> String.format(Locale.US, "%.1f MB", safeAmount)
        safeAmount > 0 -> String.format(Locale.US, "%.2f MB", safeAmount)
        else -> "0 MB"
    }
}

/**
 * Manager for persisting and retrieving connection history
 * Supports per-user data isolation - each user has their own connection history
 */
class ConnectionHistoryManager(private val context: Context) {
    private val gson = Gson()
    private val maxRecords = 50

    // Current user ID for user-specific storage
    private var currentUserId: String? = null

    /**
     * Get user-specific SharedPreferences
     */
    private fun getPrefs(): SharedPreferences {
        val prefsName = if (currentUserId != null) {
            "connection_history_$currentUserId"
        } else {
            "connection_history_default"
        }
        return context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    }

    /**
     * Set the current user ID for user-specific history tracking
     */
    fun setUserId(userId: String) {
        if (currentUserId != userId) {
            currentUserId = userId
            android.util.Log.d("ConnectionHistory", "Switched to user: $userId")
        }
    }

    /**
     * Clear current user context (logout) - preserves persisted data
     */
    fun clearUser() {
        android.util.Log.d("ConnectionHistory", "Cleared user context. Previous user: $currentUserId")
        currentUserId = null
    }

    fun getHistory(): List<ConnectionRecord> {
        val json = getPrefs().getString("records", null) ?: return emptyList()
        val type = object : TypeToken<List<ConnectionRecord>>() {}.type
        return try {
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addRecord(record: ConnectionRecord) {
        val history = getHistory().toMutableList()
        history.add(0, record)
        // Keep only the most recent records
        val trimmed = history.take(maxRecords)
        saveHistory(trimmed)
    }

    fun updateLastRecord(disconnectedAt: Long, dataUsedMB: Double) {
        val history = getHistory().toMutableList()
        if (history.isNotEmpty()) {
            val last = history[0]
            if (last.disconnectedAt == null) {
                history[0] = last.copy(
                    disconnectedAt = disconnectedAt,
                    dataUsedMB = dataUsedMB
                )
                saveHistory(history)
            }
        }
    }

    private fun saveHistory(records: List<ConnectionRecord>) {
        val json = gson.toJson(records)
        getPrefs().edit().putString("records", json).apply()
    }

    fun getRecentConnections(limit: Int = 5): List<ConnectionRecord> {
        return getHistory()
            .filter { it.disconnectedAt != null }
            .take(limit)
    }

    fun getTotalDataThisWeek(): Double {
        val weekAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
        return getHistory()
            .filter { (it.disconnectedAt ?: it.connectedAt) >= weekAgo }
            .sumOf { it.dataUsedMB }
    }

    fun getTotalDurationThisWeek(): Int {
        val weekAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
        return getHistory()
            .filter { (it.disconnectedAt ?: it.connectedAt) >= weekAgo }
            .sumOf { it.durationMinutes }
    }

    fun getMostActiveDay(): String {
        val calendar = Calendar.getInstance()
        val dayUsage = mutableMapOf<Int, Double>()

        getHistory().forEach { record ->
            calendar.timeInMillis = record.connectedAt
            val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
            dayUsage[dayOfWeek] = (dayUsage[dayOfWeek] ?: 0.0) + record.dataUsedMB
        }

        val maxDay = dayUsage.maxByOrNull { it.value }?.key ?: return "N/A"
        return when (maxDay) {
            Calendar.SUNDAY -> "Sunday"
            Calendar.MONDAY -> "Monday"
            Calendar.TUESDAY -> "Tuesday"
            Calendar.WEDNESDAY -> "Wednesday"
            Calendar.THURSDAY -> "Thursday"
            Calendar.FRIDAY -> "Friday"
            Calendar.SATURDAY -> "Saturday"
            else -> "N/A"
        }
    }

    fun getDailyStats(): List<DailyUsage> {
        val calendar = Calendar.getInstance()
        val dayFormat = SimpleDateFormat("EEE", Locale.US)
        val dailyStats = mutableMapOf<String, DailyUsage>()

        // Initialize last 7 days
        for (i in 6 downTo 0) {
            calendar.timeInMillis = System.currentTimeMillis() - (i * 24 * 60 * 60 * 1000L)
            val dayKey = if (i == 0) "Today" else dayFormat.format(calendar.time)
            dailyStats[dayKey] = DailyUsage(dayKey, 0.0, 0.0, 0)
        }

        getHistory().forEach { record ->
            calendar.timeInMillis = record.connectedAt
            val daysAgo = ((System.currentTimeMillis() - record.connectedAt) / (24 * 60 * 60 * 1000L)).toInt()

            if (daysAgo < 7) {
                val dayKey = if (daysAgo == 0) "Today" else dayFormat.format(calendar.time)
                val existing = dailyStats[dayKey] ?: DailyUsage(dayKey, 0.0, 0.0, 0)
                // Assume 80% download, 20% upload for simplicity
                dailyStats[dayKey] = existing.copy(
                    download = existing.download + (record.dataUsedMB * 0.8),
                    upload = existing.upload + (record.dataUsedMB * 0.2),
                    duration = existing.duration + record.durationMinutes
                )
            }
        }

        return dailyStats.values.toList()
    }

    fun getTotalDataThisMonth(): Double {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.DAY_OF_MONTH, 1)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val monthStart = calendar.timeInMillis

        return getHistory()
            .filter { (it.disconnectedAt ?: it.connectedAt) >= monthStart }
            .sumOf { it.dataUsedMB }
    }

    fun getTotalDurationThisMonth(): Int {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.DAY_OF_MONTH, 1)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val monthStart = calendar.timeInMillis

        return getHistory()
            .filter { (it.disconnectedAt ?: it.connectedAt) >= monthStart }
            .sumOf { it.durationMinutes }
    }

    fun getDailyStatsForMonth(): List<DailyUsage> {
        val calendar = Calendar.getInstance()
        val dayFormat = SimpleDateFormat("MMM d", Locale.US)
        val dailyStats = mutableMapOf<String, DailyUsage>()

        // Get current month's day count
        val currentDay = calendar.get(Calendar.DAY_OF_MONTH)

        // Initialize all days of the month up to today
        calendar.set(Calendar.DAY_OF_MONTH, 1)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)

        for (i in 0 until currentDay) {
            val dayKey = if (i == currentDay - 1) "Today" else dayFormat.format(calendar.time)
            dailyStats[dayKey] = DailyUsage(dayKey, 0.0, 0.0, 0)
            calendar.add(Calendar.DAY_OF_MONTH, 1)
        }

        // Aggregate data for this month
        val monthStart = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        getHistory().forEach { record ->
            if (record.connectedAt >= monthStart) {
                calendar.timeInMillis = record.connectedAt
                val day = calendar.get(Calendar.DAY_OF_MONTH)
                val isToday = day == currentDay
                val dayKey = if (isToday) "Today" else dayFormat.format(calendar.time)

                val existing = dailyStats[dayKey] ?: DailyUsage(dayKey, 0.0, 0.0, 0)
                // Assume 80% download, 20% upload for simplicity
                dailyStats[dayKey] = existing.copy(
                    download = existing.download + (record.dataUsedMB * 0.8),
                    upload = existing.upload + (record.dataUsedMB * 0.2),
                    duration = existing.duration + record.durationMinutes
                )
            }
        }

        return dailyStats.values.toList()
    }

    fun getPeakUsageTime(): String {
        val hourUsage = mutableMapOf<Int, Double>()
        val calendar = Calendar.getInstance()

        getHistory().forEach { record ->
            calendar.timeInMillis = record.connectedAt
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            hourUsage[hour] = (hourUsage[hour] ?: 0.0) + record.dataUsedMB
        }

        if (hourUsage.isEmpty()) return "N/A"

        val peakHour = hourUsage.maxByOrNull { it.value }?.key ?: return "N/A"

        // Group into time periods and show range
        return when (peakHour) {
            in 6..11 -> {
                val range = findPeakRange(hourUsage, 6, 11)
                "Morning ($range)"
            }
            in 12..17 -> {
                val range = findPeakRange(hourUsage, 12, 17)
                "Afternoon ($range)"
            }
            in 18..21 -> {
                val range = findPeakRange(hourUsage, 18, 21)
                "Evening ($range)"
            }
            else -> {
                val range = findPeakRange(hourUsage, 22, 5)
                "Night ($range)"
            }
        }
    }

    private fun findPeakRange(hourUsage: Map<Int, Double>, startHour: Int, endHour: Int): String {
        val range = if (startHour <= endHour) {
            (startHour..endHour)
        } else {
            // Wrap around midnight
            (startHour..23).toList() + (0..endHour).toList()
        }

        val rangeUsage = range.associateWith { hourUsage[it] ?: 0.0 }
        val peakHour = rangeUsage.maxByOrNull { it.value }?.key ?: startHour

        val peakStart = peakHour
        val peakEnd = (peakHour + 1) % 24

        return "${formatHour(peakStart)}-${formatHour(peakEnd)}"
    }

    private fun formatHour(hour: Int): String {
        return when {
            hour == 0 -> "12 AM"
            hour < 12 -> "$hour AM"
            hour == 12 -> "12 PM"
            else -> "${hour - 12} PM"
        }
    }
}

data class DailyUsage(
    val day: String,
    val download: Double, // MB
    val upload: Double, // MB
    val duration: Int // minutes
)

