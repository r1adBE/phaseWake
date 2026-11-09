package com.phasewake.app.learn

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import com.phasewake.app.core.Learning
import com.phasewake.app.shared.LearningSnapshot
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Stand der Lernphase am Handy: seit wann sie läuft ([since], `null` = nie begonnen), die gelernten Werte ([values],
 * `null` = noch nicht fertig) und ob die Karte «Angepasst an dich» schon bestätigt ist ([seen]). [updatedAt]: Zeitpunkt
 * der letzten Änderung (ms), für den Abgleich mit der Uhr.
 */
data class LearningState(
    val since: Instant?,
    val values: Learning.Values?,
    val seen: Boolean,
    val updatedAt: Long,
) {
    /** Läuft die Lernphase (begonnen, noch keine Werte)? */
    val active: Boolean get() = since != null && values == null

    /** Paket für die Uhr. */
    fun snapshot(): LearningSnapshot = LearningSnapshot.of(active, values, updatedAt)
}

/**
 * Lernphase am Handy, privat im DataStore der App: Keine Sicherung (weder System noch `.pwbak`) enthält sie, denn sie
 * beruht auf den Aufzeichnungen, die ebenfalls nicht gesichert werden. «Verlauf löschen» und «Neu lernen» beginnen sie
 * von vorn ([restart]).
 */
class LearningRepository(private val store: DataStore<Preferences>) {

    val state: Flow<LearningState> = store.data.map(::read)

    suspend fun current(): LearningState = read(store.data.first())

    /**
     * Beginnt die Lernphase, falls sie nie begonnen hat (nach der Installation und beim ersten Start dieser Version).
     * @return ob sie jetzt begonnen hat.
     */
    suspend fun ensureStarted(now: Instant): Boolean {
        var started = false
        store.edit { prefs ->
            if (prefs[SINCE] != null) return@edit
            prefs[SINCE] = now.toEpochMilli()
            prefs[UPDATED_AT] = now.toEpochMilli()
            started = true
        }
        return started
    }

    /** Von vorn ab [now]: gelernte Werte weg, es zählen nur Nächte ab jetzt. */
    suspend fun restart(now: Instant) {
        store.edit { prefs ->
            clearValues(prefs)
            prefs[SINCE] = now.toEpochMilli()
            prefs[SEEN] = false
            prefs[UPDATED_AT] = now.toEpochMilli()
        }
    }

    /** Fertig mit den gelernten Werten [values]; nur, solange sie noch läuft. @return ob gespeichert wurde. */
    suspend fun finish(values: Learning.Values, now: Instant): Boolean {
        var done = false
        store.edit { prefs ->
            if (prefs[SINCE] == null || prefs[NIGHTS] != null) return@edit
            values.floor?.let { prefs[FLOOR] = it }
            values.restingHeartRate?.let { prefs[RESTING_HR] = it }
            values.latencyMinutes?.let { prefs[LATENCY] = it }
            prefs[NIGHTS] = values.nights
            prefs[SEEN] = false
            prefs[UPDATED_AT] = now.toEpochMilli()
            done = true
        }
        return done
    }

    /** Karte «Angepasst an dich» bestätigt. */
    suspend fun setSeen() {
        store.edit { it[SEEN] = true }
    }

    private fun clearValues(prefs: MutablePreferences) {
        prefs.remove(FLOOR)
        prefs.remove(RESTING_HR)
        prefs.remove(LATENCY)
        prefs.remove(NIGHTS)
    }

    private fun read(prefs: Preferences): LearningState {
        val nights = prefs[NIGHTS]
        val values = nights?.let { Learning.Values(prefs[FLOOR], prefs[RESTING_HR], prefs[LATENCY], it) }
        return LearningState(prefs[SINCE]?.let(Instant::ofEpochMilli), values, prefs[SEEN] ?: false, prefs[UPDATED_AT] ?: 0L)
    }

    private companion object {
        val SINCE = longPreferencesKey("learning_since")
        val FLOOR = doublePreferencesKey("learning_floor")
        val RESTING_HR = doublePreferencesKey("learning_resting_hr")
        val LATENCY = intPreferencesKey("learning_latency_minutes")
        val NIGHTS = intPreferencesKey("learning_nights")
        val SEEN = booleanPreferencesKey("learning_seen")
        val UPDATED_AT = longPreferencesKey("learning_updated_at")
    }
}
