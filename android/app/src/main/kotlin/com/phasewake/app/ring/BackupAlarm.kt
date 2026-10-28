package com.phasewake.app.ring

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.Sunrise
import com.phasewake.app.phoneApp
import com.phasewake.app.shared.AlarmRepository
import com.phasewake.app.ui.MainActivity
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Zweitwecker auf dem Handy. Normalerweise meldet die Uhr den Weckmoment und das Handy klingelt mit.
 * Kommt bis kurz nach der Zielzeit nichts von der Uhr (Akku leer, nicht getragen, nicht verbunden),
 * klingelt das Handy selbst. Gilt nur für Wecker mit eingeschaltetem Zweitwecker.
 */
class BackupAlarm(
    private val context: Context,
    private val alarms: AlarmRepository,
    private val store: DataStore<Preferences>,
    private val sync: com.phasewake.app.shared.WearSync,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    suspend fun reschedule() {
        rescheduleSunrise()
        alarmManager.cancel(pending())
        val backups = alarms.current().alarms.filter { it.phoneBackup }
        // Hat die Uhr die laufende Nacht übernommen, gilt erst der nächste Wecker danach.
        val suppressed = Instant.ofEpochMilli(store.data.first()[SUPPRESSED_UNTIL] ?: 0L)
        val from = maxOf(clock.instant(), suppressed)
        val plan = AlarmSchedule.earliestPlan(backups, from, clock.zone) ?: return
        // Ohne Uhr (nie eine gemeldet): das Handy ist der Wecker und klingelt pünktlich zur Zielzeit.
        val at = if (sync.watchReady() == null) plan.target else plan.target.plus(GRACE)
        val show = PendingIntent.getActivity(
            context, 1, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(at.toEpochMilli(), show), pending(plan.alarmId))
    }

    /** Sonnenaufgang ab Weckfenster: beginnt mit dem Fenster des nächsten Weckers, der ihn will. */
    private suspend fun rescheduleSunrise() {
        alarmManager.cancel(sunrisePending())
        val wanted = alarms.current().alarms.filter { it.sunrise == Sunrise.WINDOW }
        // Ein bereits gezeigtes Licht nicht noch einmal starten.
        val shown = Instant.ofEpochMilli(store.data.first()[SUNRISE_SHOWN_UNTIL] ?: 0L)
        val plan = AlarmSchedule.earliestPlan(wanted, maxOf(clock.instant(), shown), clock.zone) ?: return
        val show = PendingIntent.getActivity(
            context, 3, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val at = maxOf(plan.windowStart, clock.instant())
        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(at.toEpochMilli(), show),
            sunrisePending(plan.alarmId, plan.windowStart, plan.target),
        )
    }

    private fun sunrisePending(alarmId: String? = null, start: Instant? = null, end: Instant? = null): PendingIntent {
        val intent = Intent(context, Receiver::class.java).setAction(ACTION_SUNRISE)
        if (alarmId != null) intent.putExtra(PhoneRingService.EXTRA_ALARM_ID, alarmId)
        if (start != null) intent.putExtra(SunriseLight.EXTRA_START, start.toEpochMilli())
        if (end != null) intent.putExtra(SunriseLight.EXTRA_END, end.toEpochMilli())
        return PendingIntent.getBroadcast(context, 2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Licht starten und merken, damit es nach dem Neuplanen nicht erneut beginnt. */
    internal suspend fun startSunrise(intent: Intent) {
        val end = Instant.ofEpochMilli(intent.getLongExtra(SunriseLight.EXTRA_END, 0L))
        store.edit { it[SUNRISE_SHOWN_UNTIL] = end.toEpochMilli() }
        if (end.isAfter(clock.instant())) {
            SunriseLight.start(
                context,
                SunriseLight.Active(
                    alarmId = intent.getStringExtra(PhoneRingService.EXTRA_ALARM_ID),
                    start = Instant.ofEpochMilli(intent.getLongExtra(SunriseLight.EXTRA_START, 0L)),
                    end = end,
                ),
            )
        }
    }

    /**
     * Die Uhr hat diese Nacht übernommen (klingelt, gestoppt oder Nacht gemeldet): Zweitwecker bis
     * kurz nach der Zielzeit dieser Nacht aussetzen – nicht länger, sonst fiele ein zweiter Wecker
     * am selben Morgen weg.
     */
    suspend fun watchHandled(alarmId: String?, target: Instant? = null) {
        val now = clock.instant()
        val spec = alarms.current().alarms.firstOrNull { it.id == alarmId }
        // Zielzeit der laufenden Nacht: die nächste Zielzeit ab Beginn des längsten Fensters.
        val nightTarget = target
            ?: spec?.let { AlarmSchedule.nextTarget(it, now.minus(Duration.ofMinutes(AlarmSpec.MAX_WINDOW_MINUTES.toLong())), clock.zone) }
            ?: now.plus(Duration.ofMinutes(AlarmSpec.MAX_WINDOW_MINUTES.toLong()))
        val until = nightTarget.plus(GRACE)
        store.edit { if ((it[SUPPRESSED_UNTIL] ?: 0L) < until.toEpochMilli()) it[SUPPRESSED_UNTIL] = until.toEpochMilli() }
        reschedule()
    }

    /** Schlummern am Handy: nach [at] erneut klingeln, auch aus dem Tiefschlaf. */
    fun snoozeUntil(at: Instant, alarmId: String?) {
        val show = PendingIntent.getActivity(
            context, 1, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(at.toEpochMilli(), show), snoozePending(alarmId))
    }

    fun cancelSnooze() = alarmManager.cancel(snoozePending(null))

    private fun snoozePending(alarmId: String?): PendingIntent {
        val intent = Intent(context, Receiver::class.java).setAction(ACTION)
        if (alarmId != null) intent.putExtra(PhoneRingService.EXTRA_ALARM_ID, alarmId)
        return PendingIntent.getBroadcast(context, 3, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun pending(alarmId: String? = null): PendingIntent {
        val intent = Intent(context, Receiver::class.java).setAction(ACTION)
        if (alarmId != null) intent.putExtra(PhoneRingService.EXTRA_ALARM_ID, alarmId)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Löst den Zweitwecker aus und plant den nächsten. Nicht exportiert: nur eigene Alarme. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION -> PhoneRingService.ring(context, intent.getStringExtra(PhoneRingService.EXTRA_ALARM_ID))
                ACTION_SUNRISE -> Unit // unten, nach dem Merken
                else -> return
            }
            val pending = goAsync()
            val app = context.phoneApp
            app.appScope.launch {
                try {
                    if (intent.action == ACTION_SUNRISE) app.backup.startSunrise(intent)
                    app.backup.reschedule()
                } finally {
                    pending.finish()
                }
            }
        }
    }

    /** Neustart, App-Update, Zeit- oder Zeitzonenwechsel: Alarme sind weg oder verschoben, neu planen. */
    class SystemReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pending = goAsync()
            val app = context.phoneApp
            app.appScope.launch {
                try {
                    app.backup.reschedule()
                } finally {
                    pending.finish()
                }
            }
        }
    }

    private companion object {
        const val ACTION = "com.phasewake.app.action.BACKUP_RING"
        const val ACTION_SUNRISE = "com.phasewake.app.action.SUNRISE"
        val SUPPRESSED_UNTIL = longPreferencesKey("backup_suppressed_until")
        val SUNRISE_SHOWN_UNTIL = longPreferencesKey("sunrise_shown_until")

        /** So lange nach der Zielzeit wartet das Handy auf die Uhr. */
        val GRACE: Duration = Duration.ofMinutes(2)
    }
}
