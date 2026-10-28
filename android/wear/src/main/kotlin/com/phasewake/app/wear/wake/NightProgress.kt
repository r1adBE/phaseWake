package com.phasewake.app.wear.wake

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.phasewake.app.core.WakePlan
import com.phasewake.app.core.WakeReason
import java.time.Instant
import kotlinx.coroutines.flow.first

/**
 * Stand der laufenden Weck-Nacht (erstes Klingeln, Grund, Schlummer-Runden), damit ein Neustart des
 * Dienstes beim Schlummer-Ende oder zur Zielzeit nicht wieder bei null beginnt.
 *
 * Gilt nur für genau den Plan, für den er geschrieben wurde. Schreiben führt zusammen statt zu
 * überschreiben (frühestes Klingeln, erster Grund, meiste Runden): So schadet es nicht, wenn zwei
 * Schreibvorgänge in anderer Reihenfolge ankommen.
 */
class NightProgress(private val store: DataStore<Preferences>) {

    data class Saved(val firstRing: Instant?, val reason: WakeReason?, val snoozes: Int)

    suspend fun load(plan: WakePlan): Saved? {
        val prefs = store.data.first()
        if (prefs[ALARM] != plan.alarmId || prefs[TARGET] != plan.target.toEpochMilli()) return null
        return Saved(
            firstRing = prefs[FIRST_RING]?.let(Instant::ofEpochMilli),
            reason = prefs[REASON]?.let { name -> WakeReason.entries.firstOrNull { it.name == name } },
            snoozes = prefs[SNOOZES] ?: 0,
        )
    }

    suspend fun save(plan: WakePlan, firstRing: Instant?, reason: WakeReason?, snoozes: Int) {
        store.edit { prefs ->
            if (prefs[ALARM] != plan.alarmId || prefs[TARGET] != plan.target.toEpochMilli()) {
                remove(prefs)
                prefs[ALARM] = plan.alarmId
                prefs[TARGET] = plan.target.toEpochMilli()
            }
            if (firstRing != null) {
                val old = prefs[FIRST_RING]
                if (old == null || firstRing.toEpochMilli() < old) prefs[FIRST_RING] = firstRing.toEpochMilli()
            }
            if (reason != null && prefs[REASON] == null) prefs[REASON] = reason.name
            prefs[SNOOZES] = maxOf(prefs[SNOOZES] ?: 0, snoozes)
        }
    }

    /** Vergisst den Stand, falls er zu [plan] gehört (eine neuere Nacht bleibt unberührt). */
    suspend fun clear(plan: WakePlan) {
        store.edit { prefs ->
            if (prefs[ALARM] == plan.alarmId && prefs[TARGET] == plan.target.toEpochMilli()) remove(prefs)
        }
    }

    private fun remove(prefs: MutablePreferences) {
        prefs.remove(ALARM)
        prefs.remove(TARGET)
        prefs.remove(FIRST_RING)
        prefs.remove(REASON)
        prefs.remove(SNOOZES)
    }

    private companion object {
        val ALARM = stringPreferencesKey("night_progress_alarm")
        val TARGET = longPreferencesKey("night_progress_target")
        val FIRST_RING = longPreferencesKey("night_progress_first_ring")
        val REASON = stringPreferencesKey("night_progress_reason")
        val SNOOZES = intPreferencesKey("night_progress_snoozes")
    }
}
