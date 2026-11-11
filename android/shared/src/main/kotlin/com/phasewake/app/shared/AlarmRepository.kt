package com.phasewake.app.shared

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.Codec
import com.phasewake.app.core.CodecException
import com.phasewake.app.core.TimeBounds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Wecker samt Zeitpunkt der letzten Änderung der Liste ([updatedAt]) für den Abgleich Handy ↔ Uhr. Seit Audit A-4 dazu
 * je Wecker der Zeitpunkt seiner letzten Änderung ([stamps]; fehlt einer, gilt [updatedAt], siehe [stampOf]) und die
 * gelöschten Wecker mit dem Zeitpunkt des Löschens ([deleted]). [perAlarm] `false`: Stand einer früheren Version ohne
 * diese Angaben; beim Übernehmen gilt dann «neuere Liste gewinnt».
 */
data class AlarmSnapshot(
    val alarms: List<AlarmSpec>,
    val updatedAt: Long,
    val stamps: Map<String, Long> = emptyMap(),
    val deleted: Map<String, Long> = emptyMap(),
    val perAlarm: Boolean = true,
) {
    /** Zeitpunkt der letzten Änderung des Weckers [id]. */
    fun stampOf(id: String): Long = stamps[id] ?: updatedAt
}

/**
 * Speichert die Wecker lokal. Handy und Uhr haben je eine eigene Kopie; [applyRemote] führt die Kopie der Gegenseite
 * je Wecker zusammen (Audit A-4, [AlarmStamps.merge]): Die neuere Änderung jedes Weckers gewinnt, ein Löschen gilt mit
 * seinem Zeitpunkt. So gehen bei gleichzeitigen Änderungen auf beiden Geräten (z. B. Einmal-Wecker nach dem Klingeln
 * auf der Uhr ausgeschaltet, am Handy ein anderer Wecker bearbeitet) keine Wecker oder Änderungen verloren.
 */
class AlarmRepository(
    private val store: DataStore<Preferences>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val snapshot: Flow<AlarmSnapshot> = store.data
        .map { prefs ->
            val list = decode(prefs[ALARMS]).orEmpty()
            val ids = list.map { it.id }.toSet()
            AlarmSnapshot(list, prefs[UPDATED_AT] ?: 0L, AlarmStamps.decodeMap(prefs[STAMPS]).filterKeys { it in ids }, AlarmStamps.decodeMap(prefs[DELETED]))
        }
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
        store.edit { prefs -> result = replaceAllIn(prefs, alarms) }
        return result
    }

    /**
     * Wie [replaceAll], aber in [prefs] eines laufenden `edit` desselben Speichers: Das Wiederherstellen schreibt
     * Wecker, Notizen und Einstellungen in einem Schritt (alles oder nichts). @return neuer Stand.
     */
    fun replaceAllIn(prefs: MutablePreferences, alarms: List<AlarmSpec>): AlarmSnapshot {
        // Höchstens [AlarmSpec.MAX_ALARMS], die ersten der Sicherung (Audit DA-8, wie `BackupIO` am iPhone).
        val sorted = alarms.distinctBy { it.id }.take(AlarmSpec.MAX_ALARMS).sortedWith(ORDER)
        val stamp = TimeBounds.next(clock(), prefs[UPDATED_AT])
        // Alle Wecker der Sicherung gelten als jetzt geändert; die bisherigen, die fehlen, als jetzt gelöscht.
        val before = decode(prefs[ALARMS]).orEmpty()
        return write(prefs, sorted, stamp, before, changed = sorted.map { it.id }.toSet())
    }

    /**
     * Ergebnis von [receive]: [changed], ob sich die Wecker hier geändert haben; [reply], der eigene Stand nach dem
     * Zusammenführen, falls er von dem empfangenen abweicht (sonst `null`).
     */
    data class Received(val changed: Boolean, val reply: AlarmSnapshot?)

    /** Wie [receive], nur ob sich die Wecker hier geändert haben. */
    suspend fun applyRemote(remote: AlarmSnapshot): Boolean = receive(remote).changed

    /**
     * Übernimmt den Stand der Gegenseite je Wecker ([AlarmStamps.merge], Audit A-4). Ein Stand einer früheren Version
     * ohne Zeitpunkte je Wecker ([AlarmSnapshot.perAlarm] `false`) gilt wie bisher nur, wenn er neuer ist, und ersetzt
     * dann die Liste.
     *
     * Weicht der zusammengeführte Stand vom empfangenen ab ([differs]), kennt die Gegenseite eine Änderung von hier nicht
     * (z. B. ihre Nachricht ging verloren): [Received.reply] ist dann der eigene Stand, den der Aufrufer zurückschickt
     * (Audit AN-1). So kommen beide Geräte zum selben Stand; stimmt er überein, antwortet keine Seite mehr.
     */
    suspend fun receive(remote: AlarmSnapshot): Received {
        var applied = false
        var reply: AlarmSnapshot? = null
        store.edit { prefs ->
            val localStamp = prefs[UPDATED_AT] ?: 0L
            val localList = decode(prefs[ALARMS])
            if (!remote.perAlarm || localList == null) {
                // Ältere Gegenseite oder eigener Stand unlesbar: neuere Liste gewinnt.
                if (remote.updatedAt > localStamp) {
                    val before = localList.orEmpty()
                    val sorted = remote.alarms.distinctBy { it.id }.sortedWith(ORDER)
                    val stamps = sorted.associate { it.id to remote.stampOf(it.id) }
                    val gone = before.map { it.id }.filter { id -> sorted.none { it.id == id } }.associateWith { remote.updatedAt }
                    save(prefs, sorted, remote.updatedAt, stamps, AlarmStamps.decodeMap(prefs[DELETED]) + remote.deleted + gone)
                    applied = localList != sorted
                }
                return@edit
            }
            val now = clock()
            val local = AlarmSnapshot(localList, localStamp, fullStamps(prefs, localList, localStamp), AlarmStamps.decodeMap(prefs[DELETED]))
            val merged = AlarmStamps.merge(local, remote, now)
            val sorted = merged.alarms.sortedWith(ORDER)
            val same = sorted == localList && merged.stamps == local.stamps && merged.deleted == local.deleted && merged.updatedAt == localStamp
            val result = if (same) merged.copy(alarms = sorted) else save(prefs, sorted, merged.updatedAt, merged.stamps, merged.deleted)
            applied = !same && sorted != localList
            reply = result.takeIf { differs(it, remote, now) }
        }
        return Received(applied, reply)
    }

    private suspend fun mutate(change: (List<AlarmSpec>) -> List<AlarmSpec>): AlarmSnapshot {
        lateinit var result: AlarmSnapshot
        store.edit { prefs ->
            // Unlesbarer Stand (z. B. Format einer neueren Version): nicht mit einer leeren Liste überschreiben.
            val current = decode(prefs[ALARMS]) ?: throw CodecException("Gespeicherte Wecker nicht lesbar, Änderung verworfen")
            val updated = change(current).sortedWith(ORDER)
            // Streng steigend, damit zwei Änderungen in derselben Millisekunde unterscheidbar bleiben.
            val stamp = TimeBounds.next(clock(), prefs[UPDATED_AT])
            val old = current.associateBy { it.id }
            result = write(prefs, updated, stamp, current, changed = updated.filter { old[it.id] != it }.map { it.id }.toSet())
        }
        return result
    }

    /**
     * Schreibt [alarms] mit dem Listen-Zeitpunkt [stamp]: Wecker in [changed] bekommen [stamp] als eigenen Zeitpunkt,
     * die übrigen behalten ihren (ältere Daten ohne: der bisherige Listen-Zeitpunkt); Wecker aus [before], die fehlen,
     * gelten ab [stamp] als gelöscht.
     */
    private fun write(prefs: MutablePreferences, alarms: List<AlarmSpec>, stamp: Long, before: List<AlarmSpec>, changed: Set<String>): AlarmSnapshot {
        val previous = fullStamps(prefs, before, prefs[UPDATED_AT] ?: 0L)
        val stamps = alarms.associate { it.id to (if (it.id in changed) stamp else previous[it.id] ?: stamp) }
        val ids = alarms.map { it.id }.toSet()
        val deleted = AlarmStamps.decodeMap(prefs[DELETED]).filterKeys { it !in ids } +
            before.map { it.id }.filter { it !in ids }.associateWith { stamp }
        return save(prefs, alarms, stamp, stamps, deleted)
    }

    private fun save(prefs: MutablePreferences, alarms: List<AlarmSpec>, stamp: Long, stamps: Map<String, Long>, deleted: Map<String, Long>): AlarmSnapshot {
        val ids = alarms.map { it.id }.toSet()
        val kept = AlarmStamps.prune(deleted.filterKeys { it !in ids }, clock())
        val own = stamps.filterKeys { it in ids }
        prefs[ALARMS] = Codec.encodeAlarms(alarms)
        prefs[UPDATED_AT] = stamp
        prefs[STAMPS] = AlarmStamps.encodeMap(own)
        prefs[DELETED] = AlarmStamps.encodeMap(kept)
        return AlarmSnapshot(alarms, stamp, own, kept)
    }

    /** Zeitpunkt je Wecker in [alarms]: gespeichert, sonst (ältere Daten) [listStamp]. */
    private fun fullStamps(prefs: Preferences, alarms: List<AlarmSpec>, listStamp: Long): Map<String, Long> {
        val saved = AlarmStamps.decodeMap(prefs[STAMPS])
        return alarms.associate { it.id to (saved[it.id] ?: listStamp) }
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

    companion object {
        private const val TAG = "AlarmRepository"
        private val ALARMS = stringPreferencesKey("alarms")
        private val UPDATED_AT = longPreferencesKey("alarms_updated_at")

        /** Zeitpunkt je Wecker und gelöschte Wecker ([AlarmStamps.encodeMap]), Audit A-4. */
        private val STAMPS = stringPreferencesKey("alarms_stamps")
        private val DELETED = stringPreferencesKey("alarms_deleted")

        /** Anzeige- und Speicherreihenfolge: nach Uhrzeit, dann Kennung. */
        private val ORDER = compareBy<AlarmSpec>({ it.hour }, { it.minute }, { it.id })

        /**
         * Ob der eigene Stand [own] nach dem Zusammenführen vom empfangenen [remote] abweicht (Audit AN-1): andere Wecker,
         * ein anderer Zeitpunkt je Wecker oder andere gelöschte Wecker (beide wie beim Speichern um [now] gekürzt). Der
         * Listen-Zeitpunkt zählt nicht: Er allein ändert beim Zusammenführen nichts.
         */
        fun differs(own: AlarmSnapshot, remote: AlarmSnapshot, now: Long): Boolean {
            val theirs = remote.alarms.associateBy { it.id }
            if (own.alarms.sortedWith(ORDER) != theirs.values.sortedWith(ORDER)) return true
            if (own.alarms.any { own.stampOf(it.id) != remote.stampOf(it.id) }) return true
            val ids = own.alarms.map { it.id }.toSet()
            return AlarmStamps.prune(own.deleted.filterKeys { it !in ids }, now) != AlarmStamps.prune(remote.deleted.filterKeys { it !in ids }, now)
        }
    }
}
