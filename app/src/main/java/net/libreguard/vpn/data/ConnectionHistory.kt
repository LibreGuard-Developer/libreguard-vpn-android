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
        get() = if (dataUsedMB >= 1024) {
            String.format(Locale.US, "%.2f GB", dataUsedMB / 1024)
        } else {
            String.format(Locale.US, "%.0f MB", dataUsedMB)
        }

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

/**
 * Manager for persisting and retrieving connection history
 */
class ConnectionHistoryManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("connection_history", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val maxRecords = 50

    fun getHistory(): List<ConnectionRecord> {
        val json = prefs.getString("records", null) ?: return emptyList()
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
        prefs.edit().putString("records", json).apply()
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
}

data class DailyUsage(
    val day: String,
    val download: Double, // MB
    val upload: Double, // MB
    val duration: Int // minutes
)

