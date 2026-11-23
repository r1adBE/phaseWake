package com.phasewake.app.widget

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.phasewake.app.R
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.Bedtime
import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.WakePlan
import com.phasewake.app.phoneApp
import com.phasewake.app.ring.BackupAlarm
import com.phasewake.app.ui.MainActivity
import com.phasewake.app.ui.exactAlarmSettings
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
 *
 * Privat ([NotificationCompat.VISIBILITY_PRIVATE]): Auf dem gesperrten Bildschirm steht nur «Nächster Wecker», ohne
 * Fenster und ohne Text (öffentliche Fassung); Weckzeit und Nacht-Check erst nach dem Entsperren.
 *
 * Fehlt auf Android 12/12L das Recht für exakte Wecker, warnt sie stattdessen und bietet «Erlauben» an ([content]).
 */
class NextAlarmNotice(private val context: Context, private val now: () -> Instant = Instant::now) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val mutex = Mutex()

    /** Ob die zuletzt gezeigte Mitteilung warnte (Diagnose nur beim Wechsel). */
    private var warned = false

    /** Zeigt, entfernt oder plant die Mitteilung für den aktuellen Stand. */
    suspend fun reschedule() = mutex.withLock {
        alarmManager.cancel(pending())
        val app = context.phoneApp
        val current = now()
        val zone = ZoneId.systemDefault()
        val plan = if (app.settings.nextAlarmNotice.first()) {
            AlarmSchedule.earliestPlan(
                app.alarms.current().alarms, current, zone, app.holidays.store.current(), app.backup.doneUntil(),
            )
        } else {
            null
        }
        if (plan == null) {
            manager.cancel(NOTIFICATION_ID)
            return@withLock
        }
        val from = showFrom(
            plan.target, zone, app.settings.sleepGoalNow(), app.settings.bedtimeReminder.first(),
            app.learning.current().values?.latencyMinutes,
        )
        if (from.isAfter(current)) {
            manager.cancel(NOTIFICATION_ID)
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, from.toEpochMilli(), pending())
        } else {
            show(plan, zone, current)
            // Danach aufräumen und die nächste Nacht planen, auch wenn sonst nichts neu plant.
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, plan.target.plus(KEEP_AFTER).toEpochMilli(), pending(),
            )
        }
    }

    private fun show(plan: WakePlan, zone: ZoneId, current: Instant) {
        if (!manager.areNotificationsEnabled()) return
        val content = content(context, plan, zone)
        // Nur beim Wechsel zur Warnung, nicht bei jedem Neuplanen.
        if (content.exactMissing && !warned) {
            context.phoneApp.diag.log(DiagKind.SCHEDULED, "evening notice exact alarms missing")
        }
        warned = content.exactMissing
        manager.notify(NOTIFICATION_ID, build(plan, current, content))
    }

    /** Die Mitteilung mit [content]; ohne Recht für exakte Wecker mit dem Knopf «Erlauben». */
    internal fun build(plan: WakePlan, current: Instant, content: Content): Notification {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL)
        if (content.exactMissing && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val allow = PendingIntent.getActivity(
                context, REQUEST_ALLOW, exactAlarmSettings(context),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, context.getString(R.string.check_allow), allow)
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(content.text + "\n" + context.getString(R.string.check_exact_alarms_problem)),
                )
        }
        return builder
            .setSmallIcon(R.drawable.ic_stat_moon)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion())
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            // Nur auf dem Handy: Die Uhr zeigt ihren Wecker selbst.
            .setLocalOnly(true)
            .setContentIntent(open)
            .setTimeoutAfter(Duration.between(current, plan.target.plus(KEEP_AFTER)).toMillis().coerceAtLeast(1L))
            .build()
    }

    /** Titel und Text der Mitteilung; [exactMissing]: Warnung, weil das Recht für exakte Wecker fehlt. */
    internal data class Content(val title: String, val text: String, val exactMissing: Boolean)

    /** Fassung für den gesperrten Bildschirm: nur der Titel «Nächster Wecker», keine Weckzeit, kein Text. */
    private fun publicVersion(): Notification = NotificationCompat.Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_moon)
        .setContentTitle(context.getString(R.string.widget_name))
        .setCategory(NotificationCompat.CATEGORY_STATUS)
        .setShowWhen(false)
        .build()

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
         * («lautlos») auf dem Sperrbildschirm standardmässig aus. Wichtigkeit und Sichtbarkeit auf dem Sperrbildschirm
         * lassen sich nach dem Anlegen nicht mehr ändern, darum je eine neue Kennung (jetzt privat); die alten Kanäle
         * [OLD_CHANNELS] werden gelöscht.
         */
        const val CHANNEL = "next_alarm_private"
        private val OLD_CHANNELS = listOf("next_alarm", "next_alarm_lock")
        private const val NOTIFICATION_ID = 8
        private const val REQUEST_CODE = 9
        private const val REQUEST_ALLOW = 10
        private const val ACTION = "com.phasewake.app.action.NEXT_ALARM_NOTICE"

        /** Ohne Bettzeit-Erinnerung: spätestens ab dieser Uhrzeit am Vorabend der Zielzeit. */
        private val EVENING: LocalTime = LocalTime.of(22, 0)

        /** Höchstens so lange vor der Zielzeit sichtbar. */
        internal val MAX_AHEAD: Duration = Duration.ofHours(16)

        /** So lange nach der Zielzeit bleibt die Mitteilung längstens (falls keine Meldung kommt). */
        internal val KEEP_AFTER: Duration = Duration.ofMinutes(30)

        /**
         * Ab wann die Mitteilung zur Zielzeit [target] erscheint: zur Bettzeit-Erinnerung, ist diese aus
         * zum früheren von ihr und 22:00 am Vorabend; nie früher als [MAX_AHEAD] vor der Zielzeit. [latencyMinutes]:
         * gelernte Einschlafdauer wie bei der Erinnerung (die eine Bettzeit).
         */
        internal fun showFrom(
            target: Instant,
            zone: ZoneId,
            goalMinutes: Int,
            bedtimeReminder: Boolean,
            latencyMinutes: Int? = null,
        ): Instant {
            val reminder = Bedtime.reminderAt(target, goalMinutes, latencyMinutes = latencyMinutes)
            val evening = target.atZone(zone).toLocalDate().minusDays(1).atTime(EVENING).atZone(zone).toInstant()
            val start = if (bedtimeReminder) reminder else minOf(reminder, evening)
            return maxOf(start, target.minus(MAX_AHEAD))
        }

        /**
         * Inhalt für [plan]: Fehlt das Recht für exakte Wecker (nur Android 12/12L entziehbar, ab 13 gilt
         * USE_EXACT_ALARM), warnt der Titel und der Text nennt das Weckfenster; sonst Weckfenster als Titel und
         * Nacht-Check (ersatzweise der Tag) als Text. Die Abend-Mitteilung selbst plant ungenau und kommt auch ohne
         * das Recht; wird es erteilt, plant [BackupAlarm.SystemReceiver] neu und die Warnung verschwindet.
         */
        internal fun content(context: Context, plan: WakePlan, zone: ZoneId): Content {
            val window = NextAlarmWidget.windowText(context, plan, zone)
            return if (BackupAlarm.canScheduleExact(context)) {
                val text = NextAlarmWidget.checkText(context) ?: NextAlarmWidget.day(context, plan.target, zone)
                Content(window, text, exactMissing = false)
            } else {
                Content(context.getString(R.string.notice_exact_alarms_missing), window, exactMissing = true)
            }
        }

        /** Neu zeigen oder planen (im Hintergrund); vor dem ersten Entsperren nichts. */
        fun update(context: Context) {
            if (!BackupAlarm.isUnlocked(context)) return
            val app = context.phoneApp
            app.appScope.launch { app.nextAlarm.reschedule() }
        }

        /** Kanal ohne Ton, Vibration und App-Symbol-Punkt; auf dem Sperrbildschirm nur die öffentliche Fassung. */
        fun createChannel(context: Context) {
            val notifications = context.getSystemService(NotificationManager::class.java)
            OLD_CHANNELS.forEach(notifications::deleteNotificationChannel)
            notifications.createNotificationChannel(
                NotificationChannel(
                    CHANNEL, context.getString(R.string.widget_name), NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                },
            )
        }
    }
}
