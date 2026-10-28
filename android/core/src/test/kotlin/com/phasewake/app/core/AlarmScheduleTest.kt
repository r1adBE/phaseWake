package com.phasewake.app.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlarmScheduleTest {

    private val zurich = ZoneId.of("Europe/Zurich")

    private fun at(text: String): Instant = LocalDateTime.parse(text).atZone(zurich).toInstant()

    private fun alarm(hour: Int = 7, minute: Int = 0, days: Set<DayOfWeek> = emptySet(), id: String = "a") =
        AlarmSpec(id = id, hour = hour, minute = minute, days = days)

    @Test
    fun oneShotLaterToday() {
        assertEquals(at("2026-10-28T07:00"), AlarmSchedule.nextTarget(alarm(), at("2026-10-28T01:00"), zurich))
    }

    @Test
    fun oneShotTomorrowWhenPassed() {
        assertEquals(at("2026-10-29T07:00"), AlarmSchedule.nextTarget(alarm(), at("2026-10-28T07:00"), zurich))
    }

    @Test
    fun runningWindowKeepsTodaysTarget() {
        val plan = AlarmSchedule.nextPlan(alarm(), at("2026-10-28T06:45"), zurich)!!
        assertEquals(at("2026-10-28T07:00"), plan.target)
        assertEquals(at("2026-10-28T06:30"), plan.windowStart)
    }

    @Test
    fun weekdaysSkipWeekend() {
        val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        // Freitag 30.10.2026 nach 7 Uhr → Montag 2.11.
        assertEquals(at("2026-11-02T07:00"), AlarmSchedule.nextTarget(alarm(days = weekdays), at("2026-10-30T08:00"), zurich))
    }

    @Test
    fun sameWeekdayNextWeek() {
        val wednesday = setOf(DayOfWeek.WEDNESDAY)
        assertEquals(at("2026-11-04T07:00"), AlarmSchedule.nextTarget(alarm(days = wednesday), at("2026-10-28T07:30"), zurich))
    }

    @Test
    fun disabledHasNoTarget() {
        assertNull(AlarmSchedule.nextTarget(alarm().copy(enabled = false), at("2026-10-28T01:00"), zurich))
    }

    @Test
    fun springForwardGapUsesNextValidTime() {
        // 29.3.2026: 02:00 → 03:00. Wecker 02:30 klingelt um 03:30 Sommerzeit (gleiche Dauer nach Mitternacht).
        val target = AlarmSchedule.nextTarget(alarm(hour = 2, minute = 30), at("2026-03-29T00:00"), zurich)!!
        assertEquals(LocalDateTime.parse("2026-03-29T03:30"), target.atZone(zurich).toLocalDateTime())
    }

    @Test
    fun fallBackOverlapUsesEarlierTime() {
        // 25.10.2026: 03:00 → 02:00. 02:30 gibt es zweimal; es gilt die frühere (Sommerzeit, +02:00).
        val target = AlarmSchedule.nextTarget(alarm(hour = 2, minute = 30), at("2026-10-25T00:00"), zurich)!!
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), target)
    }

    @Test
    fun earliestPlanAcrossAlarms() {
        val now = at("2026-10-28T01:00")
        val plan = AlarmSchedule.earliestPlan(listOf(alarm(hour = 8, id = "b"), alarm(hour = 6, id = "c")), now, zurich)!!
        assertEquals("c", plan.alarmId)
        assertNull(AlarmSchedule.earliestPlan(emptyList(), now, zurich))
    }

    @Test
    fun sleepTargetNeverAfterLatest() {
        val onset = at("2026-10-27T23:10")
        val latest = at("2026-10-28T07:00")
        assertEquals(at("2026-10-28T06:40"), AlarmSchedule.sleepTarget(onset, 450, latest))
        assertEquals(latest, AlarmSchedule.sleepTarget(onset, 600, latest))
    }

    @Test
    fun invalidSpecIsRejected() {
        assertFailsWith<IllegalArgumentException> { alarm(hour = 24) }
        assertFailsWith<IllegalArgumentException> { alarm(minute = 60) }
        assertFailsWith<IllegalArgumentException> { alarm().copy(windowMinutes = 31) }
        assertFailsWith<IllegalArgumentException> { alarm().copy(windowMinutes = 9) }
        assertFailsWith<IllegalArgumentException> { alarm().copy(sleepDurationMinutes = 30) }
        assertFailsWith<IllegalArgumentException> { alarm(id = " ") }
        assertTrue(alarm().isOneShot)
    }
}
