package com.phasewake.app.reminder

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.phasewake.app.R
import com.phasewake.app.core.NightHistory
import com.phasewake.app.core.WeeklySummary
import com.phasewake.app.phoneApp
import com.phasewake.app.ring.BackupAlarm
import com.phasewake.app.ui.MainActivity
import com.phasewake.app.ui.history.weeklyNoteLine
import com.phasewake.app.ui.history.weeklyText
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Wochenrückblick: am Sonntag um 18:00 eine Mitteilung über die letzten sieben Nächte (Schlaf,
 * Schlafziel, Regelmässigkeit, Befinden, deutlichste Notiz). Ungenauer Alarm (bis 10 Min später)
 * genügt. Antippen öffnet den Verlauf. Neu geplant mit dem Zweitwecker, beim Umschalten und nach dem Zeigen.
 */
class WeeklyReminder(private val context: Context, private val now: () -> Instant = Instant::now) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val mutex = Mutex()

    suspend fun reschedule() = mutex.withLock {
        alarmManager.cancel(pending())
        val app = context.phoneApp
        if (!app.settings.weeklyReview.first()) return@withLock
        val from = maxOf(now(), Instant.ofEpochMilli(app.settings.weeklyShownAt()))
        val at = WeeklySummary.nextNotification(from, ZoneId.systemDefault())
        alarmManager.setWindow(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), WINDOW.toMillis(), pending(at))
    }

    internal suspend fun fired(intent: Intent) {
        val app = context.phoneApp
        val at = intent.getLongExtra(EXTRA_AT, 0L)
        app.settings.setWeeklyShownAt(maxOf(at, now().toEpochMilli()))
        if (app.settings.weeklyReview.first()) show()
        reschedule()
    }

    private suspend fun show() {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        val app = context.phoneApp
        val zone = ZoneId.systemDefault()
        val sleeps = app.health.recentSleep(NightHistory.MAX_NIGHTS + 1L)
        val summary = WeeklySummary.of(
            app.nights.load(),
            sleeps,
            app.notes.current(),
            zone,
            app.settings.sleepGoalNow(),
            WeeklySummary.weekEnd(now().atZone(zone).toLocalDate()),
        )
        if (!summary.hasData) return
        val custom = app.notes.custom.first()
        val text = listOfNotNull(weeklyText(context, summary), weeklyNoteLine(context, summary, custom)).joinToString("\n")
        val open = PendingIntent.getActivity(
            context, REQUEST_OPEN,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_HISTORY),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, BedtimeReminder.CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_moon)
            .setContentTitle(context.getString(R.string.weekly_notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun pending(at: Instant? = null): PendingIntent {
        val intent = Intent(context, Receiver::class.java).setAction(ACTION)
        if (at != null) intent.putExtra(EXTRA_AT, at.toEpochMilli())
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Zeigt den Rückblick. Nicht exportiert: nur der eigene Alarm. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION || !BackupAlarm.isUnlocked(context)) return
            val pending = goAsync()
            val app = context.phoneApp
            app.appScope.launch {
                try {
                    app.weekly.fired(intent)
                } finally {
                    pending.finish()
                }
            }
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 6
        private const val REQUEST_CODE = 7
        private const val REQUEST_OPEN = 7
        private const val ACTION = "com.phasewake.app.action.WEEKLY_REVIEW"
        private const val EXTRA_AT = "at"

        /** Ungenauer Alarm: zwischen 18:00 und 18:10. */
        private val WINDOW: Duration = Duration.ofMinutes(10)
    }
}
