package com.phasewake.app.wear.schedule

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.phasewake.app.core.WakePlan
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
        return WakePlan(id, Instant.ofEpochMilli(prefs[WINDOW_START] ?: return null), Instant.ofEpochMilli(prefs[TARGET] ?: return null))
    }

    suspend fun set(plan: WakePlan) {
        store.edit {
            it[ALARM] = plan.alarmId
            it[WINDOW_START] = plan.windowStart.toEpochMilli()
            it[TARGET] = plan.target.toEpochMilli()
        }
    }

    suspend fun clear() {
        store.edit {
            it.remove(ALARM)
            it.remove(WINDOW_START)
            it.remove(TARGET)
        }
    }

    /** Ersetzt [plan], wenn die Ausnahme für denselben Wecker gilt, früher liegt und noch kommt. */
    suspend fun apply(plan: WakePlan?, now: Instant): WakePlan? {
        val override = get() ?: return plan
        if (!override.target.isAfter(now)) {
            clear()
            return plan
        }
        return if (plan != null && override.alarmId == plan.alarmId && !override.target.isAfter(plan.target)) override else plan
    }

    private companion object {
        val ALARM = stringPreferencesKey("onset_alarm")
        val WINDOW_START = longPreferencesKey("onset_window_start")
        val TARGET = longPreferencesKey("onset_target")
    }
}
