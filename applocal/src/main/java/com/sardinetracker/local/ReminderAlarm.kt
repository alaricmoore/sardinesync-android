package com.sardinetracker.local

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Exact alarm for the next medication dose. WorkManager's 15-minute periodic
 * job (worse under Doze) is fine for log reminders but not for "take your
 * HCQ at 9:00" — that one gets AlarmManager exactness, the mechanism alarm
 *-clock and medication apps are explicitly allowed to use (USE_EXACT_ALARM).
 * The alarm doesn't survive reboot; the periodic worker re-arms it within
 * one cycle, which is an acceptable worst case.
 */
object ReminderAlarm {

    fun armIfNeeded(context: Context, epochMs: Long) {
        if (epochMs <= System.currentTimeMillis()) return
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, ReminderAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, epochMs, pi)
        } else {
            // Exact alarms revoked in system settings — degrade to inexact.
            am.set(AlarmManager.RTC_WAKEUP, epochMs, pi)
        }
    }
}

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        android.util.Log.i("ReminderAlarm", "alarm fired — running notification checks")
        // Delegate to the same worker the periodic schedule uses: it posts
        // the due dose (75-min lookback covers it) and re-arms for the next.
        NotificationWorker.runNow(context)
    }
}
