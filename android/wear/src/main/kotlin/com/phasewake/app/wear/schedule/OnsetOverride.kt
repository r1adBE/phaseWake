package com.phasewake.app.wear.schedule

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.WakePlan
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.first

/**
 * Plan aus dem Einschlaf-Modus: ersetzt den normalen Plan eines Weckers für eine Nacht, wenn die
 * Zielzeit aus Einschlafzeit + Schlafdauer früher liegt als die eingestellte Weckzeit.
 */
class OnsetOverride(private val store: DataStore<Preferences>) {

    suspend fun get(): WakePlan? {
        val prefs = store.data.first()
        val id = prefs[ALARM] ?: return null
        val windowStart = Instant.ofEpochMilli(prefs[WINDOW_START] ?: return null)
        return WakePlan(id, windowStart, Instant.ofEpochMilli(prefs[TARGET] ?: return null), exact = prefs[EXACT] == true)
    }

    suspend fun set(plan: WakePlan) {
        store.edit {
            it[ALARM] = plan.alarmId
            it[WINDOW_START] = plan.windowStart.toEpochMilli()
            it[TARGET] = plan.target.toEpochMilli()
            it[EXACT] = plan.exact
        }
    }

    suspend fun clear() {
        store.edit {
            it.remove(ALARM)
            it.remove(WINDOW_START)
            it.remove(TARGET)
            it.remove(EXACT)
        }
    }

    /**
     * Ersetzt [plan], wenn die Ausnahme für denselben Wecker gilt, früher liegt und noch kommt. Liegt der Plan desselben
     * Weckers inzwischen weiter weg als die längste Schlafdauer nach der Ausnahme (Audit A-6: «Nächstes Mal
     * überspringen», Wecker verschoben), gehört die Ausnahme zu einer Nacht, die nicht mehr kommt: verwerfen, sonst
     * weckte die Uhr in der übersprungenen Nacht.
     */
    suspend fun apply(plan: WakePlan?, now: Instant): WakePlan? {
        val override = get() ?: return plan
        return when (decide(override, plan, now)) {
            Use.OVERRIDE -> override
            Use.PLAN -> plan
            Use.CLEAR -> {
                clear()
                plan
            }
        }
    }

    /** Ergebnis von [decide]. */
    internal enum class Use { OVERRIDE, PLAN, CLEAR }

    internal companion object {
        private val ALARM = stringPreferencesKey("onset_alarm")
        private val WINDOW_START = longPreferencesKey("onset_window_start")
        private val TARGET = longPreferencesKey("onset_target")
        private val EXACT = booleanPreferencesKey("onset_exact")

        /** Längste Schlafdauer: So weit darf die eingestellte Zielzeit derselben Nacht nach der Ausnahme liegen. */
        private val MAX_SLEEP: Duration = Duration.ofMinutes(AlarmSpec.MAX_SLEEP_MINUTES.toLong())

        /**
         * Regel von [apply]: vorbei → verwerfen; derselbe Wecker mit Zielzeit ab der Ausnahme bis längste Schlafdauer
         * danach → Ausnahme; derselbe Wecker weiter weg → verwerfen; anderer oder kein Plan → Plan (Ausnahme bleibt).
         */
        fun decide(override: WakePlan, plan: WakePlan?, now: Instant): Use = when {
            !override.target.isAfter(now) -> Use.CLEAR
            plan == null || plan.alarmId != override.alarmId -> Use.PLAN
            plan.target.isBefore(override.target) -> Use.PLAN
            plan.target.isAfter(override.target.plus(MAX_SLEEP)) -> Use.CLEAR
            else -> Use.OVERRIDE
        }
    }
}
