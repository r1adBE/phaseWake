package com.phasewake.app.shared

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Schalter «Einschlafen automatisch erkennen» auf der Uhr, vom Handy ([DataLayer.PATH_AUTO_ONSET]); die Uhr übernimmt
 * nur einen neueren Stand (wie [SleepGoalStore]). Ohne Stand vom Handy gilt «an».
 */
class AutoOnsetStore(private val store: DataStore<Preferences>) {

    /** Ob die Uhr am Abend das Einschlafen misst. */
    val enabled: Flow<Boolean> = store.data.map { it[ENABLED] ?: true }.distinctUntilChanged()

    /**
     * Speichert [enabled] mit Zeitpunkt [updatedAt], nur wenn dieser neuer ist als der gespeicherte.
     * @return ob gespeichert wurde und sich der Schalter dabei geändert hat.
     */
    suspend fun setIfNewer(enabled: Boolean, updatedAt: Long): Boolean {
        var changed = false
        store.edit { prefs ->
            if (updatedAt <= (prefs[UPDATED_AT] ?: 0L)) return@edit
            changed = (prefs[ENABLED] ?: true) != enabled
            prefs[ENABLED] = enabled
            prefs[UPDATED_AT] = updatedAt
        }
        return changed
    }

    private companion object {
        // Dieselben Namen wie am Handy (`SettingsRepository`): gleiche Bedeutung, getrennte Geräte.
        val ENABLED = booleanPreferencesKey("auto_onset")
        val UPDATED_AT = longPreferencesKey("auto_onset_updated_at")
    }
}
