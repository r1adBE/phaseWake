package com.phasewake.app.reminder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.phasewake.app.R
import com.phasewake.app.SettingsRepository
import com.phasewake.app.core.Bedtime
import com.phasewake.app.phoneApp
import com.phasewake.app.ring.BackupAlarm
import com.phasewake.app.shared.AlarmRepository
import com.phasewake.app.ui.Format
import com.phasewake.app.ui.MainActivity
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Bettzeit-Erinnerung: [Bedtime.LEAD_MINUTES] Minuten vor der Bettzeit (Zielzeit des nächsten Weckers
 * minus Schlafziel) eine Mitteilung. Ungenauer Alarm (±5 Min) genügt; kein Recht für exakte Alarme nötig.
 * Neu geplant mit dem Zweitwecker (Wecker geändert, Neustart, Zeit/Zeitzone), bei Änderung von Ziel
 * oder Einstellung und nach dem Zeigen.
 */
class BedtimeReminder(
    private val context: Context,
    private val alarms: AlarmRepository,
    private val settings: SettingsRepository,
    private val now: () -> Instant = Instant::now,
) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val mutex = Mutex()

    suspend fun reschedule() = mutex.withLock {
        alarmManager.cancel(pending())
        if (!settings.bedtimeReminder.first()) return@withLock
        val current = now()
        // Nach einer gezeigten Erinnerung (ungenau, evtl. bis 5 Min zu früh) erst die nächste Nacht.
        val from = maxOf(current, Instant.ofEpochMilli(settings.bedtimeShownAt()))
        val holidays = context.phoneApp.holidays.store.current()
        val reminder = Bedtime.next(alarms.current().alarms, from, ZoneId.systemDefault(), settings.sleepGoalNow(), holidays = holidays) ?: return@withLock
        val start = maxOf(reminder.at.minus(TOLERANCE), current)
        alarmManager.setWindow(AlarmManager.RTC_WAKEUP, start.toEpochMilli(), TOLERANCE.multipliedBy(2).toMillis(), pending(reminder))
    }

    /** Der Alarm ist da: merken, zeigen (wenn die Bettzeit nicht schon vorbei ist) und die nächste Nacht planen. */
    internal suspend fun fired(intent: Intent) {
        val at = intent.getLongExtra(EXTRA_AT, 0L)
        val bedtime = Instant.ofEpochMilli(intent.getLongExtra(EXTRA_BEDTIME, 0L))
        val target = Instant.ofEpochMilli(intent.getLongExtra(EXTRA_TARGET, 0L))
        settings.setBedtimeShownAt(at)
        if (settings.bedtimeReminder.first() && now().isBefore(bedtime)) show(bedtime, target)
        reschedule()
    }

    private suspend fun show(bedtime: Instant, target: Instant) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        val goal = Duration.ofMinutes(settings.sleepGoalNow().toLong())
        val text = context.getString(
            R.string.bedtime_notification,
            Format.time(bedtime),
            Format.duration(context, goal),
            Format.time(target),
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_moon)
            .setContentTitle(context.getString(R.string.bedtime_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            // Spätestens zur Zielzeit ist die Erinnerung überholt.
            .setTimeoutAfter(Duration.between(now(), target).toMillis().coerceAtLeast(1L))
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun pending(reminder: Bedtime.Reminder? = null): PendingIntent {
        val intent = Intent(context, Receiver::class.java).setAction(ACTION)
        if (reminder != null) {
            intent.putExtra(EXTRA_AT, reminder.at.toEpochMilli())
                .putExtra(EXTRA_BEDTIME, reminder.bedtime.toEpochMilli())
                .putExtra(EXTRA_TARGET, reminder.target.toEpochMilli())
        }
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Zeigt die Erinnerung. Nicht exportiert: nur der eigene Alarm. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION || !BackupAlarm.isUnlocked(context)) return
            val pending = goAsync()
            val app = context.phoneApp
            app.appScope.launch {
                try {
                    app.bedtime.fired(intent)
                } finally {
                    pending.finish()
                }
            }
        }
    }

    companion object {
        const val CHANNEL = "reminders"
        private const val NOTIFICATION_ID = 3
        private const val REQUEST_CODE = 6
        private const val ACTION = "com.phasewake.app.action.BEDTIME_REMINDER"
        private const val EXTRA_AT = "at"
        private const val EXTRA_BEDTIME = "bedtime"
        private const val EXTRA_TARGET = "target"

        /** Ungenauer Alarm: bis zu so viel früher oder später. */
        private val TOLERANCE: Duration = Duration.ofMinutes(5)

        fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }
}
