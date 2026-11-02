package com.phasewake.app.wear.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.CatchUpWindow
import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.Transient
import com.phasewake.app.core.WakePlan
import com.phasewake.app.shared.AlarmRepository
import com.phasewake.app.shared.HolidayStore
import com.phasewake.app.shared.TransientStore
import com.phasewake.app.shared.WearSync
import com.phasewake.app.wear.complication.NextAlarmComplicationService
import com.phasewake.app.wear.tile.NextAlarmTileService
import com.phasewake.app.wear.ui.MainActivity
import com.phasewake.app.wear.wake.WakeService
import com.phasewake.app.wear.wearApp
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
 * fällig wurde und noch nicht erledigt ist, als verpasst ([CatchUpWindow], gespeichert unter [CATCH_UP_FROM]; nur
 * Wecker, die dabei schon eingeschaltet waren; Regeln im Kern: [AlarmSchedule.missedPlan], gleich auf der Apple Watch):
 * Dieser Wecker klingelt gleich danach (ohne Messung, sein Fenster ist vorbei). Liegt seine Zielzeit noch vorn, misst er den
 * Rest seines Fensters wie gewohnt. Bewusst kein Zusammenlegen mit dem laufenden Wecken: Jeder
 * eingeschaltete Wecker klingelt sichtbar für sich (eigener Name, Ton, Schlummern, Nacht im Verlauf).
 */
class AlarmScheduler(
    private val context: Context,
    private val alarms: AlarmRepository,
    private val onsetOverride: OnsetOverride,
    private val store: DataStore<Preferences>,
    private val sync: WearSync,
    /** Feiertage vom Handy für Wecker mit «An Feiertagen nicht wecken». */
    private val holidays: HolidayStore,
    /** Probewecker oder Powernap: planen wie einen Wecker, aber nie als Nacht im Verlauf. */
    private val transient: TransientStore,
    /** Uhrzeit; die Zeitzone wird bei jedem Aufruf neu gelesen, nie beim Erzeugen festgehalten (Reisen). */
    private val clock: () -> Clock = { Clock.system(java.time.ZoneId.systemDefault()) },
) {
    /** Hält gleichzeitige Aufrufe von [reschedule] (Startseite, Dienst, Data Layer) auseinander. */
    private val rescheduleLock = Mutex()

    private val status = WatchStatus(context, store, sync, clock)

    /** Pro Wecker erledigt bis hier, sofort gültig; [saveDone] schreibt es in den Speicher. */
    @Volatile
    private var doneInMemory: Map<String, Instant> = emptyMap()

    /**
     * Der Plan, der als Nächstes gilt – mit erkanntem Einschlafen und ohne eine schon erledigte Nacht; ein
     * Probewecker oder Powernap nur nach [Transient.choose] (nie nach seiner Zielzeit + 60 s, nie vor einem früheren
     * Wecker). Startseite, Kachel und Komplikation zeigen genau diesen.
     */
    suspend fun nextPlan(): WakePlan? {
        val now = clock().instant()
        return onsetOverride.apply(Transient.choose(nextAlarmPlan(), transient.active(), now), now)
    }

    /** Der nächste echte Wecker ohne Probewecker und Powernap; erledigte Nächte und Nachholen wie in [nextPlan]. */
    suspend fun nextAlarmPlan(): WakePlan? {
        val now = clock().instant()
        val zone = clock().zone
        val prefs = store.data.first()
        // Eine schon beendete Nacht nicht noch einmal beginnen (früh geweckt, Zielzeit liegt noch vorn).
        // Pro Wecker: Ein zweiter Wecker am selben Morgen bleibt davon unberührt.
        val done = doneUntil(prefs)
        val days = holidays.current()
        val specs = alarms.current().alarms
        // Verpasst, während eine andere Nacht lief: gleich nachholen; sonst der nächste Termin.
        return AlarmSchedule.missedPlan(specs, catchUpWindow(prefs), now, zone, days, done)
            ?: AlarmSchedule.earliestPlan(specs, now, zone, days, done)
    }

    /** Pro Wecker erledigt bis (für «Nächstes Mal überspringen» nach frühem Wecken). */
    suspend fun doneUntil(): Map<String, Instant> = doneUntil(store.data.first())

    /** Pro Wecker erledigt bis: gespeichert und aus [markDone], der spätere Wert gilt. */
    private fun doneUntil(prefs: Preferences): Map<String, Instant> {
        val merged = decodeDone(prefs[DONE_UNTIL_BY_ALARM]).toMutableMap()
        doneInMemory.forEach { (id, at) -> merged[id] = maxOf(merged[id] ?: Instant.EPOCH, at) }
        return merged
    }

    /** Gespeicherter Zeitraum der zuletzt beendeten Nacht(en) für das Nachholen; `null`, wenn keiner. */
    private fun catchUpWindow(prefs: Preferences): CatchUpWindow? {
        val from = prefs[CATCH_UP_FROM]?.let(Instant::ofEpochMilli) ?: return null
        val until = prefs[CATCH_UP_UNTIL]?.let(Instant::ofEpochMilli) ?: return null
        return CatchUpWindow(from, until, prefs[CATCH_UP_IDS].orEmpty())
    }

    /**
     * Eine Nacht, die geweckt hat, ist zu Ende: Zeitraum von ihrem Fensterbeginn bis [until] merken,
     * dazu die eingeschalteten Wecker, deren Zielzeit in diesen Zeitraum fiel (nur diese werden nachgeholt).
     * Schliesst eine nachgeholte Nacht direkt an, wird der Zeitraum verlängert (drei Wecker hintereinander).
     */
    suspend fun noteNightEnded(windowStart: Instant, until: Instant) {
        val zone = clock().zone
        val specs = alarms.current().alarms
        val days = holidays.current()
        store.edit { prefs ->
            val next = AlarmSchedule.catchUpAfter(catchUpWindow(prefs), windowStart, until, specs, zone, days)
            prefs[CATCH_UP_FROM] = next.from.toEpochMilli()
            prefs[CATCH_UP_UNTIL] = next.until.toEpochMilli()
            prefs[CATCH_UP_IDS] = next.alarmIds
        }
    }

    /**
     * Am Handy beendet (Zweitwecker oder dessen Schlummern), aber nicht die Nacht, die die Uhr gerade
     * führt: Diese Nacht des Weckers [alarmId] gilt als erledigt; sie wird weder nachgeholt noch erneut
     * begonnen. Eine laufende Nacht bleibt unberührt. Ohne [target] (ältere Handy-Version oder
     * Schlummern am Handy) gilt die letzte Zielzeit des Weckers.
     */
    suspend fun markDoneFromPhone(alarmId: String, target: Instant?) {
        val now = clock().instant()
        val at = target ?: alarms.current().alarms.firstOrNull { it.id == alarmId }
            ?.let { AlarmSchedule.nextTarget(it.copy(enabled = true), now.minus(PHONE_STOP_LOOKBACK), clock().zone) }
        // Nur eine schon fällige Nacht; eine kommende bleibt stehen.
        if (at == null || at.isAfter(now)) return
        Log.i(TAG, "Wecker $alarmId am Handy beendet, Nacht bis $at erledigt")
        markDone(alarmId, at)
        saveDone()
        reschedule()
    }

    /** Plant neu nach dem aktuellen Stand der Wecker ([ScheduleRules.decide]). @return der geplante Plan oder `null`. */
    suspend fun reschedule(): WakePlan? = rescheduleLock.withLock {
        val now = clock().instant()
        val plan = nextPlan()
        // Nur abfragen, wenn keine Nacht läuft (sonst bleibt ohnehin alles stehen).
        val running = WakeService.activePlan?.takeIf { WakeService.measuringPlan == null }
        val pending = if (running == null) pendingRing(now) else null
        val decision = ScheduleRules.decide(plan, WakeService.measuringPlan, WakeService.activePlan, pending, now)

        // Läuft eine Messung für einen Plan, der nicht mehr gilt (Wecker aus, gelöscht, verschoben): beenden.
        if (decision.cancelMeasuring) WakeService.command(context, WakeService.ACTION_CANCEL)

        refreshSurfaces()
        publishStatus()
        AutoOnset.update(context, nextAlarmPlan(), now)

        // Klingelt oder schlummert gerade eine Nacht: ihre Alarme (Schlummer-Ende) nicht anfassen und
        // keinen anderen Wecker starten, der sie ablösen würde. Ihr Ende plant neu und holt nach.
        if (decision.keepRunning) {
            Log.i(TAG, "Nacht ${running?.alarmId} läuft noch, plane danach")
            return@withLock null
        }

        // Vor einem Neustart geschlummert oder die Zielzeit während des Neustarts verpasst: Dieses
        // Wecken bleibt stehen (Schlummer-Alarm), statt dass das Neuplanen es auf morgen verschiebt.
        val pendingRing = pending?.takeIf { decision.pendingRingAt != null }
        if (pendingRing != null && decision.pendingRingAt != null) {
            Log.i(TAG, "Wecken ${pendingRing.plan.alarmId} von vor dem Neustart bleibt: ${pendingRing.at}")
            setSnoozeEnd(context, pendingRing.plan, decision.pendingRingAt)
        }

        cancel(context, REQUEST_WINDOW, AlarmReceiver.ACTION_WINDOW_START)
        cancel(context, REQUEST_DEADLINE, AlarmReceiver.ACTION_DEADLINE)
        if (plan == null) {
            if (pendingRing == null) DirectBoot.clear(context) else DirectBoot.saveNext(context, null, null)
            Log.i(TAG, "Kein aktiver Wecker")
            return@withLock null
        }
        val specs = alarms.current().alarms
        val spec = specs.firstOrNull { it.id == plan.alarmId } ?: transient.specFor(plan.alarmId)
        logScheduled(plan)
        if (pendingRing == null) {
            // Dazu der Plan danach: Endet eine Nacht vor dem ersten Entsperren, gilt dieser (DirectBoot.promoteNext).
            val after = followingPlan(plan, specs, now)
            DirectBoot.save(context, plan, spec, after, specs.firstOrNull { it.id == after?.alarmId })
        } else {
            // Der abgelegte Eintrag ist das ausstehende Wecken; der neue Plan folgt danach.
            DirectBoot.saveNext(context, plan, spec)
        }
        setDeadline(context, plan)
        // Auch ein schon begonnenes Fenster startet über einen Alarm: Nur so darf der Messdienst
        // aus dem Hintergrund (Empfänger, Data-Layer-Dienst) in den Vordergrund.
        val start = decision.windowAt ?: plan.windowStart
        setAlarmClock(context, start, AlarmReceiver.pending(context, AlarmReceiver.ACTION_WINDOW_START, plan, REQUEST_WINDOW))
        Log.i(TAG, "Geplant: Fenster ${plan.windowStart} bis ${plan.target}")
        plan
    }

    /**
     * Abgelegtes Wecken aus [DirectBoot], das noch aussteht ([ScheduleRules.pendingRing]; [DirectBoot.catchesUp]:
     * Probewecker und Powernap nur 60 s). Nur aufrufen, wenn keine Nacht läuft. Sonst `null`.
     */
    private suspend fun pendingRing(now: Instant): DirectBoot.Saved? {
        val saved = DirectBoot.load(context) ?: return null
        val id = saved.plan.alarmId
        val exists = alarms.current().alarms.any { it.id == id && it.enabled } || transient.specFor(id) != null
        val done = doneUntil(store.data.first())[id]
        return saved.takeIf { ScheduleRules.pendingRing(it, now, DirectBoot.catchesUp(it, now), done, exists) }
    }

    /** Der Plan nach [plan]: dessen Wecker erst nach seiner Zielzeit, die übrigen wie gewohnt. */
    private suspend fun followingPlan(plan: WakePlan, specs: List<AlarmSpec>, now: Instant): WakePlan? {
        val zone = clock().zone
        val done = doneUntil(store.data.first())
        val days = holidays.current()
        return specs.mapNotNull { spec ->
            val from = if (spec.id == plan.alarmId) maxOf(now, plan.target) else now
            AlarmSchedule.nextPlan(spec, maxOf(from, done[spec.id] ?: Instant.EPOCH), zone, days)
        }.filter { it != plan }.minWithOrNull(compareBy({ it.target }, { it.alarmId }))
    }

    /** Zuletzt protokollierter Plan; ein Eintrag nur bei einer Änderung (neu geplant wird oft). */
    @Volatile
    private var loggedPlan: WakePlan? = null

    private fun logScheduled(plan: WakePlan) {
        if (plan == loggedPlan) return
        loggedPlan = plan
        context.wearApp.diag.log(DiagKind.SCHEDULED, "watch ${plan.windowStart} – ${plan.target}")
    }

    /** Uhr-Status ans Handy (bereit, Akkustand), nur bei Bedarf; siehe [WatchStatus.publish]. */
    suspend fun publishStatus() = status.publish()

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
        private val LEGACY_DONE_UNTIL = longPreferencesKey("night_done_until")
        private val DONE_UNTIL_BY_ALARM = stringPreferencesKey("night_done_until_by_alarm")

        /** Zeitraum der zuletzt beendeten Nacht(en) ([CatchUpWindow]), siehe Klassenbeschreibung. */
        private val CATCH_UP_FROM = longPreferencesKey("catch_up_from")
        private val CATCH_UP_UNTIL = longPreferencesKey("catch_up_until")
        private val CATCH_UP_IDS = stringSetPreferencesKey("catch_up_ids")

        /** Am Handy beendet ohne Zielzeit: so weit zurück wird die Nacht gesucht (Fenster, Klingeln, Schlummern). */
        private val PHONE_STOP_LOOKBACK: Duration = Duration.ofHours(2)

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
         * dem Ruhezustand und erlaubt den Start des Messdienstes aus dem Hintergrund). Ohne Recht für exakte Alarme
         * (entziehbar auf Android 12/12L) oder bei einer [SecurityException] ungenau statt gar nicht, wie der
         * Zweitwecker am Handy: Sonst bräche [reschedule] ab, und Fenster und Zielzeit wären beide weg.
         */
        private fun setAlarmClock(context: Context, at: Instant, operation: PendingIntent) {
            val manager = context.getSystemService(AlarmManager::class.java)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()) {
                val show = PendingIntent.getActivity(
                    context, REQUEST_SHOW, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                try {
                    manager.setAlarmClock(AlarmManager.AlarmClockInfo(at.toEpochMilli(), show), operation)
                    return
                } catch (e: SecurityException) {
                    Log.w(TAG, "Exakter Alarm nicht erlaubt", e)
                }
            }
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), operation)
        }
    }
}
