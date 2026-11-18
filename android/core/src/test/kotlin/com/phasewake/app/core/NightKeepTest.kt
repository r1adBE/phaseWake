package com.phasewake.app.core

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Verlauf 2 Jahre auf dem Handy, 90 Nächte auf der Uhr; Kennzahlen weiter über 90 Nächte bzw. Tage (wie Swift). */
class NightKeepTest {
    private fun night(wokeAt: Instant, id: String = "a") = NightRecord(
        alarmId = id,
        windowStart = wokeAt.minus(Duration.ofMinutes(20)),
        target = wokeAt.plus(Duration.ofMinutes(10)),
        wokeAt = wokeAt,
        reason = WakeReason.LIGHT_SLEEP,
        snoozes = 0,
        epochs = emptyList(),
    )

    @Test
    fun documentedNumbers() {
        assertEquals(730L, NightHistory.DAYS)
        assertEquals(800, NightHistory.PHONE_NIGHTS)
        assertEquals(90, NightHistory.WATCH_NIGHTS)
        assertEquals(90, NightHistory.INSIGHT_NIGHTS)
        assertEquals(731, NightHistory.MAX_DATES)
        assertEquals(Duration.ofDays(730), NightKeep.PHONE.maxAge)
        assertEquals(null, NightKeep.WATCH.maxAge)
    }

    @Test
    fun phoneKeepsExactlySevenHundredThirtyDays() {
        val now = Instant.parse("2026-11-18T08:00:00Z")
        val edge = now.minus(Duration.ofDays(730))
        val nights = listOf(night(edge.minusMillis(1), "alt"), night(edge, "grenze"), night(now, "neu"))
        // Genau 730 Tage: bleibt; 1 ms älter: weg.
        assertEquals(listOf("grenze", "neu"), NightKeep.PHONE.trim(nights, now).map { it.alarmId })
    }

    @Test
    fun leapYearMakesTwoCalendarYearsOneDayLonger() {
        // 2028 ist ein Schaltjahr: 730 Tage vor dem 01.03.2028 ist der 02.03.2026, nicht der 01.03.2026.
        val now = Instant.parse("2028-03-01T12:00:00Z")
        val twoYears = night(Instant.parse("2026-03-01T12:00:00Z"), "zwei-jahre")
        val edge = night(Instant.parse("2026-03-02T12:00:00Z"), "730-tage")
        assertEquals(listOf("730-tage"), NightKeep.PHONE.trim(listOf(twoYears, edge), now).map { it.alarmId })
        // Ohne Schalttag dazwischen (2028-03-01 bis 2030-03-01) sind 730 Tage genau zwei Kalenderjahre.
        val later = Instant.parse("2030-03-01T12:00:00Z")
        val kept = NightKeep.PHONE.trim(listOf(night(Instant.parse("2028-03-01T12:00:00Z"))), later)
        assertEquals(1, kept.size)
    }

    @Test
    fun phoneCapsTheNumberOfNights() {
        val now = Instant.parse("2026-11-18T08:00:00Z")
        // Zwei Wecker je Nacht über ein Jahr: 900 Nächte, alle jünger als 2 Jahre; die jüngsten 800 bleiben.
        val nights = (0L until 900L).map { night(now.minus(Duration.ofHours(it * 9)), "n$it") }
        val kept = NightKeep.PHONE.trim(nights, now)
        assertEquals(NightHistory.PHONE_NIGHTS, kept.size)
        assertEquals("n799", kept.first().alarmId)
        assertEquals("n0", kept.last().alarmId)
        assertTrue(kept.zipWithNext().all { (a, b) -> a.windowStart <= b.windowStart }, "älteste zuerst")
    }

    @Test
    fun watchKeepsNinetyRegardlessOfAge() {
        val now = Instant.parse("2026-11-18T08:00:00Z")
        val nights = (0L until 95L).map { night(now.minus(Duration.ofDays(1_000 + it)), "n$it") }
        val kept = NightKeep.WATCH.trim(nights, now)
        assertEquals(90, kept.size)
        assertEquals("n89", kept.first().alarmId)
    }

    @Test
    fun latestTakesTheNewestNinety() {
        val now = Instant.parse("2026-11-18T08:00:00Z")
        val nights = (0L until 700L).map { night(now.minus(Duration.ofDays(it)), "n$it") }
        val latest = NightHistory.latest(nights)
        assertEquals(90, latest.size)
        assertEquals(listOf("n89", "n0"), listOf(latest.first().alarmId, latest.last().alarmId))
        assertEquals(14, NightHistory.latest(nights, 14).size)
    }

    @Test
    fun csvExportsTheWholeHistory() {
        val now = Instant.parse("2026-11-18T08:00:00Z")
        val nights = (0L until 120L).map {
            night(now.minus(Duration.ofDays(it))).copy(epochs = listOf(Epoch(0.1, 50.0)))
        }
        val rows = NightHistory.exportCsv(nights, zone = ZoneId.of("Europe/Zurich")).trimEnd().lines()
        assertEquals(121, rows.size, "Kopf und je Nacht eine Epoche")
        assertTrue(rows.last().startsWith("120,"))
    }

    @Test
    fun healthReadsTwoYearsInsightsOnlyNinetyDays() {
        val now = Instant.parse("2026-11-18T08:00:00Z")
        // Gelesen wird so weit wie der Verlauf (ein Tag mehr, damit die älteste Nacht ganz drin ist).
        assertEquals(730L, HealthHistory.DAYS)
        fun session(endDaysAgo: Long) = HealthSession(
            now.minus(Duration.ofDays(endDaysAgo)).minus(Duration.ofHours(8)), now.minus(Duration.ofDays(endDaysAgo)),
        )
        val sessions = listOf(session(400), session(92), session(91), session(90), session(1))
        val nights = (0L until 200L).map { night(now.minus(Duration.ofDays(it)), "n$it") }
        val (recent, recentSessions) = HealthHistory.forInsights(nights, sessions, now)
        assertEquals(90, recent.size)
        // Ende echt nach jetzt − 91 Tage.
        assertEquals(listOf(session(90), session(1)), recentSessions)
        // Mit Vorlauf für die Phasen: bis vor die älteste der 90 Nächte (Fenster 89 Tage und 20 Min) minus 2 Tage.
        val (_, withBefore) = HealthHistory.forInsights(nights, sessions, now, Duration.ofDays(2))
        assertEquals(listOf(session(91), session(90), session(1)), withBefore)
    }
}
