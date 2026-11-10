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

/**
 * Marken der laufenden Aufzeichnung, gespeichert (überstehen ein Prozessende wie die Aufzeichnung selbst): die [LearnMark]
 * und, ob eine von Hand begonnene durchgehend misst ([setContinuous], nur mit freigeschalteter Diagnose, Audit F-01).
 */
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

    /** Die Aufzeichnung ab [start] misst durchgehend statt in Stichproben. */
    internal suspend fun setContinuous(start: Instant) {
        store.edit { it[CONTINUOUS] = start.toEpochMilli() }
    }

    /** Ob die Aufzeichnung ab [start] durchgehend misst; ohne Marke in Stichproben. */
    internal suspend fun isContinuous(start: Instant): Boolean = store.data.first()[CONTINUOUS] == start.toEpochMilli()

    /** Löscht die Marken; mit [start] nur die, die zu dieser Aufzeichnung gehören. */
    internal suspend fun clear(start: Instant? = null) {
        store.edit {
            if (start == null || it[START] == start.toEpochMilli()) {
                it.remove(START)
                it.remove(UNTIL)
            }
            if (start == null || it[CONTINUOUS] == start.toEpochMilli()) it.remove(CONTINUOUS)
        }
    }

    private companion object {
        val START = longPreferencesKey("learn_record_start")
        val UNTIL = longPreferencesKey("learn_record_until")
        val CONTINUOUS = longPreferencesKey("record_continuous_start")
    }
}
