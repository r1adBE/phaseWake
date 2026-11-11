package com.phasewake.app.wear.wake

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.phasewake.app.core.Learning
import com.phasewake.app.core.WakePlan
import java.time.Instant
import kotlinx.coroutines.flow.first

/**
 * Grundrauschen der Einschlaf-Messung je Nacht (Vollaudit K-1): Die Messung ([OnsetMonitor.floor]) merkt es sich beim
 * Einschlafen oder Aufgeben; zu Beginn des Weckfensters derselben Nacht hebt es als Vorwissen
 * ([com.phasewake.app.core.DetectorConfig.withOnsetFloor]) die Schwellen höchstens auf das 1.5-Fache an. Nur die letzte
 * Nacht; dieselbe Zuordnung wie die Einschlafzeit (gleicher Wecker, eingestellte Weckzeit nicht vor der Zielzeit).
 */
class OnsetFloorStore(private val store: DataStore<Preferences>) {

    /** Grundrauschen [floor] der Messung für [alarmId] mit Weckzeit [latest]; ohne gültigen Wert wird der alte gelöscht. */
    suspend fun set(alarmId: String, latest: Instant, floor: Double?) {
        val valid = Learning.validFloor(floor)
        store.edit {
            if (valid == null) {
                it.remove(ALARM)
                it.remove(LATEST)
                it.remove(FLOOR)
            } else {
                it[ALARM] = alarmId
                it[LATEST] = latest.toEpochMilli()
                it[FLOOR] = valid
            }
        }
    }

    /** Grundrauschen für die Nacht [plan], sonst `null` ([floorFor]). */
    suspend fun floorFor(plan: WakePlan): Double? {
        val prefs = store.data.first()
        return floorFor(prefs[ALARM], prefs[LATEST], prefs[FLOOR], plan)
    }

    private companion object {
        val ALARM = stringPreferencesKey("onset_floor_alarm")
        val LATEST = longPreferencesKey("onset_floor_latest")
        val FLOOR = doublePreferencesKey("onset_floor_value")

        /**
         * Gespeichertes Grundrauschen [floor] gilt für [plan], wenn Wecker [alarmId] gleich ist und die eingestellte Weckzeit
         * [latestMs] nicht vor der Zielzeit liegt (im Einschlaf-Modus liegt die Zielzeit früher) und höchstens
         * [WakeRules.GOOD_NIGHT_MAX_AHEAD] danach (eine ältere Nacht desselben Weckers zählt nicht). Sonst `null`.
         */
        fun floorFor(alarmId: String?, latestMs: Long?, floor: Double?, plan: WakePlan): Double? {
            if (alarmId != plan.alarmId || latestMs == null) return null
            val latest = Instant.ofEpochMilli(latestMs)
            if (latest.isBefore(plan.target) || latest.isAfter(plan.target.plus(WakeRules.GOOD_NIGHT_MAX_AHEAD))) return null
            return Learning.validFloor(floor)
        }
    }
}
