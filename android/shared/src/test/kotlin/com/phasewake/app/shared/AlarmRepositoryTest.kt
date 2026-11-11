package com.phasewake.app.shared

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.CodecException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AlarmRepositoryTest {
    private var now = 1_000L
    private val store = MemoryStore()
    private val repo = AlarmRepository(store) { now }

    private fun alarm(id: String, hour: Int = 7, minute: Int = 0) = AlarmSpec(id = id, hour = hour, minute = minute)

    @Test
    fun emptyStoreHasNoAlarms() = runTest {
        assertEquals(AlarmSnapshot(emptyList(), 0L), repo.current())
    }

    @Test
    fun upsertSortsByTimeAndReplacesSameId() = runTest {
        repo.upsert(alarm("b", 8))
        repo.upsert(alarm("a", 6, 30))
        assertEquals(listOf("a", "b"), repo.current().alarms.map { it.id })
        repo.upsert(alarm("b", 5))
        assertEquals(listOf("b" to 5, "a" to 6), repo.current().alarms.map { it.id to it.hour })
    }

    @Test
    fun removeAndEnable() = runTest {
        repo.upsert(alarm("a"))
        repo.upsert(alarm("b", 8))
        assertFalse(repo.setEnabled("a", false).alarms.first { it.id == "a" }.enabled)
        assertEquals(listOf("a"), repo.remove("b").alarms.map { it.id })
    }

    @Test
    fun stampIsStrictlyIncreasingWithinTheSameMillisecond() = runTest {
        val first = repo.upsert(alarm("a")).updatedAt
        val second = repo.upsert(alarm("b")).updatedAt
        assertEquals(1_000L, first)
        assertEquals(1_001L, second)
        now = 5_000L
        assertEquals(5_000L, repo.remove("a").updatedAt)
    }

    @Test
    fun legacyRemoteIsAppliedOnlyWhenNewer() = runTest {
        // Gegenseite einer früheren Version (ohne Zeitpunkte je Wecker): neuere Liste gewinnt wie bisher.
        repo.upsert(alarm("a"))
        assertFalse(repo.applyRemote(AlarmSnapshot(listOf(alarm("old")), 1_000L, perAlarm = false)))
        assertEquals(listOf("a"), repo.current().alarms.map { it.id })
        assertTrue(repo.applyRemote(AlarmSnapshot(listOf(alarm("new")), 2_000L, perAlarm = false)))
        val current = repo.current()
        assertEquals(listOf(alarm("new")), current.alarms)
        assertEquals(2_000L, current.updatedAt)
        assertEquals(mapOf("a" to 2_000L), current.deleted, "der ersetzte Wecker gilt als gelöscht")
    }

    @Test
    fun automaticChangeAndUserEditOnTheOtherDeviceBothSurvive() = runTest {
        // Audit A-4: Uhr schaltet den Einmal-Wecker «once» nach dem Klingeln aus, gleichzeitig legt der Nutzer am Handy
        // «new» an und ändert «daily». Früher gewann die neuere Liste, und eine der Änderungen ging verloren.
        val phone = repo
        var watchNow = 1_000L
        val watch = AlarmRepository(MemoryStore()) { watchNow }
        val base = phone.replaceAll(listOf(alarm("once"), alarm("daily", 6)))
        assertTrue(watch.applyRemote(base))
        assertEquals(base.alarms, watch.current().alarms)

        now = 5_000L
        watchNow = 5_000L
        val fromWatch = watch.setEnabled("once", false)
        now = 6_000L
        phone.upsert(alarm("new", 9))
        val fromPhone = phone.update("daily") { it.copy(minute = 15) }

        // Beide Nachrichten kreuzen sich; beide Seiten führen zusammen und kommen zum selben Stand.
        assertTrue(phone.applyRemote(fromWatch))
        assertTrue(watch.applyRemote(fromPhone))
        val expected = listOf(alarm("daily", 6, 15), alarm("once").copy(enabled = false), alarm("new", 9))
        assertEquals(expected, phone.current().alarms)
        assertEquals(expected, watch.current().alarms)
        // Nochmals derselbe Stand: ändert nichts.
        assertFalse(phone.applyRemote(fromWatch))
    }

    @Test
    fun deletionWinsOverOlderEditAndNewerEditWinsOverDeletion() = runTest {
        var watchNow = 1_000L
        val watch = AlarmRepository(MemoryStore()) { watchNow }
        watch.applyRemote(repo.replaceAll(listOf(alarm("a"), alarm("b", 8))))
        // Am Handy «a» gelöscht (später), auf der Uhr «a» geändert (früher): gelöscht bleibt gelöscht.
        watchNow = 2_000L
        val edited = watch.update("a") { it.copy(hour = 5) }
        now = 3_000L
        val removed = repo.remove("a")
        repo.applyRemote(edited)
        watch.applyRemote(removed)
        assertEquals(listOf("b"), repo.current().alarms.map { it.id })
        assertEquals(listOf("b"), watch.current().alarms.map { it.id })
        // Ein Wecker, den nur eine Seite kennt und nirgends gelöscht ist, bleibt (z. B. eben auf der Uhr angelegt).
        watchNow = 4_000L
        val added = watch.upsert(alarm("c", 9))
        assertTrue(repo.applyRemote(added))
        assertEquals(listOf("b", "c"), repo.current().alarms.map { it.id })
        // Neuere Änderung nach dem Löschen (Wiederherstellen einer Sicherung mit «a»): «a» ist wieder da.
        now = 5_000L
        val restored = repo.replaceAll(listOf(alarm("a"), alarm("b", 8), alarm("c", 9)))
        assertTrue(watch.applyRemote(restored))
        assertEquals(listOf("a", "b", "c"), watch.current().alarms.map { it.id })
    }

    @Test
    fun olderDataWithoutStampsKeepsItsListStamp() = runTest {
        // Gespeichert von einer früheren Version: nur der Listen-Zeitpunkt (7). Eine Änderung an «b» gibt nur «b» den
        // neuen Zeitpunkt; «a» behält 7 und verliert darum gegen eine neuere Fassung der Gegenseite.
        val old = MemoryStore(
            emptyPreferences().toMutablePreferences().apply {
                this[stringPreferencesKey("alarms")] = com.phasewake.app.core.Codec.encodeAlarms(listOf(alarm("a"), alarm("b", 8)))
                this[longPreferencesKey("alarms_updated_at")] = 7L
            },
        )
        val oldRepo = AlarmRepository(old) { now }
        val changed = oldRepo.update("b") { it.copy(minute = 30) }
        assertEquals(mapOf("a" to 7L, "b" to 1_000L), changed.stamps)
        assertTrue(oldRepo.applyRemote(AlarmSnapshot(listOf(alarm("a", 5)), 8L, mapOf("a" to 8L))))
        assertEquals(listOf(alarm("a", 5), alarm("b", 8, 30)), oldRepo.current().alarms)
    }

    @Test
    fun mergeIsTheSameOnBothSidesAndPrunesOldTombstones() {
        val left = AlarmSnapshot(listOf(alarm("a", 6), alarm("b")), 10L, mapOf("a" to 10L, "b" to 4L), mapOf("old" to 1L))
        val right = AlarmSnapshot(listOf(alarm("a", 5), alarm("c")), 9L, mapOf("a" to 10L, "c" to 9L), mapOf("b" to 5L))
        val now = AlarmStamps.TOMBSTONE_MAX_AGE_MS + 2L
        val one = AlarmStamps.merge(left, right, now)
        val other = AlarmStamps.merge(right, left, now)
        assertEquals(one.alarms.sortedBy { it.id }, other.alarms.sortedBy { it.id })
        assertEquals(one.stamps, other.stamps)
        assertEquals(one.deleted, other.deleted)
        assertEquals(listOf("a", "c"), one.alarms.map { it.id }.sorted(), "b gelöscht (5 nach 4)")
        assertEquals(mapOf("b" to 5L), one.deleted, "zu alter Grabstein «old» weg")
        assertEquals(10L, one.updatedAt)
    }

    @Test
    fun unreadableStoreIsNotOverwritten() = runTest {
        val broken = MemoryStore(
            emptyPreferences().toMutablePreferences().apply {
                this[stringPreferencesKey("alarms")] = "phasewake-alarms 99"
                this[longPreferencesKey("alarms_updated_at")] = 7L
            },
        )
        val brokenRepo = AlarmRepository(broken) { now }
        assertEquals(AlarmSnapshot(emptyList(), 7L), brokenRepo.current())
        assertFailsWith<CodecException> { brokenRepo.upsert(alarm("a")) }
        assertEquals("phasewake-alarms 99", broken.data.first()[stringPreferencesKey("alarms")])
    }
}
