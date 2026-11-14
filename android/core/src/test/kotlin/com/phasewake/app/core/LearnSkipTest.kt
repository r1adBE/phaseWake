package com.phasewake.app.core

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Meldung der Uhr ([LearnSkip]) und was das Handy für die Lern-Übersicht merkt ([LearnLog]); gleich wie `LearnSkipTests` (Swift). */
class LearnSkipTest {
    private val zone = ZoneId.of("Europe/Zurich")
    private val target = Instant.ofEpochMilli(1_800_000_000_000L)

    private fun at(day: Int, hour: Int): Instant = ZonedDateTime.of(2026, 11, day, hour, 0, 0, 0, zone).toInstant()

    @Test
    fun encodesAndDecodes() {
        val skip = LearnSkip(target, LearnSkip.Reason.BATTERY, 35)
        assertEquals("1800000000000|battery|35", skip.encode())
        assertEquals(skip, LearnSkip.decode(skip.encode()))
        assertEquals("1800000000000|sensors|", LearnSkip(target, LearnSkip.Reason.SENSORS).encode())
        assertEquals(LearnSkip(target, LearnSkip.Reason.MANUAL), LearnSkip.decode(" 1800000000000|manual\n"))
        assertEquals(LearnSkip(target, LearnSkip.Reason.LATE), LearnSkip.decode("1800000000000|late|"))
        assertEquals(LearnSkip(target, LearnSkip.Reason.BATTERY), LearnSkip.decode("1800000000000|battery|140"), "Akku unplausibel: unbekannt")
    }

    @Test
    fun unreadableOrUnknownIsNull() {
        assertNull(LearnSkip.decode(null))
        assertNull(LearnSkip.decode(""))
        assertNull(LearnSkip.decode("1800000000000"))
        assertNull(LearnSkip.decode("x|battery|20"))
        assertNull(LearnSkip.decode("0|battery|20"))
        assertNull(LearnSkip.decode("9999999999999999|battery|20"), "Zeitpunkt unplausibel")
        assertNull(LearnSkip.decode("1800000000000|later|20"), "unbekannter Grund einer neueren Uhr")
    }

    @Test
    fun skipsOnePerNightNewestFourteen() {
        var skips = emptyList<LearnSkip>()
        for (day in 1..20) skips = LearnLog.addSkip(skips, LearnSkip(at(day, 7), LearnSkip.Reason.BATTERY, day), zone)
        assertEquals(LearnNights.MAX_NIGHTS, skips.size)
        assertEquals(7, skips.first().battery)
        // Dieselbe Nacht: die neue Meldung ersetzt die frühere.
        skips = LearnLog.addSkip(skips, LearnSkip(at(20, 6), LearnSkip.Reason.SENSORS), zone)
        assertEquals(LearnNights.MAX_NIGHTS, skips.size)
        assertEquals(LearnSkip(at(20, 6), LearnSkip.Reason.SENSORS), skips.last())
        assertEquals(skips, LearnLog.decodeSkips(LearnLog.encodeSkips(skips)))
        assertEquals(emptyList(), LearnLog.decodeSkips("kaputt\n\n"))
    }

    @Test
    fun upcomingTargetsOfTheNextTwoWeeks() {
        val now = at(2, 12)
        val weekdays = AlarmSpec(id = "w", hour = 7, minute = 0, days = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY))
        val once = AlarmSpec(id = "o", hour = 6, minute = 30)
        val off = AlarmSpec(id = "x", hour = 5, minute = 0, days = setOf(DayOfWeek.SUNDAY), enabled = false)
        val targets = LearnLog.upcomingTargets(listOf(weekdays, once, off), now, zone, emptySet())
        // 2.11.2026 ist ein Montag: Di–Fr, Mo–Fr, Mo = 10 Werktage bis zum 16., dazu der einmalige am 3.
        assertEquals(11, targets.size)
        assertEquals(at(3, 6).plus(Duration.ofMinutes(30)), targets.first())
        assertTrue(targets.all { it.isAfter(now) && !it.isAfter(now.plus(Duration.ofDays(LearnLog.PLAN_DAYS))) })
        // Feiertag fällt mit skipHolidays weg.
        val holiday = LearnLog.upcomingTargets(listOf(weekdays.copy(skipHolidays = true)), now, zone, setOf(LocalDate.of(2026, 11, 4)))
        assertEquals(9, holiday.size)
    }

    @Test
    fun alarmNightsKeepThePastAndReplaceTheFuture() {
        val now = at(5, 12)
        val old = mapOf(
            LocalDate.of(2026, 11, 3) to at(4, 7),
            LocalDate.of(2026, 11, 5) to at(6, 7),
        )
        // Der Wecker der kommenden Nacht ist aus (nicht mehr im Plan), dafür einer in der übernächsten.
        val merged = LearnLog.mergeAlarmNights(old, listOf(at(7, 6), at(3, 7)), now, zone)
        assertEquals(mapOf(LocalDate.of(2026, 11, 3) to at(4, 7), LocalDate.of(2026, 11, 6) to at(7, 6)), merged)
        // Zwei Wecker derselben Nacht: der frühere.
        assertEquals(at(7, 5), LearnLog.mergeAlarmNights(emptyMap(), listOf(at(7, 6), at(7, 5)), now, zone)[LocalDate.of(2026, 11, 6)])
        // Höchstens 14 vergangene Nächte.
        val many = (1..30).associate { LocalDate.of(2026, 10, it) to at(1, 7).minus(Duration.ofDays((31 - it).toLong())) }
        assertEquals(LearnNights.MAX_NIGHTS, LearnLog.mergeAlarmNights(many, emptyList(), now, zone).size)
        assertEquals(merged, LearnLog.decodeAlarmNights(LearnLog.encodeAlarmNights(merged)))
        assertEquals("2026-11-03=${at(4, 7).toEpochMilli()}\n2026-11-06=${at(7, 6).toEpochMilli()}", LearnLog.encodeAlarmNights(merged))
        assertEquals(emptyMap(), LearnLog.decodeAlarmNights("2026-13-01=5\nx=1\n2026-11-01=abc"))
    }

    @Test
    fun alarmNightsKeepTheEarlierTargetAndTheBoundaryAtNow() {
        // Audit BE-4: zwei Wecker derselben Nacht, der frühere zuerst geplant – er bleibt (der spätere ersetzt ihn nicht).
        val now = at(5, 12)
        assertEquals(mapOf(LocalDate.of(2026, 11, 5) to at(6, 5)), LearnLog.mergeAlarmNights(emptyMap(), listOf(at(6, 5), at(6, 6)), now, zone))
        // Ein Eintrag genau zur Zeit des Neuplanens ist vergangen und bleibt, auch wenn der Plan ihn nicht mehr hat.
        val old = mapOf(LocalDate.of(2026, 11, 4) to now)
        assertEquals(old, LearnLog.mergeAlarmNights(old, emptyList(), now, zone))
        // Eine Zielzeit genau jetzt gehört nicht mehr zum Plan (nur echt künftige).
        assertEquals(emptyMap(), LearnLog.mergeAlarmNights(emptyMap(), listOf(now), now, zone))
        // Vergangene Nacht am selben Nachtdatum wie ein späterer zweiter Wecker: die vergangene (frühere) bleibt.
        val rang = mapOf(LocalDate.of(2026, 11, 5) to at(6, 5).minusSeconds(7 * 3600))
        val later = rang.values.single().plusSeconds(60 * 60 * 7 + 3600)
        assertEquals(rang, LearnLog.mergeAlarmNights(rang, listOf(later), rang.values.single().plusSeconds(60), zone))
    }
}
