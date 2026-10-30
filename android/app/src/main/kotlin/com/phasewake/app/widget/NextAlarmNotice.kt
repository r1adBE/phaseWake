package com.phasewake.app.widget

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.phasewake.app.R
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.Bedtime
import com.phasewake.app.core.WakePlan
import com.phasewake.app.phoneApp
import com.phasewake.app.ring.BackupAlarm
import com.phasewake.app.ui.MainActivity
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Leise Mitteilung «Nächster Wecker» auf dem Sperrbildschirm: Weckfenster und Nacht-Check wie im Widget
 * [NextAlarmWidget], für den nächsten gespeicherten Wecker (ohne Probewecker und Powernap; Überspringen,
 * Feiertage und erledigte Nächte wie beim Widget).
 *
 * Sichtbar ab der Bettzeit-Erinnerung ([Bedtime.reminderAt]), ist diese aus ab dem früheren von 22:00 am Vorabend
 * und diesem Zeitpunkt, jedoch höchstens [MAX_AHEAD] vor der Zielzeit. Verschwindet, sobald die Nacht erledigt
 * ist (geweckt, gestoppt), der Wecker aus, gelöscht oder übersprungen ist, spätestens [KEEP_AFTER] nach der
 * Zielzeit. Neu geplant mit dem Zweitwecker (Wecker geändert, Neustart, Zeit/Zeitzone) und bei jedem Nacht-Check;
 * dazwischen weckt ein eigener, ungenauer Alarm (zum Zeigen und zum Wegräumen).
 */
class NextAlarmNotice(private val context: Context, private val now: () -> Instant = Instant::now) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val mutex = Mutex()

    /** Zeigt, entfernt oder plant die Mitteilung für den aktuellen Stand. */
    suspend fun reschedule() = mutex.withLock {
        alarmManager.cancel(pending())
        val app = context.phoneApp
        val current = now()
        val zone = ZoneId.systemDefault()
        val plan = if (app.settings.nextAlarmNotice.first()) {
            AlarmSchedule.earliestPlan(app.alarms.current().alarms, current, zone, app.holidays.store.current(), app.backup.doneUntil())
        } else {
            null
        }
        if (plan == null) {
            manager.cancel(NOTIFICATION_ID)
            return@withLock
        }
        val from = showFrom(plan.target, zone, app.settings.sleepGoalNow(), app.settings.bedtimeReminder.first())
        if (from.isAfter(current)) {
            manager.cancel(NOTIFICATION_ID)
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, from.toEpochMilli(), pending())
        } else {
            show(plan, zone, current)
            // Danach aufräumen und die nächste Nacht planen, auch wenn sonst nichts neu plant.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, plan.target.plus(KEEP_AFTER).toEpochMilli(), pending())
        }
    }

    private fun show(plan: WakePlan, zone: ZoneId, current: Instant) {
        if (!manager.areNotificationsEnabled()) return
        val text = NextAlarmWidget.checkText(context) ?: NextAlarmWidget.day(context, plan.target, zone)
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_moon)
            .setContentTitle(NextAlarmWidget.windowText(context, plan, zone))
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            // Nur auf dem Handy: Die Uhr zeigt ihren Wecker selbst.
            .setLocalOnly(true)
            .setContentIntent(open)
            .setTimeoutAfter(Duration.between(current, plan.target.plus(KEEP_AFTER)).toMillis().coerceAtLeast(1L))
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun pending(): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_CODE, Intent(context, Receiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Eigener Alarm: zeigen oder wegräumen. Nicht exportiert: nur der eigene Alarm. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION || !BackupAlarm.isUnlocked(context)) return
            val pending = goAsync()
            val app = context.phoneApp
            app.appScope.launch {
                try {
                    app.nextAlarm.reschedule()
                } finally {
                    pending.finish()
                }
            }
        }
    }

    companion object {
        /**
         * Kanal mit normaler Wichtigkeit, aber ohne Ton: Android und One UI blenden Mitteilungen niedriger Wichtigkeit
         * («lautlos») auf dem Sperrbildschirm standardmässig aus. Die Wichtigkeit eines Kanals lässt sich nicht
         * nachträglich anheben, darum neue Kennung; der alte Kanal [OLD_CHANNEL] wird gelöscht.
         */
        const val CHANNEL = "next_alarm_lock"
        private const val OLD_CHANNEL = "next_alarm"
        private const val NOTIFICATION_ID = 8
        private const val REQUEST_CODE = 9
        private const val ACTION = "com.phasewake.app.action.NEXT_ALARM_NOTICE"

        /** Ohne Bettzeit-Erinnerung: spätestens ab dieser Uhrzeit am Vorabend der Zielzeit. */
        private val EVENING: LocalTime = LocalTime.of(22, 0)

        /** Höchstens so lange vor der Zielzeit sichtbar. */
        internal val MAX_AHEAD: Duration = Duration.ofHours(16)

        /** So lange nach der Zielzeit bleibt die Mitteilung längstens (falls keine Meldung kommt). */
        internal val KEEP_AFTER: Duration = Duration.ofMinutes(30)

        /**
         * Ab wann die Mitteilung zur Zielzeit [target] erscheint: zur Bettzeit-Erinnerung, ist diese aus
         * zum früheren von ihr und 22:00 am Vorabend; nie früher als [MAX_AHEAD] vor der Zielzeit.
         */
        internal fun showFrom(target: Instant, zone: ZoneId, goalMinutes: Int, bedtimeReminder: Boolean): Instant {
            val reminder = Bedtime.reminderAt(target, goalMinutes)
            val evening = target.atZone(zone).toLocalDate().minusDays(1).atTime(EVENING).atZone(zone).toInstant()
            val start = if (bedtimeReminder) reminder else minOf(reminder, evening)
            return maxOf(start, target.minus(MAX_AHEAD))
        }

        /** Neu zeigen oder planen (im Hintergrund); vor dem ersten Entsperren nichts. */
        fun update(context: Context) {
            if (!BackupAlarm.isUnlocked(context)) return
            val app = context.phoneApp
            app.appScope.launch { app.nextAlarm.reschedule() }
        }

        /** Kanal ohne Ton, Vibration und App-Symbol-Punkt; auf dem Sperrbildschirm ganz sichtbar. */
        fun createChannel(context: Context) {
            val notifications = context.getSystemService(NotificationManager::class.java)
            notifications.deleteNotificationChannel(OLD_CHANNEL)
            notifications.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.widget_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            )
        }
    }
}
