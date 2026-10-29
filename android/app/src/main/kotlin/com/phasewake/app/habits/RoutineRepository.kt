package com.phasewake.app.habits

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.phasewake.app.core.Routine
import com.phasewake.app.core.RoutineItem
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Abendroutine: eigene Punkte und die Haken je Nachtdatum. Nur auf dem Handy. Format siehe [Routine]. */
class RoutineRepository(private val store: DataStore<Preferences>) {

    /** Noch nie geändert: die drei Vorschläge. */
    val items: Flow<List<RoutineItem>> = store.data.map { Routine.decodeItems(it[ITEMS]) }.distinctUntilChanged()

    val ticks: Flow<Map<LocalDate, List<String>>> = store.data.map { Routine.decodeTicks(it[TICKS]) }.distinctUntilChanged()

    suspend fun toggle(date: LocalDate, id: String) {
        store.edit { prefs -> prefs[TICKS] = Routine.encodeTicks(Routine.toggle(Routine.decodeTicks(prefs[TICKS]), date, id)) }
    }

    /** Neuer eigener Punkt; leer oder über dem Limit: nichts. */
    suspend fun add(text: String) {
        val clean = Routine.cleanText(text)
        if (clean.isEmpty()) return
        store.edit { prefs ->
            val list = Routine.decodeItems(prefs[ITEMS])
            if (list.size < Routine.MAX_ITEMS) {
                prefs[ITEMS] = Routine.encodeItems(list + RoutineItem(Routine.customId(UUID.randomUUID().toString()), clean))
            }
        }
    }

    /** Entfernt einen Punkt (auch einen Vorschlag); vergangene Haken zählen danach nicht mehr. */
    suspend fun remove(id: String) {
        store.edit { prefs -> prefs[ITEMS] = Routine.encodeItems(Routine.decodeItems(prefs[ITEMS]).filterNot { it.id == id }) }
    }

    /** «Verlauf löschen»: die Haken aller Abende; die Punkte bleiben. */
    suspend fun clearTicks() {
        store.edit { it.remove(TICKS) }
    }

    /** Gespeicherte Texte (Punkte oder `null` = nie geändert, Haken) für die Sicherung. */
    suspend fun raw(): Pair<String?, String> = store.data.first().let { it[ITEMS] to it[TICKS].orEmpty() }

    /** Ersetzt Punkte und Haken (Wiederherstellen); `null` = die Vorschläge. */
    suspend fun restore(items: String?, ticks: String) {
        store.edit {
            if (items == null) {
                it.remove(ITEMS)
            } else {
                it[ITEMS] = Routine.encodeItems(Routine.decodeItems(items))
            }
            it[TICKS] = Routine.encodeTicks(Routine.decodeTicks(ticks))
        }
    }

    private companion object {
        val ITEMS = stringPreferencesKey("routine_items")
        val TICKS = stringPreferencesKey("routine_ticks")
    }
}
