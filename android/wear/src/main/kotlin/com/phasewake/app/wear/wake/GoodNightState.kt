package com.phasewake.app.wear.wake

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * «Gute Nacht» läuft: seit wann und für welchen Wecker. Gespeichert statt nur im Speicher, damit ein
 * Ende des Prozesses den Modus nicht still beendet: Der [WakeService] nimmt ihn danach wieder auf, und
 * die Startseite zeigt nur diesen Stand. [Saved.auto]: von der automatischen Einschlaf-Messung gestartet
 * ([com.phasewake.app.wear.schedule.AutoOnset]), nicht durch Tippen.
 */
class GoodNightState(private val store: DataStore<Preferences>) {

    /** Gespeicherter Stand: Wecker, seit wann gewartet wird und ob automatisch gestartet. */
    data class Saved(val alarmId: String, val since: Instant, val auto: Boolean = false)

    /** Seit wann auf das Einschlafen gewartet wird; `null`, wenn nicht. */
    val since: Flow<Instant?> = store.data.map { read(it)?.since }.distinctUntilChanged()

    suspend fun load(): Saved? = read(store.data.first())

    suspend fun set(alarmId: String, since: Instant, auto: Boolean = false) {
        store.edit {
            it[ALARM] = alarmId
            it[SINCE] = since.toEpochMilli()
            it[AUTO] = auto
        }
    }

    suspend fun clear() {
        store.edit {
            it.remove(ALARM)
            it.remove(SINCE)
            it.remove(AUTO)
        }
    }

    private fun read(prefs: Preferences): Saved? {
        val id = prefs[ALARM] ?: return null
        val since = prefs[SINCE]?.let(Instant::ofEpochMilli) ?: return null
        // Ein liegengebliebener Stand (länger her als jede mögliche Nacht) gilt nicht mehr.
        if (since.isBefore(Instant.now().minus(WakeService.GOOD_NIGHT_MAX_AHEAD))) return null
        return Saved(id, since, prefs[AUTO] == true)
    }

    private companion object {
        val ALARM = stringPreferencesKey("good_night_alarm")
        val SINCE = longPreferencesKey("good_night_since")
        val AUTO = booleanPreferencesKey("good_night_auto")
    }
}
