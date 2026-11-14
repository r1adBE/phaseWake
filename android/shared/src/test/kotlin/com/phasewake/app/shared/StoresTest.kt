package com.phasewake.app.shared

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.DetectorConfig
import com.phasewake.app.core.HolidayList
import com.phasewake.app.core.Learning
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
import kotlin.test.assertNotNull
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
    fun autoOnsetStoreTakesOnlyNewer() = runTest {
        val store = AutoOnsetStore(MemoryStore())
        // Uhr ohne Handy: an.
        assertTrue(store.enabled.first())
        assertTrue(store.setIfNewer(false, 10L))
        assertFalse(store.enabled.first())
        // Älter oder gleich alt: bleibt.
        assertFalse(store.setIfNewer(true, 10L))
        assertFalse(store.setIfNewer(true, 5L))
        assertFalse(store.enabled.first())
        // Neuer, aber gleich: gespeichert, keine Änderung.
        assertFalse(store.setIfNewer(false, 11L))
        assertTrue(store.setIfNewer(true, 12L))
        assertTrue(store.enabled.first())
    }

    @Test
    fun learningStoreTakesOnlyNewer() = runTest {
        val store = LearningStore(MemoryStore())
        // Uhr ohne Handy: keine Lernphase, keine Werte.
        assertEquals(LearningSnapshot(false, null, null, 0L), store.current())
        assertTrue(store.setIfNewer(LearningSnapshot(true, null, null, 10L)))
        assertTrue(store.current().active)
        // Älter oder gleich alt: bleibt.
        assertFalse(store.setIfNewer(LearningSnapshot(false, 0.02, 52.0, 10L)))
        assertTrue(store.current().active)
        // Fertig: Werte da, Lernphase vorbei; neu begonnen: Werte wieder weg.
        assertTrue(store.setIfNewer(LearningSnapshot(false, 0.02, 52.0, 11L)))
        assertEquals(LearningSnapshot(false, 0.02, 52.0, 11L), store.current())
        assertTrue(store.setIfNewer(LearningSnapshot(true, null, null, 12L)))
        assertEquals(LearningSnapshot(true, null, null, 12L), store.current())
        // Neuer, aber gleich: gespeichert, keine Änderung.
        assertFalse(store.setIfNewer(LearningSnapshot(true, null, null, 13L)))
        assertEquals(13L, store.current().updatedAt)
    }

    @Test
    fun learningSnapshotForTheDetector() {
        val values = Learning.Values(0.02, 52.0, 12, 7)
        val snapshot = LearningSnapshot.of(active = false, values = values, updatedAt = 5L)
        assertEquals(LearningSnapshot(false, 0.02, 52.0, 5L, latencyMinutes = 12), snapshot)
        val config = snapshot.applyTo(DetectorConfig())
        assertEquals(0.02, config.personalFloor)
        assertEquals(52.0, config.restingHeartRate)
        assertEquals(DetectorConfig(), LearningSnapshot.of(true, null, 6L).applyTo(DetectorConfig()))
        assertEquals("active=0 floor=0.0200 hr=set", snapshot.diagLine())
        assertEquals("active=1 floor=- hr=-", LearningSnapshot.of(true, null, 6L).diagLine())
    }

    @Test
    fun learningMessageWithFourOrFiveFields() = runTest {
        // O-03: 5. Feld Einschlafdauer; ohne Dauer weiter 4 Felder (eine frühere Uhr liest nur genau 4).
        val with = LearningSnapshot(true, 0.02, 52.0, 7L, latencyMinutes = 14)
        assertEquals("1|0.02|52.0|7|14", LearningSnapshot.encode(with))
        assertEquals(with, LearningSnapshot.decode(LearningSnapshot.encode(with)))
        assertEquals("1|0.02|52.0|7", LearningSnapshot.encode(with.copy(latencyMinutes = null)))
        // Frühere Handys: 4 Felder, keine Dauer.
        assertEquals(LearningSnapshot(true, 0.02, 52.0, 7L), LearningSnapshot.decode("1|0.02|52.0|7"))
        // Leer oder unplausibel (nicht 0–60): keine Dauer; 6 Felder: unlesbar.
        assertNull(assertNotNull(LearningSnapshot.decode("1|||7|")).latencyMinutes)
        assertNull(assertNotNull(LearningSnapshot.decode("1|||7|61")).latencyMinutes)
        assertNull(assertNotNull(LearningSnapshot.decode("1|||7|-1")).latencyMinutes)
        assertEquals(0, LearningSnapshot.decode("1|||7|0")?.latencyMinutes)
        assertEquals(60, LearningSnapshot.decode("1|||7|60")?.latencyMinutes)
        assertNull(LearningSnapshot.decode("1|||7|5|x"))
        // Die Uhr speichert die Dauer und gibt sie für den Ring heraus.
        val store = LearningStore(MemoryStore())
        assertTrue(store.setIfNewer(with))
        assertEquals(14, store.latencyMinutes.first())
        assertEquals(with, store.current())
        assertTrue(store.setIfNewer(with.copy(updatedAt = 8L, latencyMinutes = null)), "nur die Dauer weg: geändert")
        assertNull(store.latencyMinutes.first())
    }

    @Test
    fun invalidLearnedValuesCountAsNone() {
        // Audit K-6: NaN, negative oder absurde Werte aus einer Nachricht dürfen den Start des Fensters nicht abbrechen.
        val decoded = assertNotNull(LearningSnapshot.decode("1|NaN|NaN|7"))
        assertEquals(LearningSnapshot(true, null, null, 7L), decoded)
        assertEquals(LearningSnapshot(false, null, null, 8L), LearningSnapshot.decode("0|-0.5|1e9|8"))
        assertEquals(LearningSnapshot(false, null, null, 9L), LearningSnapshot.decode("0|Infinity|-Infinity|9"))
        // Auch ein Stand mit ungültigen Werten direkt (z. B. älter gespeichert): Detektor wie ohne gelernte Werte.
        val config = LearningSnapshot(true, Double.NaN, Double.NaN, 10L).applyTo(DetectorConfig())
        assertNull(config.personalFloor)
        assertNull(config.restingHeartRate)
        assertEquals(DetectorConfig(), config)
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
