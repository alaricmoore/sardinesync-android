package com.sardinetracker.local

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Delivers the tracker's queued notifications as real Android notifications.
 * Channels map to the server's notification kinds so the user can tune each
 * (e.g. meds stay loud, log reminders go quiet) in system settings.
 */
object Notifier {
    private const val CH_MEDS = "medications"
    private const val CH_ALERTS = "flare_alerts"
    private const val CH_REMINDERS = "reminders"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_MEDS, "Medication reminders", NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, "Flare risk alerts", NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_REMINDERS, "Log & data reminders", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    /** Channel by the server's ntfy-style tags (see _send_ntfy* in app.py). */
    private fun channelFor(tags: String): String = when {
        tags.contains("pill") -> CH_MEDS
        tags.contains("rotating_light") || tags.contains("warning") -> CH_ALERTS
        else -> CH_REMINDERS
    }

    fun post(context: Context, id: Int, title: String, message: String, tags: String) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        val tapIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, channelFor(tags))
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and the post — drop.
        }
    }
}
