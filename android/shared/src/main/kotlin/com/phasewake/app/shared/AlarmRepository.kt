package com.phasewake.app.shared

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.Codec
import com.phasewake.app.core.CodecException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Wecker samt Zeitpunkt der letzten Änderung (für den Abgleich Handy ↔ Uhr). */
data class AlarmSnapshot(val alarms: List<AlarmSpec>, val updatedAt: Long)

/**
 * Speichert die Wecker lokal. Handy und Uhr haben je eine eigene Kopie; [applyRemote] übernimmt
 * die Kopie der Gegenseite, wenn sie neuer ist (letzte Änderung gewinnt).
 */
class AlarmRepository(
    private val store: DataStore<Preferences>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val snapshot: Flow<AlarmSnapshot> = store.data
        .map { prefs -> AlarmSnapshot(decode(prefs[ALARMS]).orEmpty(), prefs[UPDATED_AT] ?: 0L) }
        .distinctUntilChanged()

    val alarms: Flow<List<AlarmSpec>> = snapshot.map { it.alarms }.distinctUntilChanged()

    suspend fun current(): AlarmSnapshot = snapshot.first()

    /** Fügt einen Wecker hinzu oder ersetzt ihn (gleiche [AlarmSpec.id]). @return neuer Stand. */
    suspend fun upsert(spec: AlarmSpec): AlarmSnapshot = mutate { list ->
        val index = list.indexOfFirst { it.id == spec.id }
        if (index < 0) list + spec else list.toMutableList().also { it[index] = spec }
    }

    suspend fun remove(id: String): AlarmSnapshot = mutate { list -> list.filterNot { it.id == id } }

    suspend fun setEnabled(id: String, enabled: Boolean): AlarmSnapshot = mutate { list ->
        list.map { if (it.id == id) it.copy(enabled = enabled) else it }
    }

    /** Ändert den Wecker [id] mit [change] (z. B. «Nächstes Mal überspringen»); fehlt er, bleibt alles. */
    suspend fun update(id: String, change: (AlarmSpec) -> AlarmSpec): AlarmSnapshot = mutate { list ->
        list.map { if (it.id == id) change(it) else it }
    }

    /**
     * Ersetzt alle Wecker (Wiederherstellen einer Sicherung), auch einen unlesbaren Stand. Der Zeitpunkt ist
     * neuer als alles Bisherige, damit die Uhr den Stand übernimmt.
     */
    suspend fun replaceAll(alarms: List<AlarmSpec>): AlarmSnapshot {
        lateinit var result: AlarmSnapshot
        store.edit { prefs ->
            val sorted = alarms.distinctBy { it.id }.sortedWith(ORDER)
            val stamp = maxOf(clock(), (prefs[UPDATED_AT] ?: 0L) + 1)
            prefs[ALARMS] = Codec.encodeAlarms(sorted)
            prefs[UPDATED_AT] = stamp
            result = AlarmSnapshot(sorted, stamp)
        }
        return result
    }

    /** Übernimmt den Stand der Gegenseite, falls er neuer ist. @return ob übernommen wurde. */
    suspend fun applyRemote(remote: AlarmSnapshot): Boolean {
        var applied = false
        store.edit { prefs ->
            if (remote.updatedAt > (prefs[UPDATED_AT] ?: 0L)) {
                prefs[ALARMS] = Codec.encodeAlarms(remote.alarms)
                prefs[UPDATED_AT] = remote.updatedAt
                applied = true
            }
        }
        return applied
    }

    private suspend fun mutate(change: (List<AlarmSpec>) -> List<AlarmSpec>): AlarmSnapshot {
        lateinit var result: AlarmSnapshot
        store.edit { prefs ->
            // Unlesbarer Stand (z. B. Format einer neueren Version): nicht mit einer leeren Liste überschreiben.
            val current = decode(prefs[ALARMS]) ?: throw CodecException("Gespeicherte Wecker nicht lesbar, Änderung verworfen")
            val updated = change(current).sortedWith(ORDER)
            // Streng steigend, damit zwei Änderungen in derselben Millisekunde unterscheidbar bleiben.
            val stamp = maxOf(clock(), (prefs[UPDATED_AT] ?: 0L) + 1)
            prefs[ALARMS] = Codec.encodeAlarms(updated)
            prefs[UPDATED_AT] = stamp
            result = AlarmSnapshot(updated, stamp)
        }
        return result
    }

    /** @return die Wecker, oder `null`, wenn der gespeicherte Text unlesbar ist. */
    private fun decode(text: String?): List<AlarmSpec>? {
        if (text.isNullOrEmpty()) return emptyList()
        return try {
            Codec.decodeAlarms(text)
        } catch (e: CodecException) {
            Log.w(TAG, "Gespeicherte Wecker nicht lesbar", e)
            null
        }
    }

    private companion object {
        const val TAG = "AlarmRepository"
        val ALARMS = stringPreferencesKey("alarms")
        val UPDATED_AT = longPreferencesKey("alarms_updated_at")

        /** Anzeige- und Speicherreihenfolge: nach Uhrzeit, dann Kennung. */
        val ORDER = compareBy<AlarmSpec>({ it.hour }, { it.minute }, { it.id })
    }
}
