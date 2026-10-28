package com.phasewake.app.wear.schedule

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.AlarmSpec
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Stellt die System-Alarme für den nächsten Wecker.
 *
 * Je Wecker zwei Alarme: Fensterbeginn (Messung startet) und Zielzeit (Sicherung). Die Sicherung
 * steht schon beim Planen, nicht erst, wenn die Messung läuft – so weckt die Uhr auch dann zur
 * Zielzeit, wenn der Messdienst nicht starten konnte (z. B. Sensorrechte fehlen).
 *
 * Mehrere Wecker am selben Morgen (z. B. 6:50 und 7:00): Die Uhr führt immer nur eine Nacht. Klingelt
 * oder schlummert eine Nacht, bleiben ihre Alarme stehen und kein anderer Wecker wird geplant; seine
 * Zielzeit geht dabei aber nicht verloren. Endet die Nacht, gilt jede Zielzeit, die in der Zwischenzeit
 * fällig wurde und noch nicht erledigt ist, als verpasst ([CATCH_UP_FROM]): Dieser Wecker klingelt
 * gleich danach (ohne Messung, sein Fenster ist vorbei). Liegt seine Zielzeit noch vorn, misst er den
 * Rest seines Fensters wie gewohnt. Bewusst kein Zusammenlegen mit dem laufenden Wecken: Jeder
 * eingeschaltete Wecker klingelt sichtbar für sich (eigener Name, Ton, Schlummern, Nacht im Verlauf).
 */
class AlarmScheduler(
    private val context: Context,
    private val alarms: AlarmRepository,
    private val onsetOverride: OnsetOverride,
    private val store: DataStore<Preferences>,
    private val sync: WearSync,
    /** Uhrzeit; die Zeitzone wird bei jedem Aufruf neu gelesen, nie beim Erzeugen festgehalten (Reisen). */
    private val clock: () -> Clock = { Clock.system(java.time.ZoneId.systemDefault()) },
) {
    /** Hält gleichzeitige Aufrufe von [reschedule] (Startseite, Dienst, Data Layer) auseinander. */
    private val rescheduleLock = Mutex()

    /** Pro Wecker erledigt bis hier, sofort gültig; [saveDone] schreibt es in den Speicher. */
    @Volatile
    private var doneInMemory: Map<String, Instant> = emptyMap()

    /**
     * Der Plan, der als Nächstes gilt – mit erkanntem Einschlafen und ohne eine schon erledigte Nacht.
     * Startseite, Kachel und Komplikation zeigen genau diesen.
     */
    suspend fun nextPlan(): WakePlan? {
        val now = clock().instant()
        val zone = clock().zone
        val prefs = store.data.first()
        // Eine schon beendete Nacht nicht noch einmal beginnen (früh geweckt, Zielzeit liegt noch vorn).
        // Pro Wecker: Ein zweiter Wecker am selben Morgen bleibt davon unberührt.
        val saved = decodeDone(prefs[DONE_UNTIL_BY_ALARM])
        val memory = doneInMemory
        val busy = busyRange(prefs, now)
        val plan = alarms.current().alarms
            .mapNotNull { spec ->
                val done = maxOf(saved[spec.id] ?: Instant.EPOCH, memory[spec.id] ?: Instant.EPOCH)
                missedPlan(spec, busy, done, now, zone) ?: AlarmSchedule.nextPlan(spec, maxOf(now, done), zone)
            }
            .minWithOrNull(compareBy({ it.target }, { it.alarmId }))
        return onsetOverride.apply(plan, now)
    }

    /**
     * Ein Wecker, dessen Zielzeit fällig wurde, während eine andere Nacht lief ([busy]), und der noch
     * nicht erledigt ist: Plan mit dieser (vergangenen) Zielzeit, damit er gleich klingelt statt erst
     * am nächsten Tag. Sonst `null`.
     */
    private fun missedPlan(spec: AlarmSpec, busy: Pair<Instant, Instant>?, done: Instant, now: Instant, zone: java.time.ZoneId): WakePlan? {
        val (from, until) = busy ?: return null
        val target = AlarmSchedule.nextTarget(spec, maxOf(from, done), zone) ?: return null
        if (target.isAfter(until) || target.isAfter(now)) return null
        return WakePlan(spec.id, AlarmSchedule.windowStart(target, spec.windowMinutes), target)
    }

    /** Zeitraum der zuletzt beendeten Nacht(en), solange er für das Nachholen gilt. */
    private fun busyRange(prefs: Preferences, now: Instant): Pair<Instant, Instant>? {
        val from = prefs[CATCH_UP_FROM]?.let(Instant::ofEpochMilli) ?: return null
        val until = prefs[CATCH_UP_UNTIL]?.let(Instant::ofEpochMilli) ?: return null
        return if (now.isAfter(until.plus(CATCH_UP_VALID))) null else from to until
    }

    /**
     * Eine Nacht, die geweckt hat, ist zu Ende: Zeitraum von ihrem Fensterbeginn bis [until] merken.
     * Schliesst eine nachgeholte Nacht direkt an, wird der Zeitraum verlängert (drei Wecker hintereinander).
     */
    suspend fun noteNightEnded(windowStart: Instant, until: Instant) {
        store.edit { prefs ->
            val oldFrom = prefs[CATCH_UP_FROM]
            val oldUntil = prefs[CATCH_UP_UNTIL]
            val start = windowStart.toEpochMilli()
            val end = until.toEpochMilli()
            if (oldFrom != null && oldUntil != null && start <= oldUntil) {
                prefs[CATCH_UP_FROM] = minOf(oldFrom, start)
                prefs[CATCH_UP_UNTIL] = maxOf(oldUntil, end)
            } else {
                prefs[CATCH_UP_FROM] = start
                prefs[CATCH_UP_UNTIL] = end
            }
        }
    }

    /** Plant neu nach dem aktuellen Stand der Wecker. @return der geplante Plan oder `null`. */
    suspend fun reschedule(): WakePlan? = rescheduleLock.withLock {
        val now = clock().instant()
        val plan = nextPlan()

        // Läuft eine Messung für einen Plan, der nicht mehr gilt (Wecker aus, gelöscht, verschoben): beenden.
        WakeService.measuringPlan?.let { running ->
            if (running != plan) WakeService.command(context, WakeService.ACTION_CANCEL)
        }

        refreshSurfaces()
        // Mit Akkustand der Uhr: Der Nacht-Check am Handy warnt, wenn er für die Nacht knapp ist.
        sync.publishWatchReady(isReady(), batteryPercent())

        // Klingelt oder schlummert gerade eine Nacht: ihre Alarme (Schlummer-Ende) nicht anfassen und
        // keinen anderen Wecker starten, der sie ablösen würde. Ihr Ende plant neu und holt nach.
        WakeService.activePlan?.takeIf { WakeService.measuringPlan == null }?.let { active ->
            Log.i(TAG, "Nacht ${active.alarmId} läuft noch, plane danach")
            return@withLock null
        }

        cancel(context, REQUEST_WINDOW, AlarmReceiver.ACTION_WINDOW_START)
        cancel(context, REQUEST_DEADLINE, AlarmReceiver.ACTION_DEADLINE)
        if (plan == null) {
            DirectBoot.clear(context)
            Log.i(TAG, "Kein aktiver Wecker")
            return@withLock null
        }
        DirectBoot.save(context, plan, alarms.current().alarms.firstOrNull { it.id == plan.alarmId })
        setDeadline(context, plan)
        // Auch ein schon begonnenes Fenster startet über einen Alarm: Nur so darf der Messdienst
        // aus dem Hintergrund (Empfänger, Data-Layer-Dienst) in den Vordergrund.
        val start = maxOf(plan.windowStart, now.plus(IMMEDIATE))
        setAlarmClock(context, start, AlarmReceiver.pending(context, AlarmReceiver.ACTION_WINDOW_START, plan, REQUEST_WINDOW))
        Log.i(TAG, "Geplant: Fenster ${plan.windowStart} bis ${plan.target}")
        plan
    }

    /**
     * Merkt sofort, dass die Nacht bis [target] erledigt ist; [reschedule] beginnt sie nicht noch
     * einmal. Gilt ab dem Aufruf (auch für ein gleichzeitiges Neuplanen), [saveDone] macht es dauerhaft.
     */
    @Synchronized
    fun markDone(alarmId: String, target: Instant) {
        val current = doneInMemory[alarmId]
        if (current == null || target.isAfter(current)) doneInMemory = doneInMemory + (alarmId to target)
    }

    /** Schreibt den Stand aus [markDone] in den Speicher; spätere Werte bleiben, alte fallen weg. */
    suspend fun saveDone() {
        val memory = doneInMemory
        val keepAfter = clock().instant().minus(Duration.ofDays(2))
        store.edit { prefs ->
            val merged = decodeDone(prefs[DONE_UNTIL_BY_ALARM]).toMutableMap()
            memory.forEach { (id, at) -> merged[id] = maxOf(merged[id] ?: Instant.EPOCH, at) }
            prefs[DONE_UNTIL_BY_ALARM] = merged.filterValues { it.isAfter(keepAfter) }
                .entries.joinToString("\n") { "${it.key}=${it.value.toEpochMilli()}" }
            prefs.remove(LEGACY_DONE_UNTIL)
        }
    }

    private fun decodeDone(text: String?): Map<String, Instant> = text.orEmpty().lines().mapNotNull { line ->
        val i = line.lastIndexOf('=')
        if (i <= 0) return@mapNotNull null
        line.substring(i + 1).toLongOrNull()?.let { line.substring(0, i) to Instant.ofEpochMilli(it) }
    }.toMap()

    /** Sind die Rechte erteilt, die Messen und Wecken brauchen? Fürs Handy und die Startseite. */
    fun isReady(): Boolean {
        val sensors = SENSOR_PERMISSIONS.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        val notifications = NotificationManagerCompat.from(context)
        // Ohne Vollbild-Mitteilung erscheint der Weckbildschirm nicht von selbst. Das Hintergrundrecht
        // für den Puls ist nicht nötig: Ohne Puls entscheidet die Bewegung allein.
        return sensors && notifications.areNotificationsEnabled() && notifications.canUseFullScreenIntent()
    }

    /** Akkustand der Uhr in Prozent; `null`, wenn das System ihn nicht nennt. */
    private fun batteryPercent(): Int? =
        context.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }

    /** Kachel und Komplikation sofort aktualisieren, nicht erst beim nächsten Abruf des Systems. */
    private fun refreshSurfaces() {
        runCatching { TileService.getUpdater(context).requestUpdate(NextAlarmTileService::class.java) }
        runCatching {
            ComplicationDataSourceUpdateRequester
                .create(context, ComponentName(context, NextAlarmComplicationService::class.java))
                .requestUpdateAll()
        }
    }

    /**
     * Die Alarme selbst brauchen nur den Context, keinen Speicher: So stellt sie der Weckdienst auch
     * vor dem ersten Entsperren (Direct Boot), ohne den DataStore zu berühren.
     */
    companion object {
        private const val TAG = "AlarmScheduler"
        private const val REQUEST_WINDOW = 1
        private const val REQUEST_DEADLINE = 2
        private const val REQUEST_SNOOZE = 3
        private const val REQUEST_SHOW = 4
        private const val REQUEST_GOOD_NIGHT_CHECK = 5
        private val IMMEDIATE: Duration = Duration.ofSeconds(1)
        private val LEGACY_DONE_UNTIL = longPreferencesKey("night_done_until")
        private val DONE_UNTIL_BY_ALARM = stringPreferencesKey("night_done_until_by_alarm")

        /** Zeitraum der zuletzt beendeten Nacht(en), siehe Klassenbeschreibung. */
        private val CATCH_UP_FROM = longPreferencesKey("catch_up_from")
        private val CATCH_UP_UNTIL = longPreferencesKey("catch_up_until")

        /**
         * So lange nach dem Ende der Nacht wird noch nachgeholt. Danach nicht mehr: Sonst klänge ein
         * Wecker, der Stunden später wieder eingeschaltet wird, sofort für einen längst vergangenen Morgen.
         */
        private val CATCH_UP_VALID: Duration = Duration.ofMinutes(10)

        /** So oft prüft ein Alarm, ob «Gute Nacht» noch misst, und startet es sonst neu. */
        private val GOOD_NIGHT_CHECK_INTERVAL: Duration = Duration.ofMinutes(15)

        /** Sicherungs-Alarm zur Zielzeit. */
        fun setDeadline(context: Context, plan: WakePlan) = setDeadlineAt(context, plan, plan.target)

        /** Sicherungs-Alarm für [plan] zu [at] (vor dem Entsperren: nach einem Neustart). */
        fun setDeadlineAt(context: Context, plan: WakePlan, at: Instant) =
            setAlarmClock(context, at, AlarmReceiver.pending(context, AlarmReceiver.ACTION_DEADLINE, plan, REQUEST_DEADLINE))

        /** Weckt nach dem Schlummern erneut. */
        fun setSnoozeEnd(context: Context, plan: WakePlan, at: Instant) =
            setAlarmClock(context, at, AlarmReceiver.pending(context, AlarmReceiver.ACTION_SNOOZE_END, plan, REQUEST_SNOOZE))

        fun cancelDeadlineAndSnooze(context: Context) {
            cancel(context, REQUEST_DEADLINE, AlarmReceiver.ACTION_DEADLINE)
            cancel(context, REQUEST_SNOOZE, AlarmReceiver.ACTION_SNOOZE_END)
        }

        /**
         * Nächste Prüfung von «Gute Nacht». Exakter Alarm (kein Wecker): erscheint nicht als nächste
         * Weckzeit, erlaubt aber den Neustart des Messdienstes aus dem Hintergrund.
         */
        fun armGoodNightCheck(context: Context, at: Instant = Instant.now().plus(GOOD_NIGHT_CHECK_INTERVAL)) {
            try {
                context.getSystemService(AlarmManager::class.java)
                    .setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), goodNightCheckPending(context))
            } catch (e: SecurityException) {
                Log.w(TAG, "Prüfung von «Gute Nacht» nicht planbar", e)
            }
        }

        fun cancelGoodNightCheck(context: Context) =
            context.getSystemService(AlarmManager::class.java).cancel(goodNightCheckPending(context))

        private fun goodNightCheckPending(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, REQUEST_GOOD_NIGHT_CHECK,
            Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_GOOD_NIGHT_CHECK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun cancel(context: Context, requestCode: Int, action: String) =
            context.getSystemService(AlarmManager::class.java).cancel(AlarmReceiver.pending(context, action, null, requestCode))

        /**
         * `setAlarmClock` statt `setExact`: Das System behandelt es als Wecker (zeigt ihn an, weckt aus
         * dem Ruhezustand und erlaubt den Start des Messdienstes aus dem Hintergrund).
         */
        private fun setAlarmClock(context: Context, at: Instant, operation: PendingIntent) {
            val show = PendingIntent.getActivity(
                context, REQUEST_SHOW, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            context.getSystemService(AlarmManager::class.java).setAlarmClock(AlarmManager.AlarmClockInfo(at.toEpochMilli(), show), operation)
        }

        /** Eine davon genügt für den Messdienst (Typ «health»). */
        val SENSOR_PERMISSIONS: List<String> = buildList {
            if (Build.VERSION.SDK_INT >= 36) add("android.permission.health.READ_HEART_RATE")
            add(Manifest.permission.BODY_SENSORS)
            add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
    }
}
