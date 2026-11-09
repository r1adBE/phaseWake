package com.phasewake.app.wear.record

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import java.time.Instant
import kotlinx.coroutines.flow.first

/**
 * Lernphase: Die Aufzeichnung ab [start] hat die Lernphase begonnen; sie endet beim Wecken, ohne Klingeln spätestens zur
 * eingestellten Weckzeit [until] ([RecordControl.endsAtTarget]). Eine von Hand begonnene Aufzeichnung hat keine Marke und
 * läuft wie bisher.
 */
internal data class LearnMark(val start: Instant, val until: Instant)

/** Die [LearnMark] der laufenden Aufzeichnung, gespeichert (übersteht ein Prozessende wie die Aufzeichnung selbst). */
class LearnMarkStore internal constructor(private val store: DataStore<Preferences>) {

    internal suspend fun get(): LearnMark? {
        val prefs = store.data.first()
        val start = prefs[START] ?: return null
        val until = prefs[UNTIL] ?: return null
        return LearnMark(Instant.ofEpochMilli(start), Instant.ofEpochMilli(until))
    }

    internal suspend fun set(mark: LearnMark) {
        store.edit {
            it[START] = mark.start.toEpochMilli()
            it[UNTIL] = mark.until.toEpochMilli()
        }
    }

    /** Löscht die Marke; mit [start] nur, wenn sie zu dieser Aufzeichnung gehört. */
    internal suspend fun clear(start: Instant? = null) {
        store.edit {
            if (start == null || it[START] == start.toEpochMilli()) {
                it.remove(START)
                it.remove(UNTIL)
            }
        }
    }

    private companion object {
        val START = longPreferencesKey("learn_record_start")
        val UNTIL = longPreferencesKey("learn_record_until")
    }
}
