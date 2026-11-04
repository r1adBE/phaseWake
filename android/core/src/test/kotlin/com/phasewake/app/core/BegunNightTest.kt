package com.phasewake.app.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** Begonnene Nacht über einen Zeitzonenwechsel ([AlarmSchedule.begunNight]); gleiche Fälle wie `BegunNightTests.swift`. */
class BegunNightTest {

    private val zurich = ZoneId.of("Europe/Zurich")
    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val grace = Duration.ofMinutes(2)
    private val planned = at("2026-11-04T07:00")

    private fun at(text: String): Instant = LocalDateTime.parse(text).atZone(zurich).toInstant()

    private fun alarm(hour: Int = 7, minute: Int = 0, window: Int = 30) =
        AlarmSpec(id = "a", hour = hour, minute = minute, windowMinutes = window)

    private fun begun(spec: AlarmSpec = alarm(), now: Instant, zone: ZoneId? = zurich, suppressedUntil: Instant? = null) =
        AlarmSchedule.begunNight(spec, planned, zone, now, grace, suppressedUntil = suppressedUntil)

    @Test
    fun zoneChangeInWindowKeepsPlannedTarget() {
        val now = at("2026-11-04T06:40")
        // In Tokio (14:40) wäre die nächste 07:00 erst morgen.
        assertNotEquals(planned, AlarmSchedule.nextTarget(alarm(), now, tokyo))
        val plan = begun(now = now)!!
        assertEquals(planned, plan.target)
        assertEquals(at("2026-11-04T06:30"), plan.windowStart)
        assertEquals("a", plan.alarmId)
        // Fensterbeginn zählt schon; in der Nachfrist bleibt die Nacht.
        assertEquals(plan, begun(now = at("2026-11-04T06:30")))
        assertEquals(plan, begun(now = at("2026-11-04T07:01")))
    }

    @Test
    fun beforeWindowOrAfterGraceNull() {
        assertNull(begun(now = at("2026-11-04T06:20")), "vor dem Fenster")
        assertNull(begun(now = at("2026-11-04T07:02")), "Ende der Nachfrist")
        assertNull(begun(now = at("2026-11-04T08:00")), "danach")
    }

    @Test
    fun changedAlarmNull() {
        val now = at("2026-11-04T06:40")
        assertNull(begun(alarm(minute = 30), now), "Zeit geändert")
        assertNull(begun(alarm().copy(enabled = false), now), "ausgeschaltet")
        assertNull(begun(alarm(window = 10), now), "Fenster verkürzt, noch nicht begonnen")
    }

    @Test
    fun suppressedOrWithoutZoneNull() {
        val now = at("2026-11-04T06:40")
        assertNull(begun(now = now, suppressedUntil = planned), "ausgesetzt")
        assertNull(begun(now = now, zone = null), "ohne Zone")
        assertNull(AlarmSchedule.begunNight(alarm(), null, zurich, now, grace), "ohne Zielzeit")
    }

    @Test
    fun zoneShiftedOnlyWhenOffsetDiffers() {
        assertTrue(AlarmSchedule.zoneShifted(planned, zurich, tokyo))
        assertFalse(AlarmSchedule.zoneShifted(planned, zurich, ZoneId.of("Europe/Berlin")), "Zürich → Berlin")
        assertFalse(AlarmSchedule.zoneShifted(planned, zurich, zurich))
    }

    @Test
    fun sameZoneHarmless() {
        val now = at("2026-11-04T06:40")
        assertEquals(AlarmSchedule.nextPlan(alarm(), now, zurich), begun(now = now))
    }
}
