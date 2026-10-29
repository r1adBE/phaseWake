package com.phasewake.app.shared

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import com.phasewake.app.core.SleepGoal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Schlafziel auf der Uhr, vom Handy ([DataLayer.PATH_SLEEP_GOAL]); die Uhr übernimmt nur einen neueren Stand.
 * Eine Uhr ohne Handy zeigt [SleepGoal.DEFAULT_MINUTES]. Es dient nur der Anzeige (Einschlafpunkt), nie dem Wecken.
 */
class SleepGoalStore(private val store: DataStore<Preferences>) {

    /** Schlafziel in Minuten, mit [SleepGoal.clamp]; ohne gespeicherten Stand der Standard. */
    val minutes: Flow<Int> = store.data.map { SleepGoal.clamp(it[MINUTES] ?: SleepGoal.DEFAULT_MINUTES) }.distinctUntilChanged()

    /** Zeitpunkt des gespeicherten Stands (ms); 0 ohne Daten. */
    suspend fun updatedAt(): Long = store.data.first()[UPDATED_AT] ?: 0L

    /**
     * Speichert [minutes] (mit [SleepGoal.clamp]) mit Zeitpunkt [updatedAt], nur wenn dieser neuer ist als der
     * gespeicherte. @return ob gespeichert wurde und sich das Ziel dabei geändert hat.
     */
    suspend fun setIfNewer(minutes: Int, updatedAt: Long): Boolean {
        var changed = false
        store.edit { prefs ->
            if (updatedAt <= (prefs[UPDATED_AT] ?: 0L)) return@edit
            val goal = SleepGoal.clamp(minutes)
            changed = SleepGoal.clamp(prefs[MINUTES] ?: SleepGoal.DEFAULT_MINUTES) != goal
            prefs[MINUTES] = goal
            prefs[UPDATED_AT] = updatedAt
        }
        return changed
    }

    private companion object {
        // Dieselben Namen wie am Handy (`SettingsRepository`): gleiche Bedeutung, getrennte Geräte.
        val MINUTES = intPreferencesKey("sleep_goal_minutes")
        val UPDATED_AT = longPreferencesKey("sleep_goal_updated_at")
    }
}
