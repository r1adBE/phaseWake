package com.phasewake.app.shared

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.CodecException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DataStore im Speicher: gleiche Schnittstelle, ohne Datei und ohne Android. */
private class MemoryStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)
    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        transform(state.value).also { state.value = it }
}

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
    fun remoteIsAppliedOnlyWhenNewer() = runTest {
        repo.upsert(alarm("a"))
        assertFalse(repo.applyRemote(AlarmSnapshot(listOf(alarm("old")), 1_000L)))
        assertEquals(listOf("a"), repo.current().alarms.map { it.id })
        assertTrue(repo.applyRemote(AlarmSnapshot(listOf(alarm("new")), 2_000L)))
        assertEquals(AlarmSnapshot(listOf(alarm("new")), 2_000L), repo.current())
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
