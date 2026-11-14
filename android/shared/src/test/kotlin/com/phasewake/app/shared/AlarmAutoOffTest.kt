package com.phasewake.app.shared

import com.phasewake.app.core.AlarmSpec
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Auto-Aus des Einmal-Weckers nach dem Klingeln (Fix G-1/S-1): trifft nur die Fassung, die geklingelt hat, und
 * überschreibt nie eine neuere Änderung der Gegenseite. Wie `AlarmStampsTests` (Swift).
 */
class AlarmAutoOffTest {
    private val zone: ZoneId = ZoneOffset.UTC

    /** 14.11.2026 hh:mm UTC in ms. */
    private fun at(hour: Int, minute: Int = 0, day: Int = 14): Long =
        ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun alarm(hour: Int, minute: Int = 0, days: Set<DayOfWeek> = emptySet(), enabled: Boolean = true) =
        AlarmSpec(id = "a", hour = hour, minute = minute, days = days, enabled = enabled)

    private class Device(start: Long) {
        var now = start
        val repo = AlarmRepository(MemoryStore()) { now }
    }

    /** Beide Geräte mit Wecker «a» um 07:00, am Abend davor gestellt und abgeglichen. */
    private suspend fun pair(): Pair<Device, Device> {
        val phone = Device(at(20, day = 13))
        val watch = Device(at(20, day = 13))
        watch.repo.receive(phone.repo.upsert(alarm(7)))
        return phone to watch
    }

    private suspend fun exchange(a: Device, b: Device) {
        repeat(3) {
            a.repo.receive(b.repo.current()).reply?.let { b.repo.receive(it) }
            b.repo.receive(a.repo.current()).reply?.let { a.repo.receive(it) }
        }
    }

    private fun rangAt7(spec: AlarmSpec) = AlarmStamps.rang(spec, null, Instant.ofEpochMilli(at(7)), zone, shifted = false)

    @Test
    fun s1_changeOnPhoneWhileWatchUnreachableSurvivesAutoOff() = runTest {
        val (phone, watch) = pair()
        // 23:00 am Handy auf 08:00 verschoben; die Uhr ist nicht erreichbar.
        phone.now = at(23, day = 13)
        phone.repo.upsert(alarm(8))
        // 07:05: Die Uhr klingelt nach altem Stand und schaltet ihn aus.
        watch.now = at(7, 5)
        phone.now = at(7, 5)
        assertNotNull(watch.repo.disableAfterRing("a", ::rangAt7))
        exchange(phone, watch)
        for (device in listOf(phone, watch)) {
            val a = device.repo.current().alarms.single()
            assertEquals(8 to true, a.hour to a.enabled, "die neuere Änderung vom Handy gilt auf beiden Geräten")
        }
    }

    @Test
    fun autoOffWithoutNewerChangeReachesTheOtherDevice() = runTest {
        val (phone, watch) = pair()
        watch.now = at(7, 5)
        phone.now = at(7, 5)
        val off = assertNotNull(watch.repo.disableAfterRing("a", ::rangAt7))
        assertFalse(off.alarms.single().enabled)
        exchange(phone, watch)
        assertFalse(phone.repo.current().alarms.single().enabled)
        assertFalse(watch.repo.current().alarms.single().enabled)
    }

    @Test
    fun autoOffStampIsTheRungVersionPlusOneMillisecond() = runTest {
        val (_, watch) = pair()
        val before = watch.repo.current()
        watch.now = at(7, 5)
        val off = assertNotNull(watch.repo.disableAfterRing("a", ::rangAt7))
        assertEquals(before.stampOf("a") + 1, off.stampOf("a"))
        // Listen-Zeitpunkt wie jede Änderung streng steigend «jetzt».
        assertEquals(at(7, 5), off.updatedAt)
    }

    @Test
    fun g1_versionMovedAfterTheRingIsNotSwitchedOff() = runTest {
        val (_, watch) = pair()
        val rung = watch.repo.current().alarms.single()
        // Im Schlummern auf 07:30 verschoben.
        watch.now = at(7, 3)
        watch.repo.upsert(alarm(7, 30))
        watch.now = at(7, 10)
        val target = Instant.ofEpochMilli(at(7))
        assertNull(watch.repo.disableAfterRing("a") { AlarmStamps.rang(it, rung, target, zone, shifted = false) })
        assertTrue(watch.repo.current().alarms.single().enabled)
        // Ohne bekannte Fassung (Neustart): die Zeitregel erkennt die Verschiebung ebenso.
        assertNull(watch.repo.disableAfterRing("a") { AlarmStamps.rang(it, null, target, zone, shifted = false) })
        assertTrue(watch.repo.current().alarms.single().enabled)
    }

    @Test
    fun repeatingOrDisabledAlarmsAreNeverSwitchedOff() = runTest {
        val repo = AlarmRepository(MemoryStore()) { at(6) }
        repo.upsert(alarm(7, days = setOf(DayOfWeek.SATURDAY)))
        assertNull(repo.disableAfterRing("a") { true })
        repo.upsert(alarm(7, enabled = false))
        assertNull(repo.disableAfterRing("a") { true })
        assertNull(repo.disableAfterRing("missing") { true })
    }

    @Test
    fun rangRules() {
        val target = Instant.ofEpochMilli(at(7))
        // Bekannte Fassung: Uhrzeit und Tage entscheiden.
        assertTrue(AlarmStamps.rang(alarm(7).copy(label = "neu"), alarm(7), target, zone, shifted = false))
        assertFalse(AlarmStamps.rang(alarm(7, 1), alarm(7), target, zone, shifted = false))
        assertFalse(AlarmStamps.rang(alarm(7, days = setOf(DayOfWeek.MONDAY)), alarm(7), target, zone, shifted = false))
        // Unbekannt: eingestellte Weckzeit = Zielzeit.
        assertTrue(AlarmStamps.rang(alarm(7), null, target, zone, shifted = false))
        assertFalse(AlarmStamps.rang(alarm(7, 1), null, target, zone, shifted = false))
        assertFalse(AlarmStamps.rang(alarm(6, 59), null, target, zone, shifted = false))
        // Früher geweckt (Einschlaf-Modus): eingestellte Weckzeit bis 16 Std. nach der Zielzeit.
        val early = Instant.ofEpochMilli(at(6, 20))
        assertTrue(AlarmStamps.rang(alarm(7), null, early, zone, shifted = true))
        assertFalse(AlarmStamps.rang(alarm(7), null, early, zone, shifted = false))
        val sixteen = Instant.ofEpochMilli(at(7) - 16 * 3_600_000L)
        assertTrue(AlarmStamps.rang(alarm(7), null, sixteen, zone, shifted = true))
        assertFalse(AlarmStamps.rang(alarm(7), null, sixteen.minusMillis(1), zone, shifted = true))
    }

    @Test
    fun shiftLimitIsSixteenHours() {
        assertEquals(57_600_000L, AlarmStamps.SHIFT_MAX_AHEAD_MS)
    }

    @Test
    fun autoOffWithoutPlausibleStampUsesTheListStamp() {
        val local = AlarmSnapshot(listOf(alarm(7)), 5_000L, mapOf("a" to Long.MAX_VALUE))
        val off = assertNotNull(AlarmStamps.autoOff(local, "a", 6_000L) { true })
        assertEquals(6_000L, off.stampOf("a"))
        assertFalse(off.alarms.single().enabled)
    }
}
