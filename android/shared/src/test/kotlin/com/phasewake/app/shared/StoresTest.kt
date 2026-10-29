package com.phasewake.app.shared

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.HolidayList
import com.phasewake.app.core.SleepGoal
import com.phasewake.app.core.PhoneAlert
import com.phasewake.app.core.TestRun
import com.phasewake.app.core.TestStep
import com.phasewake.app.core.Transient
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Speicher für vorübergehende Pläne, Feiertage und den Probewecker; dazu neue Änderungen an den Weckern. */
class StoresTest {
    private val start = Instant.parse("2026-10-29T13:00:00Z")
    private var now = start

    @Test
    fun transientStoreKeepsOnePlanUntilExpired() = runTest {
        val store = TransientStore(MemoryStore()) { now }
        assertNull(store.active())
        val test = Transient.test(null, start, ZoneOffset.UTC, "1")
        store.set(test)
        assertEquals(test, store.active())
        assertEquals(test.spec, store.specFor("test-1"))
        assertNull(store.specFor("test-2"))
        assertNull(store.specFor("3f2a"))
        // Ein Powernap ersetzt den Probewecker.
        val nap = Transient.nap(null, start, ZoneOffset.UTC, 20, "2")
        store.set(nap)
        assertNull(store.specFor("test-1"))
        now = nap.plan.target.plus(Transient.ACTIVE_AFTER_TARGET)
        assertNull(store.active())
        assertEquals(nap, store.stored.first())
        assertFalse(store.clear("nap-anders"))
        assertTrue(store.clear("nap-2"))
        assertFalse(store.clear())
        assertNull(store.stored.first())
    }

    @Test
    fun staleNapIsNotPlannedAfterWatchComesBack() = runTest {
        // Nap aktiv, Uhr eine Stunde nach der Zielzeit wieder an (Akku, Neustart), Stopp kam nie an: neu planen
        // nimmt den Nap nicht mehr, die Weckart bleibt für ein laufendes Klingeln aber lesbar.
        val store = TransientStore(MemoryStore()) { now }
        val nap = Transient.nap(null, start, ZoneOffset.UTC, 20, "3")
        store.set(nap)
        assertEquals(nap.plan, Transient.choose(null, store.active(), now))
        now = nap.plan.target.plusSeconds(3_600)
        assertEquals(nap, store.active())
        assertNull(Transient.choose(null, store.active(), now))
        assertEquals(nap.spec, store.specFor("nap-3"))
    }

    @Test
    fun transientStoreIgnoresBrokenText() = runTest {
        val broken = MemoryStore(emptyPreferences().toMutablePreferences().apply { this[stringPreferencesKey("transient_plan")] = "kaputt" })
        val store = TransientStore(broken) { now }
        assertNull(store.active())
        assertTrue(store.clear())
    }

    @Test
    fun holidayStoreOnlyTakesNewerOnWatch() = runTest {
        val store = HolidayStore(MemoryStore())
        assertEquals(HolidayList("", emptySet()), store.list.first())
        assertEquals(0L, store.updatedAt())
        val ch = HolidayList("CH", setOf(LocalDate.of(2026, 12, 25)))
        assertTrue(store.set(ch, 10L))
        assertEquals(setOf(LocalDate.of(2026, 12, 25)), store.current())
        assertFalse(store.set(HolidayList("DE", emptySet()), 5L, onlyIfNewer = true))
        assertEquals(ch, store.list.first())
        assertFalse(store.set(ch, 11L, onlyIfNewer = true))
        assertEquals(11L, store.updatedAt())
        assertTrue(store.set(HolidayList("DE", emptySet()), 12L))
        assertEquals(emptySet(), store.current())
    }

    @Test
    fun sleepGoalStoreDefaultsAndOnlyTakesNewer() = runTest {
        val store = SleepGoalStore(MemoryStore())
        // Uhr ohne Handy: Standard 7½ Std.
        assertEquals(SleepGoal.DEFAULT_MINUTES, store.minutes.first())
        assertEquals(0L, store.updatedAt())
        assertTrue(store.setIfNewer(480, 10L))
        assertEquals(480, store.minutes.first())
        assertFalse(store.setIfNewer(360, 5L))
        assertFalse(store.setIfNewer(360, 10L))
        assertEquals(480, store.minutes.first())
        assertFalse(store.setIfNewer(480, 11L))
        assertEquals(11L, store.updatedAt())
        // Ausserhalb des Bereichs: auf 10 Std. begrenzt.
        assertTrue(store.setIfNewer(2_000, 12L))
        assertEquals(SleepGoal.MAX_MINUTES, store.minutes.first())
    }

    @Test
    fun testRunStoreRecordsOnlyItsTest() = runTest {
        val store = TestRunStore(MemoryStore())
        assertNull(store.current())
        store.record("test-1", TestStep.RECEIVED, start)
        assertNull(store.current())
        store.start(TestRun("test-1", start.plusSeconds(120), PhoneAlert.SOUND))
        store.record("test-1", TestStep.RECEIVED, start)
        store.record("test-2", TestStep.WATCH_RANG, start)
        store.record("3f2a", TestStep.WATCH_RANG, start)
        store.record(null, TestStep.STOP_ARRIVED, start)
        assertEquals(mapOf(TestStep.RECEIVED to start), store.current()?.seen)
        store.clear()
        assertNull(store.run.first())
    }

    @Test
    fun alarmUpdateAndReplaceAll() = runTest {
        val repo = AlarmRepository(MemoryStore()) { 100L }
        repo.upsert(AlarmSpec(id = "a", hour = 7, minute = 0))
        val updated = repo.update("a") { it.copy(skipUntil = 5L) }
        assertEquals(5L, updated.alarms.single().skipUntil)
        assertEquals(updated.alarms, repo.update("fehlt") { it.copy(hour = 1) }.alarms)
        val replaced = repo.replaceAll(listOf(AlarmSpec(id = "c", hour = 9, minute = 0), AlarmSpec(id = "b", hour = 6, minute = 0), AlarmSpec(id = "b", hour = 5, minute = 0)))
        assertEquals(listOf("b", "c"), replaced.alarms.map { it.id })
        assertTrue(replaced.updatedAt > updated.updatedAt)
        assertEquals(replaced, repo.current())
    }

    @Test
    fun replaceAllOverwritesUnreadableState() = runTest {
        val broken = MemoryStore(emptyPreferences().toMutablePreferences().apply { this[stringPreferencesKey("alarms")] = "phasewake-alarms 99" })
        val repo = AlarmRepository(broken) { 1L }
        assertEquals(listOf("x"), repo.replaceAll(listOf(AlarmSpec(id = "x", hour = 7, minute = 0))).alarms.map { it.id })
        assertEquals(1L, repo.current().updatedAt)
    }
}
