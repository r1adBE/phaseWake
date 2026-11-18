package com.phasewake.app.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SleepGoalTest {
    private val zone: ZoneId = ZoneId.of("Europe/Zurich")
    private fun at(text: String): Instant = LocalDateTime.parse(text).atZone(zone).toInstant()
    private val day = LocalDate.of(2026, 10, 27)

    @Test
    fun goalIsClampedToRangeAndStep() {
        assertEquals(450, SleepGoal.DEFAULT_MINUTES)
        assertEquals(360, SleepGoal.clamp(100))
        assertEquals(600, SleepGoal.clamp(700))
        assertEquals(465, SleepGoal.clamp(460))
        assertEquals(450, SleepGoal.clamp(457))
        assertTrue(SleepGoal.reached(450, 450))
        assertFalse(SleepGoal.reached(449, 450))
    }

    @Test
    fun weekStatusCountsNewestSevenNights() {
        val nights = (0L until 10L).map { day.minusDays(it) to if (it % 2 == 0L) 480L else 400L }
        // Jüngste 7: Tage 0–6 → 0, 2, 4, 6 erreicht.
        assertEquals(4 to 7, SleepGoal.weekStatus(nights, 450))
        assertEquals(0 to 0, SleepGoal.weekStatus(emptyList(), 450))
        // Doppeltes Datum zählt einmal, der längere Eintrag gilt.
        assertEquals(1 to 2, SleepGoal.weekStatus(listOf(day to 300L, day to 460L, day.minusDays(1) to 200L), 450))
    }

    /**
     * Schlafziel der Startseite und der Trends (`goal_week`): die jüngsten 7 Nachtdaten aus dem Fenster der Kennzahlen
     * ([HealthHistory.forInsights], 90 Tage), nicht nur die Nächte der letzten 7 Tage. Gleich auf dem iPhone
     * (`HomeSleepWeekPhoneTests`).
     */
    @Test
    fun goalWeekTakesTheNewestSevenNightDatesOfTheInsightWindow() {
        val now = at("2026-11-18T12:00")
        fun session(daysBack: Long, minutes: Long): HealthSession {
            val start = at("2026-11-18T23:00").minus(java.time.Duration.ofDays(daysBack))
            val end = start.plus(java.time.Duration.ofMinutes(minutes))
            return HealthSession(start, end, listOf(StageSpan(start, end, SleepStage.LIGHT)))
        }
        // Letzte 7 Tage: 3 Nächte über dem Ziel; davor (10–14 Tage zurück) 5 Nächte darunter.
        val sessions = listOf(1L, 2L, 3L).map { session(it, 480) } + (10L..14L).map { session(it, 300) }
        val (nights, recent) = HealthHistory.forInsights(emptyList(), sessions, now)
        val sleeps = HealthHistory.summaries(recent, null, zone)
        val week = SleepWeek.of(
            Trends.compute(nights, sleeps, now, zone), SleepGoal.weekStatus(SleepGoal.nightsOf(sleeps, zone), 450),
        )
        assertEquals(SleepWeek(480, 3, 7), week)
    }

    private fun alarm(
        id: String,
        hour: Int,
        minute: Int,
        days: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
        sleep: Int? = null,
    ) =
        AlarmSpec(id = id, hour = hour, minute = minute, days = days, sleepDurationMinutes = sleep)

    @Test
    fun bedtimeIsTargetMinusGoal() {
        val target = at("2026-10-28T06:00")
        assertEquals(at("2026-10-27T22:30"), Bedtime.bedtime(target, 450))
        assertEquals(at("2026-10-27T22:00"), Bedtime.reminderAt(target, 450))
        assertEquals(at("2026-10-27T22:20"), Bedtime.reminderAt(target, 450, leadMinutes = 10))
    }

    @Test
    fun oneBedtimeIncludesLearnedLatency() {
        // Bettzeit = Weckzeit − Schlafziel − gelernte Einschlafdauer, überall gleich.
        val target = at("2026-10-28T06:00")
        assertEquals(at("2026-10-27T22:15"), Bedtime.bedtime(target, 450, latencyMinutes = 15))
        // Nicht gelernt: wie bisher; negative Werte zählen 0.
        assertEquals(at("2026-10-27T22:30"), Bedtime.bedtime(target, 450, latencyMinutes = null))
        assertEquals(at("2026-10-27T22:30"), Bedtime.bedtime(target, 450, latencyMinutes = -5))
        assertEquals(at("2026-10-27T21:45"), Bedtime.reminderAt(target, 450, latencyMinutes = 15))
        assertEquals(at("2026-10-27T22:15"), Bedtime.onDial(target, 450, latencyMinutes = 15))
        // Auf dem Ring zählt die ganze Strecke: 700 + 19 passt noch, 700 + 20 nicht mehr.
        assertEquals(at("2026-10-27T18:01"), Bedtime.onDial(target, 700, latencyMinutes = 19))
        assertNull(Bedtime.onDial(target, 700, latencyMinutes = 20))
        val reminder = Bedtime.next(listOf(alarm("a", 6, 0)), at("2026-10-27T20:00"), zone, 450, latencyMinutes = 15)!!
        assertEquals(at("2026-10-27T21:45"), reminder.at)
        assertEquals(at("2026-10-27T22:15"), reminder.bedtime)
        // Empfohlene Schlafenszeit (Karte) endet an derselben Bettzeit (abgerundet auf 5 Min).
        assertEquals(Bedtime.bedtime(target, 450, 15), Learning.bedtimeRange(target, 450, 15).second)
        // Diagramm: die Bettzeit-Linie (Minuten ab Mittag) liegt 15 Min früher.
        val sleeps = listOf(SleepSummary(at("2026-10-26T23:00"), at("2026-10-27T06:30"), 450))
        assertEquals(
            10 * 60 + 15,
            ChartData.sleepBars(sleeps, zone, day, target, 450, latencyMinutes = 15)!!.bedtimeMinute,
        )
        assertEquals(10 * 60 + 30, ChartData.sleepBars(sleeps, zone, day, target, 450)!!.bedtimeMinute)
    }

    @Test
    fun bedtimeOnDialOnlyWithinTwelveHours() {
        val target = at("2026-10-28T06:00")
        assertEquals(at("2026-10-27T22:30"), Bedtime.onDial(target, 450))
        assertEquals(at("2026-10-27T20:00"), Bedtime.onDial(target, SleepGoal.MAX_MINUTES))
        assertEquals(at("2026-10-27T18:01"), Bedtime.onDial(target, 719))
        assertNull(Bedtime.onDial(target, Bedtime.DIAL_MINUTES))
        assertNull(Bedtime.onDial(target, 0))
    }

    @Test
    fun nextReminderSkipsPastNightsAndSecondAlarms() {
        val alarms = listOf(alarm("a", 6, 0), alarm("b", 6, 30))
        // Vor der Erinnerung: die für 06:00.
        val evening = Bedtime.next(alarms, at("2026-10-27T20:00"), zone, 450)!!
        assertEquals(at("2026-10-27T22:00"), evening.at)
        assertEquals(at("2026-10-27T22:30"), evening.bedtime)
        assertEquals(at("2026-10-28T06:00"), evening.target)
        // Erinnerung verpasst (22:10): nicht nachträglich für den zweiten Wecker um 06:30, sondern morgen.
        val late = Bedtime.next(alarms, at("2026-10-27T22:10"), zone, 450)!!
        assertEquals(at("2026-10-28T22:00"), late.at)
        // Nach dem Zeigen (ab dem Zeitpunkt der Erinnerung) dasselbe.
        assertEquals(at("2026-10-28T22:00"), Bedtime.next(alarms, at("2026-10-27T22:00"), zone, 450)!!.at)
        // Einschlaf-Modus: es gilt die späteste Weckzeit.
        val sleepMode = Bedtime.next(listOf(alarm("s", 7, 0, sleep = 420)), at("2026-10-27T20:00"), zone, 480)!!
        assertEquals(at("2026-10-27T22:30"), sleepMode.at)
        assertNull(Bedtime.next(listOf(alarm("off", 6, 0).copy(enabled = false)), at("2026-10-27T20:00"), zone, 450))
        assertNull(Bedtime.next(emptyList(), at("2026-10-27T20:00"), zone, 450))
    }

    @Test
    fun oneShotAlarmWithPastReminderHasNone() {
        // Einmal-Wecker in 3 Stunden: Erinnerung läge in der Vergangenheit, danach kommt nichts mehr.
        val once = alarm("o", 23, 0, days = emptySet())
        assertNull(Bedtime.next(listOf(once), at("2026-10-27T20:00"), zone, 450))
    }

    @Test
    fun nightsOfHealthAppMapByEndAndEmptyWeek() {
        val sleep = SleepSummary(at("2026-10-27T23:00"), at("2026-10-28T06:30"), 420)
        assertEquals(listOf(day to 420L), SleepGoal.nightsOf(listOf(sleep), zone))
        assertEquals(0 to 0, SleepGoal.weekStatus(emptyList(), 450))
    }

    @Test
    fun sleepWeekNeedsThreeNightsWithDuration() {
        // Nächte bis 28.10.: 480, 420 und 400 Min, Ziel 450 → Ø 433 Min, Ziel 1 von 3; mit zwei Nächten keine Zeile.
        val now = at("2026-10-28T12:00")
        val sleeps = listOf(480L, 420L, 400L).mapIndexed { i, minutes ->
            val end = at("2026-10-28T06:30").minus(java.time.Duration.ofDays(i.toLong()))
            SleepSummary(end.minus(java.time.Duration.ofMinutes(minutes + 10)), end, minutes)
        }
        fun week(list: List<SleepSummary>) = SleepWeek.of(
            Trends.compute(emptyList(), list, now, zone), SleepGoal.weekStatus(SleepGoal.nightsOf(list, zone), 450),
        )
        assertEquals(SleepWeek(433, 1, 3), week(sleeps))
        assertNull(week(sleeps.take(2)))
    }
}
