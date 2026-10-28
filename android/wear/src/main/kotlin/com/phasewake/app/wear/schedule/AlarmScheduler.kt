package com.phasewake.app.wear.schedule

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.WakePlan
import com.phasewake.app.shared.AlarmRepository
import com.phasewake.app.shared.WearSync
import com.phasewake.app.wear.complication.NextAlarmComplicationService
import com.phasewake.app.wear.tile.NextAlarmTileService
import com.phasewake.app.wear.ui.MainActivity
import com.phasewake.app.wear.wake.WakeService
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.first

/**
 * Stellt die System-Alarme für den nächsten Wecker.
 *
 * Je Wecker zwei Alarme: Fensterbeginn (Messung startet) und Zielzeit (Sicherung). Die Sicherung
 * steht schon beim Planen, nicht erst, wenn die Messung läuft – so weckt die Uhr auch dann zur
 * Zielzeit, wenn der Messdienst nicht starten konnte (z. B. Sensorrechte fehlen).
 */
class AlarmScheduler(
    private val context: Context,
    private val alarms: AlarmRepository,
    private val onsetOverride: OnsetOverride,
    private val store: DataStore<Preferences>,
    private val sync: WearSync,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    /** Plant neu nach dem aktuellen Stand der Wecker. @return der geplante Plan oder `null`. */
    /**
     * Der Plan, der als Nächstes gilt – mit erkanntem Einschlafen und ohne eine schon erledigte Nacht.
     * Startseite, Kachel und Komplikation zeigen genau diesen.
     */
    suspend fun nextPlan(): WakePlan? {
        val now = clock.instant()
        // Eine schon beendete Nacht nicht noch einmal beginnen (früh geweckt, Zielzeit liegt noch vorn).
        val done = Instant.ofEpochMilli(store.data.first()[DONE_UNTIL] ?: 0L)
        return onsetOverride.apply(AlarmSchedule.earliestPlan(alarms.current().alarms, maxOf(now, done), clock.zone), now)
    }

    suspend fun reschedule(): WakePlan? {
        val now = clock.instant()
        val plan = nextPlan()

        // Läuft eine Messung für einen Plan, der nicht mehr gilt (Wecker aus, gelöscht, verschoben): beenden.
        WakeService.measuringPlan?.let { running ->
            if (running != plan) WakeService.command(context, WakeService.ACTION_CANCEL)
        }

        cancel(REQUEST_WINDOW, AlarmReceiver.ACTION_WINDOW_START)
        cancel(REQUEST_DEADLINE, AlarmReceiver.ACTION_DEADLINE)
        refreshSurfaces()
        sync.publishWatchReady(isReady())
        if (plan == null) {
            Log.i(TAG, "Kein aktiver Wecker")
            return null
        }
        setDeadline(plan)
        // Auch ein schon begonnenes Fenster startet über einen Alarm: Nur so darf der Messdienst
        // aus dem Hintergrund (Empfänger, Data-Layer-Dienst) in den Vordergrund.
        val start = maxOf(plan.windowStart, now.plus(IMMEDIATE))
        setAlarmClock(start, AlarmReceiver.pending(context, AlarmReceiver.ACTION_WINDOW_START, plan, REQUEST_WINDOW))
        Log.i(TAG, "Geplant: Fenster ${plan.windowStart} bis ${plan.target}")
        return plan
    }

    /** Merkt, dass die Nacht bis [target] erledigt ist; [reschedule] beginnt sie nicht noch einmal. */
    suspend fun markDone(target: Instant) {
        store.edit { it[DONE_UNTIL] = target.toEpochMilli() }
    }

    /** Sicherungs-Alarm zur Zielzeit. */
    fun setDeadline(plan: WakePlan) =
        setAlarmClock(plan.target, AlarmReceiver.pending(context, AlarmReceiver.ACTION_DEADLINE, plan, REQUEST_DEADLINE))

    /** Weckt nach dem Schlummern erneut. */
    fun setSnoozeEnd(plan: WakePlan, at: Instant) =
        setAlarmClock(at, AlarmReceiver.pending(context, AlarmReceiver.ACTION_SNOOZE_END, plan, REQUEST_SNOOZE))

    fun cancelDeadlineAndSnooze() {
        cancel(REQUEST_DEADLINE, AlarmReceiver.ACTION_DEADLINE)
        cancel(REQUEST_SNOOZE, AlarmReceiver.ACTION_SNOOZE_END)
    }

    /** Sind die Rechte erteilt, die Messen und Wecken brauchen? Fürs Handy und die Startseite. */
    fun isReady(): Boolean {
        val sensors = SENSOR_PERMISSIONS.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        return sensors && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** Kachel und Komplikation sofort aktualisieren, nicht erst beim nächsten Abruf des Systems. */
    private fun refreshSurfaces() {
        runCatching { TileService.getUpdater(context).requestUpdate(NextAlarmTileService::class.java) }
        runCatching {
            ComplicationDataSourceUpdateRequester
                .create(context, ComponentName(context, NextAlarmComplicationService::class.java))
                .requestUpdateAll()
        }
    }

    private fun cancel(requestCode: Int, action: String) =
        alarmManager.cancel(AlarmReceiver.pending(context, action, null, requestCode))

    /**
     * `setAlarmClock` statt `setExact`: Das System behandelt es als Wecker (zeigt ihn an, weckt aus
     * dem Ruhezustand und erlaubt den Start des Messdienstes aus dem Hintergrund).
     */
    private fun setAlarmClock(at: Instant, operation: PendingIntent) {
        val show = PendingIntent.getActivity(
            context, REQUEST_SHOW, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(at.toEpochMilli(), show), operation)
    }

    companion object {
        private const val TAG = "AlarmScheduler"
        private const val REQUEST_WINDOW = 1
        private const val REQUEST_DEADLINE = 2
        private const val REQUEST_SNOOZE = 3
        private const val REQUEST_SHOW = 4
        private val IMMEDIATE: Duration = Duration.ofSeconds(1)
        private val DONE_UNTIL = longPreferencesKey("night_done_until")

        /** Eine davon genügt für den Messdienst (Typ «health»). */
        val SENSOR_PERMISSIONS: List<String> = buildList {
            if (Build.VERSION.SDK_INT >= 36) add("android.permission.health.READ_HEART_RATE")
            add(Manifest.permission.BODY_SENSORS)
            add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
    }
}
