package com.phasewake.app.shared

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.phasewake.app.core.HolidayList
import com.phasewake.app.core.Holidays
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Die aufgelösten Feiertage ([HolidayList]) auf Handy und Uhr. Das Handy rechnet sie aus der Datendatei und
 * schickt sie der Uhr ([DataLayer.PATH_HOLIDAYS]); die Uhr übernimmt nur einen neueren Stand.
 */
class HolidayStore(private val store: DataStore<Preferences>) {

    /** Aktuelle Liste; ohne gespeicherte Daten leer (keine Region, keine Tage). */
    val list: Flow<HolidayList> = store.data.map { Holidays.decode(it[TEXT]) }.distinctUntilChanged()

    /** Nur die Tage, für [com.phasewake.app.core.AlarmSchedule]. */
    val dates: Flow<Set<LocalDate>> = list.map { it.dates }.distinctUntilChanged()

    /** Die Tage jetzt. */
    suspend fun current(): Set<LocalDate> = dates.first()

    /** Zeitpunkt des gespeicherten Stands (ms); 0 ohne Daten. */
    suspend fun updatedAt(): Long = store.data.first()[UPDATED_AT] ?: 0L

    /**
     * Speichert [list] mit Zeitpunkt [updatedAt]. Mit [onlyIfNewer] (Uhr) nur, wenn der Stand neuer ist.
     * @return ob gespeichert wurde und sich die Tage dabei geändert haben.
     */
    suspend fun set(list: HolidayList, updatedAt: Long, onlyIfNewer: Boolean = false): Boolean {
        var changed = false
        store.edit { prefs ->
            if (onlyIfNewer && updatedAt <= (prefs[UPDATED_AT] ?: 0L)) return@edit
            val text = Holidays.encode(list)
            changed = prefs[TEXT]?.let { Holidays.decode(it) } != list
            prefs[TEXT] = text
            prefs[UPDATED_AT] = updatedAt
        }
        return changed
    }

    private companion object {
        val TEXT = stringPreferencesKey("holidays")
        val UPDATED_AT = longPreferencesKey("holidays_updated_at")
    }
}
