package com.phasewake.app.wear.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
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
 * Sie endet beim erkannten Einschlafen oder 15 Min vor der Zielzeit. Im Einschlaf-Modus legt sie die Zielzeit fest
 * (wie «Gute Nacht»), sonst merkt sie nur die Einschlafzeit für die Untergrenze kurzer Nächte ([OnsetStore]).
 *
 * Geplant bei jedem Neuplanen ([AlarmScheduler.reschedule]): Neustart, geänderte Wecker, Schlafziel.
 */
object AutoOnset {
    /** Aktion des Alarms an [AlarmReceiver]. */
    const val ACTION = "com.phasewake.app.action.AUTO_ONSET"
    private const val EXTRA_ALARM_ID = "autoOnsetAlarmId"
    private const val REQUEST = 6
    private const val TAG = "AutoOnset"
    private val IMMEDIATE: Duration = Duration.ofSeconds(1)

    /**
     * Beginn der Messung für [plan] (Plan des Weckers [spec] ohne Einschlaf-Ausnahme), oder `null`: kein gespeicherter
     * Wecker (Powernap, Probewecker), schon erledigt ([done]: eingeschlafen, aufgegeben, abgebrochen), oder der Beginn
     * läge nicht vor dem Fenster und 15 Min vor der Zielzeit oder mehr als 16 Std. davor. Liegt die Bettzeit schon
     * zurück, beginnt sie gleich.
     */
    fun startAt(plan: WakePlan?, spec: AlarmSpec?, goalMinutes: Int, now: Instant, done: Boolean): Instant? {
        if (plan == null || spec == null || spec.id != plan.alarmId || done || Transient.kindOf(plan.alarmId) != null) return null
        val start = ShortNight.autoOnsetStart(plan.target, goalMinutes, spec.sleepDurationMinutes)
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
        val at = startAt(plan, spec, goal, now, done)
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
 * heisst: Messung vorbei (aufgegeben oder abgebrochen), für diese Nacht nicht noch einmal starten.
 */
class OnsetStore(private val store: DataStore<Preferences>) {

    /** Messung für [alarmId] mit Weckzeit [latest] vorbei, mit Einschlafzeit [onset] oder ohne (`null`). */
    suspend fun set(alarmId: String, latest: Instant, onset: Instant?) {
        store.edit {
            it.remove(ONSET)
            if (onset != null) it[ONSET] = onset.toEpochMilli()
            it[ALARM] = alarmId
            it[LATEST] = latest.toEpochMilli()
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
        if (prefs[ALARM] != plan.alarmId) return null
        val latest = prefs[LATEST]?.let(Instant::ofEpochMilli) ?: return null
        val onset = prefs[ONSET]?.let(Instant::ofEpochMilli) ?: return null
        if (latest.isBefore(plan.target) || !onset.isBefore(plan.target)) return null
        return onset.takeIf { Duration.between(it, plan.target) <= WakeService.GOOD_NIGHT_MAX_AHEAD }
    }

    private companion object {
        val ALARM = stringPreferencesKey("onset_night_alarm")
        val LATEST = longPreferencesKey("onset_night_latest")
        val ONSET = longPreferencesKey("onset_night_at")
    }
}
