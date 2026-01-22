package net.libreguard.vpn.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import net.libreguard.vpn.MainActivity

/**
 * VPN Notification Manager
 *
 * Manages system notifications for VPN events (data limit, errors, etc.)
 */
object VpnNotificationManager {
    private const val CHANNEL_ID = "vpn_events_channel"
    private const val CHANNEL_NAME = "VPN Events"
    private const val DATA_LIMIT_NOTIFICATION_ID = 2001
    private const val TAG = "VpnNotificationMgr"

    private fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Log.w(TAG, "Notifications suppressed: POST_NOTIFICATIONS not granted")
                return false
            }
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            Log.w(TAG, "Notifications suppressed: channel disabled by user/system")
            return false
        }
        return true
    }

    /**
     * Create notification channel (required for Android 8.0+)
     */
    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, importance).apply {
                description = "Notifications for VPN events like data limit exceeded"
                setShowBadge(true)
                enableVibration(true)
                enableLights(true)
            }

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Show notification that VPN was disconnected due to data limit exceeded
     */
    fun showDataLimitExceeded(context: Context) {
        if (!canPostNotifications(context)) return
        createNotificationChannel(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning) // Warning icon
            .setContentTitle("⚠️ Data Limit Exceeded")
            .setContentText("VPN disconnected - Monthly data quota reached")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Your monthly free data limit has been reached. The VPN has been disconnected. " +
                        "Upgrade to Pro for unlimited data or wait until next month for quota reset."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setOngoing(false) // Can be dismissed
            .setAutoCancel(true) // Dismiss when tapped
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setVibrate(longArrayOf(0, 250, 250, 250)) // Vibrate pattern
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(DATA_LIMIT_NOTIFICATION_ID, notification)
    }

    /**
     * Show notification for authentication failure
     */
    fun showAuthenticationFailed(context: Context, reason: String = "Certificate invalid or revoked") {
        if (!canPostNotifications(context)) return
        createNotificationChannel(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("❌ VPN Authentication Failed")
            .setContentText("VPN disconnected - $reason")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("VPN authentication failed: $reason. Please try reconnecting or contact support if the issue persists."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(DATA_LIMIT_NOTIFICATION_ID, notification)
    }

    /**
     * Cancel all VPN event notifications
     */
    fun cancelAll(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(DATA_LIMIT_NOTIFICATION_ID)
    }
}
