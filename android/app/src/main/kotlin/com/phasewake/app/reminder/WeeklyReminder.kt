package com.phasewake.app.reminder

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.phasewake.app.R
import com.phasewake.app.core.CustomNote
import com.phasewake.app.core.NightHistory
import com.phasewake.app.core.NightRecord
import com.phasewake.app.core.SleepSummary
import com.phasewake.app.core.WeeklySummary
import com.phasewake.app.phoneApp
import com.phasewake.app.ring.BackupAlarm
import com.phasewake.app.ui.MainActivity
import com.phasewake.app.ui.history.weeklyNoteLine
import com.phasewake.app.ui.history.weeklyText
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Wochenrückblick: am Sonntag um 18:00 eine Mitteilung über die letzten sieben Nächte (Schlaf,
 * Schlafziel, Regelmässigkeit, Befinden, deutlichste Notiz). Ungenauer Alarm (bis 10 Min später)
 * genügt. Antippen öffnet den Verlauf. Neu geplant mit dem Zweitwecker, beim Umschalten und nach dem Zeigen.
 *
 * Health Connect liest im Hintergrund nur mit eigener Berechtigung, die wir bewusst nicht anfragen.
 * Darum steht der Text wie auf dem iPhone schon vorher fest: [prepare] rechnet ihn im Vordergrund
 * (App offen, neue Nacht, Befinden oder Notizen geändert), der Alarm zeigt nur noch den abgelegten Text.
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
        if (app.settings.weeklyReview.first()) show(at)
        reschedule()
    }

    /**
     * Text der nächsten Mitteilung vorberechnen und ablegen; nur aus der sichtbaren App aufrufen
     * (dort sind die Schlafdaten aus Health Connect lesbar). Ohne Rückblick nichts.
     */
    suspend fun prepare(
        nights: List<NightRecord>,
        sleeps: List<SleepSummary>,
        notes: Map<LocalDate, List<String>>,
        goalMinutes: Int,
        custom: List<CustomNote>,
    ) {
        val app = context.phoneApp
        if (!app.settings.weeklyReview.first()) return
        val zone = ZoneId.systemDefault()
        val at = WeeklySummary.nextNotification(maxOf(now(), Instant.ofEpochMilli(app.settings.weeklyShownAt())), zone)
        val weekEnd = WeeklySummary.weekEnd(at.atZone(zone).toLocalDate())
        val summary = WeeklySummary.of(nights, sleeps, notes, zone, goalMinutes, weekEnd)
        app.settings.setWeeklyPrepared(weekEnd, textOf(summary, custom).orEmpty())
    }

    /** Der Rückblick als Text; `null`, wenn es nichts zu berichten gibt. */
    private fun textOf(summary: WeeklySummary, custom: List<CustomNote>): String? =
        if (!summary.hasData) null
        else listOfNotNull(weeklyText(context, summary), weeklyNoteLine(context, summary, custom)).joinToString("\n")

    private suspend fun show(atMillis: Long) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        val app = context.phoneApp
        val zone = ZoneId.systemDefault()
        val day = Instant.ofEpochMilli(atMillis.takeIf { it > 0L } ?: now().toEpochMilli()).atZone(zone).toLocalDate()
        val weekEnd = WeeklySummary.weekEnd(day)
        // Vorberechnet für diese Woche: so zeigen (leer = nichts zu berichten). Sonst – App die ganze Woche
        // nicht geöffnet – jetzt rechnen; dann meist ohne Schlafdaten, nur mit dem Befinden.
        val prepared = app.settings.weeklyPrepared()?.takeIf { it.first == weekEnd }?.second
        val text = prepared ?: textOf(
            WeeklySummary.of(app.nights.load(), app.health.recentSleep(NightHistory.MAX_NIGHTS + 1L, app.settings.historyClearedAt.first()), app.notes.current(), zone, app.settings.sleepGoalNow(), weekEnd),
            app.notes.custom.first(),
        )
        if (text.isNullOrEmpty()) return
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
