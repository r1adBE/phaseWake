package com.phasewake.app.notes

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.phasewake.app.core.CustomNote
import com.phasewake.app.core.SleepNotes
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Schlafnotizen je Nachtdatum und die eigenen Notizen. Nur auf dem Handy, nicht mit der Uhr
 * abgeglichen. Format siehe [SleepNotes].
 */
class NotesRepository(private val store: DataStore<Preferences>) {

    val notes: Flow<Map<LocalDate, List<String>>> = store.data.map { SleepNotes.decode(it[NOTES]) }.distinctUntilChanged()

    val custom: Flow<List<CustomNote>> = store.data.map { SleepNotes.decodeCustom(it[CUSTOM]) }.distinctUntilChanged()

    suspend fun current(): Map<LocalDate, List<String>> = notes.first()

    /** Setzt oder entfernt eine Notiz für ein Nachtdatum; sofort gespeichert. */
    suspend fun toggle(date: LocalDate, id: String) {
        store.edit { prefs -> prefs[NOTES] = SleepNotes.encode(SleepNotes.toggle(SleepNotes.decode(prefs[NOTES]), date, id)) }
    }

    /** «Verlauf löschen»: die Notizen aller Nächte; die eigenen Notiz-Namen bleiben (wie auf dem iPhone). */
    suspend fun clearNotes() {
        store.edit { it.remove(NOTES) }
    }

    /** Neue eigene Notiz; leer oder über dem Limit: nichts. */
    suspend fun addCustom(name: String) {
        val clean = SleepNotes.cleanName(name)
        if (clean.isEmpty()) return
        store.edit { prefs ->
            val list = SleepNotes.decodeCustom(prefs[CUSTOM])
            if (list.size < SleepNotes.MAX_CUSTOM) {
                prefs[CUSTOM] = SleepNotes.encodeCustom(list + CustomNote(SleepNotes.customId(UUID.randomUUID().toString()), clean))
            }
        }
    }

    suspend fun renameCustom(id: String, name: String) {
        val clean = SleepNotes.cleanName(name)
        if (clean.isEmpty()) return
        store.edit { prefs ->
            prefs[CUSTOM] = SleepNotes.encodeCustom(SleepNotes.decodeCustom(prefs[CUSTOM]).map { if (it.id == id) it.copy(name = clean) else it })
        }
    }

    /** Entfernt eine eigene Notiz aus der Auswahl; vergangene Nächte behalten die Kennung (ohne Anzeige). */
    suspend fun deleteCustom(id: String) {
        store.edit { prefs ->
            prefs[CUSTOM] = SleepNotes.encodeCustom(SleepNotes.decodeCustom(prefs[CUSTOM]).filterNot { it.id == id })
        }
    }

    private companion object {
        val NOTES = stringPreferencesKey("sleep_notes")
        val CUSTOM = stringPreferencesKey("sleep_notes_custom")
    }
}
