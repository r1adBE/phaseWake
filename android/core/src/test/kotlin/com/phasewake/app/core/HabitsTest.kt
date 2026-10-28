package com.phasewake.app.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HabitsTest {
    private val zone: ZoneId = ZoneId.of("Europe/Zurich")
    private fun at(text: String): Instant = LocalDateTime.parse(text).atZone(zone).toInstant()

    // Mittwoch, 28. Oktober 2026.
    private val today = LocalDate.of(2026, 10, 28)

    /** Bettzeit am Abend des Nachtdatums um [hhmm] (nach Mitternacht: am Folgetag). */
    private fun bedtime(date: LocalDate, hhmm: String): Pair<LocalDate, Instant> {
        val time = java.time.LocalTime.parse(hhmm)
        val day = if (time.hour < 12) date.plusDays(1) else date
        return date to day.atTime(time).atZone(zone).toInstant()
    }

    @Test
    fun regularityAgainstMedianOfFourteenNights() {
        val until = LocalDate.of(2026, 10, 27)
        // 14 Nächte um 23:00, die letzten 7 davon: 23:20, 23:30, 23:31, 00:10, 22:45, 23:00, 22:29.
        val older = (7L until 14L).map { bedtime(until.minusDays(it), "23:00") }
        val week = listOf("23:20", "23:30", "23:31", "00:10", "22:45", "23:00", "22:29")
            .mapIndexed { i, t -> bedtime(until.minusDays(6L - i), t) }
        // Median aller 14: 23:00 → innerhalb ± 30 Min: 23:20, 23:30, 22:45, 23:00 = 4.
        assertEquals(4 to 7, Regularity.count(older + week, zone))
        assertEquals(4 to 7, Regularity.count(older + week, zone, until = until))
        // Lücken zählen nicht mit; doppeltes Datum: die früheste Bettzeit.
        val sparse = listOf(bedtime(until, "23:40"), bedtime(until, "23:00"), bedtime(until.minusDays(1), "23:10"))
        assertEquals(2 to 2, Regularity.count(sparse, zone))
        assertEquals(1 to 1, Regularity.count(sparse, zone, until = until.minusDays(1)))
        assertEquals(0 to 0, Regularity.count(emptyList(), zone))
        // Mitternacht dazwischen: 23:50 und 00:15 liegen 25 Min auseinander.
        assertEquals(15.0 * 60, Regularity.minutesFromNoon(at("2026-10-28T03:00"), zone))
        assertEquals(2 to 2, Regularity.count(listOf(bedtime(until, "23:50"), bedtime(until.minusDays(1), "00:15")), zone))
    }

    @Test
    fun streakCurrentAndLongest() {
        val reached = mapOf(
            today.minusDays(1) to true, today.minusDays(2) to true, today.minusDays(3) to true,
            today.minusDays(4) to false,
            today.minusDays(5) to true, today.minusDays(6) to true, today.minusDays(7) to true, today.minusDays(8) to true,
            // Lücke (keine Daten) bei −9.
            today.minusDays(10) to true,
        )
        // Abend: heute noch ohne Daten → ab gestern.
        assertEquals(3, Streak.current(reached, today))
        // Heute schon verfehlt: 0.
        assertEquals(0, Streak.current(reached + (today to false), today))
        assertEquals(4, Streak.current(reached + (today to true), today))
        assertEquals(4, Streak.longest(reached, today))
        // Ausserhalb von 90 Tagen zählt nicht.
        val old = (100L until 110L).associate { today.minusDays(it) to true }
        assertEquals(0, Streak.longest(old, today))
        assertEquals(0, Streak.current(emptyMap(), today))
    }

    @Test
    fun streakFromSleeps() {
        val sleeps = listOf(
            SleepSummary(at("2026-10-26T23:00"), at("2026-10-27T07:00"), 470),
            SleepSummary(at("2026-10-27T23:00"), at("2026-10-28T06:00"), 400),
        )
        assertEquals(mapOf(LocalDate.of(2026, 10, 26) to true, LocalDate.of(2026, 10, 27) to false), Streak.reachedByNight(sleeps, zone, 450))
    }

    @Test
    fun weeklyWindowAndNotificationTime() {
        // Sonntag 25. 10.: Woche Sonntag 18. bis Samstag 24. (Nachtdaten); Montag 26.: dieselbe.
        assertEquals(LocalDate.of(2026, 10, 24), WeeklySummary.weekEnd(LocalDate.of(2026, 10, 25)))
        assertEquals(LocalDate.of(2026, 10, 24), WeeklySummary.weekEnd(LocalDate.of(2026, 10, 26)))
        assertTrue(WeeklySummary.cardVisible(LocalDate.of(2026, 10, 25)))
        assertTrue(WeeklySummary.cardVisible(LocalDate.of(2026, 10, 26)))
        assertFalse(WeeklySummary.cardVisible(today))
        assertEquals(at("2026-11-01T18:00"), WeeklySummary.nextNotification(at("2026-10-28T12:00"), zone))
        assertEquals(at("2026-11-01T18:00"), WeeklySummary.nextNotification(at("2026-10-25T18:00"), zone))
        assertEquals(at("2026-10-25T18:00"), WeeklySummary.nextNotification(at("2026-10-25T17:59"), zone))
    }

    private fun night(woke: Instant, feeling: Int) =
        NightRecord("a", woke.minus(Duration.ofMinutes(30)), woke, woke, WakeReason.LIGHT_SLEEP, 0, emptyList(), feeling)

    @Test
    fun weeklySummaryOfLastWeek() {
        val end = LocalDate.of(2026, 10, 24)
        // Sieben Nächte (18.–24.) um 23:00, abwechselnd 8 h und 7 h; dazu eine Nacht der Folgewoche.
        val sleeps = (0L until 7L).map { i ->
            val date = end.minusDays(i)
            val start = date.atTime(23, 0).atZone(zone).toInstant()
            val minutes = if (i % 2 == 0L) 480L else 420L
            SleepSummary(start, start.plus(Duration.ofMinutes(minutes + 10)), minutes)
        } + SleepSummary(at("2026-10-25T23:00"), at("2026-10-26T07:00"), 480)
        val nights = listOf(
            night(at("2026-10-20T06:30"), feeling = 4),
            night(at("2026-10-22T06:30"), feeling = 2),
            night(at("2026-10-27T06:30"), feeling = 1),
        )
        val summary = WeeklySummary.of(nights, sleeps, emptyMap(), zone, 450, end)
        assertEquals(LocalDate.of(2026, 10, 18), summary.from)
        assertEquals((4 * 480L + 3 * 420L) / 7, summary.asleepAverage)
        assertEquals(4, summary.goalReached)
        assertEquals(7, summary.nightsWithSleep)
        assertEquals(7 to 7, summary.regular to summary.regularCounted)
        assertEquals(3.0, summary.feelingAverage!!, 1e-9)
        assertNull(summary.strongestNote)
        assertTrue(summary.hasData)
        val empty = WeeklySummary.of(emptyList(), emptyList(), emptyMap(), zone, 450, end)
        assertFalse(empty.hasData)
        assertNull(empty.asleepAverage)
    }

    @Test
    fun routineItemsAndAverage() {
        assertEquals(Routine.DEFAULTS, Routine.decodeItems(null))
        assertEquals(emptyList(), Routine.decodeItems(""))
        val items = Routine.DEFAULTS + RoutineItem(Routine.customId("1"), "Buch | lesen")
        val text = Routine.encodeItems(items)
        assertEquals("PHONE|\nLIGHT|\nWATER|\ncustom:1|Buch   lesen", text)
        assertEquals(listOf("PHONE", "LIGHT", "WATER", "custom:1"), Routine.decodeItems(text).map { it.id })
        // Unbekannte und leere eigene Punkte fallen weg, höchstens 8.
        assertEquals(listOf("PHONE"), Routine.decodeItems("PHONE|\nFOO|x\ncustom:2|").map { it.id })
        assertEquals(8, Routine.decodeItems((1..10).joinToString("\n") { "custom:$it|P$it" }).size)

        var ticks = emptyMap<LocalDate, List<String>>()
        assertNull(Routine.average(ticks, Routine.DEFAULTS, today))
        ticks = Routine.toggle(ticks, today, Routine.PHONE)
        ticks = Routine.toggle(ticks, today, Routine.LIGHT)
        ticks = Routine.toggle(ticks, today.minusDays(1), Routine.PHONE)
        assertNull(Routine.average(ticks, Routine.DEFAULTS, today))
        ticks = Routine.toggle(ticks, today.minusDays(2), Routine.PHONE)
        ticks = Routine.toggle(ticks, today.minusDays(2), Routine.WATER)
        ticks = Routine.toggle(ticks, today.minusDays(2), Routine.LIGHT)
        // (2 + 1 + 3) / 3
        assertEquals(2.0, Routine.average(ticks, Routine.DEFAULTS, today)!!, 1e-9)
        // Abhaken rückgängig: Datum fällt weg.
        assertEquals(listOf(Routine.LIGHT), Routine.toggle(ticks, today, Routine.PHONE)[today])
        assertEquals(ticks, Routine.decodeTicks(Routine.encodeTicks(ticks)))
        // Gelöschter Punkt zählt nicht mehr.
        assertNull(Routine.average(ticks, listOf(RoutineItem(Routine.WATER)), today))
    }

    @Test
    fun mathChallengeNeedsThreeCorrectAnswers() {
        val challenge = MathChallenge(Random(7))
        assertEquals(1, challenge.step)
        val first = challenge.problem
        assertTrue(first.a in 10..99 && first.b in 10..99)
        // Falsch: neue Aufgabe, gleicher Schritt.
        assertFalse(challenge.submit(first.answer + 1))
        assertEquals(1, challenge.step)
        assertTrue(challenge.problem != first)
        repeat(3) {
            assertFalse(challenge.done)
            assertTrue(challenge.submit(challenge.problem.answer))
        }
        assertTrue(challenge.done)
        assertEquals(3, challenge.solved)
        assertEquals(3, challenge.step)
        assertTrue(challenge.submit(0))
    }
}
