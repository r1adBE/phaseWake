package com.phasewake.app.shared

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.phasewake.app.core.Transient
import com.phasewake.app.core.TransientKind
import com.phasewake.app.core.TestRun
import com.phasewake.app.core.TestRuns
import com.phasewake.app.core.TestStep
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Handy: der zuletzt gestartete Probewecker und was das Handy davon gesehen hat (für das Ergebnis).
 * Gespeichert, damit ein Ende des Prozesses zwischen Start und Klingeln nichts verliert.
 */
class TestRunStore(private val store: DataStore<Preferences>) {

    /** Laufender oder zuletzt beendeter Probewecker; `null`, wenn keiner (oder das Ergebnis geschlossen ist). */
    val run: Flow<TestRun?> = store.data.map { TestRuns.decode(it[KEY]) }.distinctUntilChanged()

    /** Der gespeicherte Lauf jetzt. */
    suspend fun current(): TestRun? = run.first()

    /** Beginnt einen neuen Lauf und ersetzt den alten. */
    suspend fun start(run: TestRun) {
        store.edit { it[KEY] = TestRuns.encode(run) }
    }

    /** Merkt [step] für den Probewecker [alarmId]; andere Kennungen (echte Wecker, alter Test) zählen nicht. */
    suspend fun record(alarmId: String?, step: TestStep, at: Instant) {
        if (Transient.kindOf(alarmId) != TransientKind.TEST) return
        store.edit { prefs ->
            val current = TestRuns.decode(prefs[KEY]) ?: return@edit
            if (current.id == alarmId) prefs[KEY] = TestRuns.encode(TestRuns.record(current, step, at))
        }
    }

    /** Ergebnis geschlossen: nichts mehr anzeigen. */
    suspend fun clear() {
        store.edit { it.remove(KEY) }
    }

    private companion object {
        val KEY = stringPreferencesKey("test_run")
    }
}
