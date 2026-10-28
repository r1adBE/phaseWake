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

    private fun alarm(id: String, hour: Int, minute: Int, days: Set<DayOfWeek> = DayOfWeek.entries.toSet(), sleep: Int? = null) =
        AlarmSpec(id = id, hour = hour, minute = minute, days = days, sleepDurationMinutes = sleep)

    @Test
    fun bedtimeIsTargetMinusGoal() {
        val target = at("2026-10-28T06:00")
        assertEquals(at("2026-10-27T22:30"), Bedtime.bedtime(target, 450))
        assertEquals(at("2026-10-27T22:00"), Bedtime.reminderAt(target, 450))
        assertEquals(at("2026-10-27T22:20"), Bedtime.reminderAt(target, 450, leadMinutes = 10))
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
}
