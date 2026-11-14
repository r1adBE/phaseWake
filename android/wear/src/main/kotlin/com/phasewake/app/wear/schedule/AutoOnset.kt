package com.phasewake.app.wear.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.ShortNight
import com.phasewake.app.core.Transient
import com.phasewake.app.core.WakePlan
import com.phasewake.app.wear.wake.WakeService
import com.phasewake.app.wear.wearApp
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.first

/**
 * Automatische Einschlaf-Messung (nur Galaxy Watch, `docs/PHASENERKENNUNG.md`, Abschnitt 8): Für den nächsten
 * gespeicherten Wecker startet ein exakter Alarm 60 Min vor der Bettzeit die stromsparende Messung von «Gute Nacht».
 * Sie endet beim erkannten Einschlafen, spätestens 2 Std. nach ihrem Beginn
 * ([com.phasewake.app.wear.wake.OnsetMonitor.MAX_DURATION]; bei Unruhe verlängert bis 4 Std., nie ins Weckfenster) und
 * 15 Min vor der Zielzeit; unter [MIN_BATTERY_PERCENT] Akku beginnt sie nicht und verlängert sich nicht
 * ([skipForBattery], Akku-Befund 03.11.2026). Im Einschlaf-Modus legt sie die Zielzeit fest
 * (wie «Gute Nacht»), sonst merkt sie nur die Einschlafzeit für die Untergrenze kurzer Nächte ([OnsetStore]).
 *
 * Geplant bei jedem Neuplanen ([AlarmScheduler.reschedule]): Neustart, geänderte Wecker, Schlafziel, Schalter am Handy
 * («Einschlafen automatisch erkennen», [com.phasewake.app.shared.AutoOnsetStore]); nur, wenn er an ist.
 */
object AutoOnset {
    /** Aktion des Alarms an [AlarmReceiver]. */
    const val ACTION = "com.phasewake.app.action.AUTO_ONSET"
    private const val EXTRA_ALARM_ID = "autoOnsetAlarmId"
    private const val REQUEST = 6
    private const val TAG = "AutoOnset"
    private val IMMEDIATE: Duration = Duration.ofSeconds(1)

    /** Unter diesem Akkustand (Prozent) startet die automatische Messung nicht. */
    const val MIN_BATTERY_PERCENT = 30

    /** Ob die automatische Messung bei Akkustand [percent] ausfällt; unbekannt (`null`) hindert nicht. */
    fun skipForBattery(percent: Int?): Boolean = percent != null && percent < MIN_BATTERY_PERCENT

    /**
     * Beginn der Messung für [plan] (Plan des Weckers [spec] ohne Einschlaf-Ausnahme), oder `null`: kein gespeicherter
     * Wecker (Powernap, Probewecker), schon erledigt ([done]: eingeschlafen, aufgegeben, abgebrochen), oder der Beginn
     * läge nicht vor dem Fenster und 15 Min vor der Zielzeit oder mehr als 16 Std. davor. Liegt die Bettzeit schon
     * zurück, beginnt sie gleich – ausser es endete seit dem Beginn schon eine Nacht mit Wecken ([lastNightEnded],
     * [AlarmScheduler.lastNightEnded]): zweiter Wecker am selben Morgen, der Schlaf dieser Nacht ist vorbei. Eine Messung
     * gleich nach dem Wecken fände kein Einschlafen und sperrte das frühe Wecken (fail-closed, Audit G-2).
     */
    fun startAt(plan: WakePlan?, spec: AlarmSpec?, goalMinutes: Int, now: Instant, done: Boolean, lastNightEnded: Instant? = null): Instant? {
        if (plan == null || spec == null || spec.id != plan.alarmId || done || Transient.kindOf(plan.alarmId) != null) return null
        val start = ShortNight.autoOnsetStart(plan.target, goalMinutes, spec.sleepDurationMinutes)
        if (lastNightEnded != null && !lastNightEnded.isBefore(start)) return null
        val at = maxOf(start, now.plus(IMMEDIATE))
        val giveUp = plan.target.minus(WakeService.ONSET_GIVE_UP)
        if (!at.isBefore(giveUp) || !at.isBefore(plan.windowStart)) return null
        if (Duration.between(at, plan.target) > WakeService.GOOD_NIGHT_MAX_AHEAD) return null
        return at
    }

    /** Stellt oder löscht den Alarm für [plan], den nächsten echten Wecker ([AlarmScheduler.nextAlarmPlan]). */
    suspend fun update(context: Context, plan: WakePlan?, now: Instant) {
        val app = context.wearApp
        val spec = plan?.let { p -> app.alarms.current().alarms.firstOrNull { it.id == p.alarmId } }
        val done = plan != null && app.onsets.isDone(plan.alarmId, plan.target)
        val goal = app.sleepGoal.minutes.first()
        // Schalter am Handy aus: kein Alarm, ein gestellter wird aufgehoben.
        val at = if (app.autoOnset.enabled.first()) startAt(plan, spec, goal, now, done, app.scheduler.lastNightEnded()) else null
        if (plan == null || at == null) {
            context.getSystemService(AlarmManager::class.java).cancel(pending(context, null))
            return
        }
        try {
            context.getSystemService(AlarmManager::class.java)
                .setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pending(context, plan.alarmId))
        } catch (e: SecurityException) {
            // Ohne exakte Alarme keine automatische Messung; der Wecker weckt wie bisher.
            Log.w(TAG, "Einschlaf-Messung nicht planbar", e)
        }
    }

    /** Kennung des Weckers aus dem Alarm, oder `null`. */
    fun alarmIdFrom(intent: Intent): String? = intent.getStringExtra(EXTRA_ALARM_ID)

    private fun pending(context: Context, alarmId: String?): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).setAction(ACTION)
        if (alarmId != null) intent.putExtra(EXTRA_ALARM_ID, alarmId)
        return PendingIntent.getBroadcast(context, REQUEST, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}

/**
 * Einschlafzeit der kommenden Nacht, je Wecker und eingestellter Weckzeit ([latest]); nur die jüngste Nacht. Grundlage
 * der Untergrenze für kurze Nächte ([com.phasewake.app.core.ShortNight.blocksEarly]). Ein Eintrag ohne Einschlafzeit
 * heisst: Messung vorbei, für diese Nacht nicht noch einmal starten – mit [measuredFor] `true`, wenn sie begonnen hatte
 * und ohne Ergebnis endete (aufgegeben, vom Weckfenster oder einem Prozessende beendet; fail-closed, Entscheid r1AD
 * 10.11.2026), sonst (abgebrochen, nicht begonnen) ohne Untergrenze.
 */
class OnsetStore(private val store: DataStore<Preferences>) {

    /**
     * Messung für [alarmId] mit Weckzeit [latest] vorbei, mit Einschlafzeit [onset] oder ohne (`null`); [measured]: ohne
     * Einschlafzeit, obwohl sie gemessen hat.
     */
    suspend fun set(alarmId: String, latest: Instant, onset: Instant?, measured: Boolean = false) {
        store.edit {
            it.remove(ONSET)
            if (onset != null) it[ONSET] = onset.toEpochMilli()
            it[ALARM] = alarmId
            it[LATEST] = latest.toEpochMilli()
            it[MEASURED] = onset == null && measured
        }
    }

    /** Ob die Messung für [alarmId] mit Weckzeit [latest] schon vorbei ist. */
    suspend fun isDone(alarmId: String, latest: Instant): Boolean {
        val prefs = store.data.first()
        return prefs[ALARM] == alarmId && prefs[LATEST] == latest.toEpochMilli()
    }

    /**
     * Einschlafzeit für die Nacht [plan]: gleicher Wecker, eingestellte Weckzeit nicht vor der Zielzeit (im
     * Einschlaf-Modus liegt die Zielzeit früher) und Einschlafzeit höchstens 16 Std. vor der Zielzeit. Sonst `null`.
     */
    suspend fun onsetFor(plan: WakePlan): Instant? {
        val prefs = store.data.first()
        if (!sameNight(prefs, plan)) return null
        val onset = prefs[ONSET]?.let(Instant::ofEpochMilli) ?: return null
        if (!onset.isBefore(plan.target)) return null
        return onset.takeIf { Duration.between(it, plan.target) <= WakeService.GOOD_NIGHT_MAX_AHEAD }
    }

    /** Ob für die Nacht [plan] gemessen wurde, aber ohne Einschlafzeit (siehe [set]). */
    suspend fun measuredFor(plan: WakePlan): Boolean {
        val prefs = store.data.first()
        return sameNight(prefs, plan) && prefs[ONSET] == null && prefs[MEASURED] == true
    }

    /** Gleicher Wecker und eingestellte Weckzeit nicht vor der Zielzeit von [plan]. */
    private fun sameNight(prefs: Preferences, plan: WakePlan): Boolean {
        if (prefs[ALARM] != plan.alarmId) return false
        val latest = prefs[LATEST]?.let(Instant::ofEpochMilli) ?: return false
        return !latest.isBefore(plan.target)
    }

    private companion object {
        val ALARM = stringPreferencesKey("onset_night_alarm")
        val LATEST = longPreferencesKey("onset_night_latest")
        val ONSET = longPreferencesKey("onset_night_at")
        val MEASURED = booleanPreferencesKey("onset_night_measured")
    }
}
