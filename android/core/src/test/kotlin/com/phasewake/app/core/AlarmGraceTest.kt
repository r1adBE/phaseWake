package com.phasewake.app.core

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Nachfrist ([AlarmSchedule.inGrace]) und Nachholen ([AlarmSchedule.missedPlan]); gleiche Fälle wie `AlarmGraceTests.swift`. */
class AlarmGraceTest {

    private val zurich = ZoneId.of("Europe/Zurich")
    private val grace = Duration.ofMinutes(2)

    private fun at(text: String): Instant = LocalDateTime.parse(text).atZone(zurich).toInstant()

    private fun alarm(hour: Int = 7, minute: Int = 0, days: Set<DayOfWeek> = emptySet(), id: String = "a") =
        AlarmSpec(id = id, hour = hour, minute = minute, days = days)

    @Test
    fun graceKeepsPlannedBackup() {
        val plan = AlarmSchedule.inGrace(alarm(), at("2026-10-28T07:00"), at("2026-10-28T07:01"), grace, zurich)!!
        assertEquals(at("2026-10-28T07:00"), plan.target)
        assertEquals(at("2026-10-28T06:40"), plan.windowStart)
        assertEquals("a", plan.alarmId)
        // Genau zur Zielzeit zählt schon, das Ende der Nachfrist nicht mehr.
        assertEquals(plan, AlarmSchedule.inGrace(alarm(), at("2026-10-28T07:00"), at("2026-10-28T07:00"), grace, zurich))
        assertNull(AlarmSchedule.inGrace(alarm(), at("2026-10-28T07:00"), at("2026-10-28T07:02"), grace, zurich))
    }

    @Test
    fun graceOnlyAfterTarget() {
        assertNull(AlarmSchedule.inGrace(alarm(), at("2026-10-28T07:00"), at("2026-10-28T06:59"), grace, zurich))
        assertNull(AlarmSchedule.inGrace(alarm(), null, at("2026-10-28T07:01"), grace, zurich))
        // Ohne Uhr (Nachfrist null) klingelt das Handy pünktlich; es gibt keine Nachfrist zu behalten.
        assertNull(AlarmSchedule.inGrace(alarm(), at("2026-10-28T07:00"), at("2026-10-28T07:00"), Duration.ZERO, zurich))
    }

    @Test
    fun graceDropsInvalidOccurrence() {
        val planned = at("2026-10-28T07:00")
        val now = at("2026-10-28T07:01")
        assertNull(AlarmSchedule.inGrace(alarm(minute = 30), planned, now, grace, zurich), "Zeit geändert")
        assertNull(AlarmSchedule.inGrace(alarm().copy(enabled = false), planned, now, grace, zurich), "ausgeschaltet")
        val daily = alarm(days = DayOfWeek.entries.toSet())
        assertTrue(AlarmSchedule.inGrace(daily, planned, now, grace, zurich) != null)
        val skipped = daily.copy(skipUntil = AlarmSchedule.wallMillis(planned, zurich))
        assertNull(AlarmSchedule.inGrace(skipped, planned, now, grace, zurich), "übersprungen")
        val holiday = daily.copy(skipHolidays = true)
        assertNull(AlarmSchedule.inGrace(holiday, planned, now, grace, zurich, setOf(LocalDate.of(2026, 10, 28))), "Feiertag")
        // Mittwoch 28.10.: ein Wecker nur am Donnerstag hatte heute keinen Termin.
        assertNull(AlarmSchedule.inGrace(alarm(days = setOf(DayOfWeek.THURSDAY)), planned, now, grace, zurich), "kein Termin")
    }

    @Test
    fun graceRespectsSuppression() {
        val planned = at("2026-10-28T07:00")
        val now = at("2026-10-28T07:01")
        assertNull(AlarmSchedule.inGrace(alarm(), planned, now, grace, zurich, suppressedUntil = planned.plus(grace)))
        assertNull(AlarmSchedule.inGrace(alarm(), planned, now, grace, zurich, suppressedUntil = planned))
        assertTrue(AlarmSchedule.inGrace(alarm(), planned, now, grace, zurich, suppressedUntil = at("2026-10-27T07:02")) != null)
    }

    /** Wecker a 06:40 führt die Nacht (Fenster ab 06:20, beendet 07:10); b 07:00 wurde dabei fällig. */
    private val a = alarm(hour = 6, minute = 40, id = "a")
    private val b = alarm(id = "b")
    private val window get() = AlarmSchedule.catchUpAfter(null, at("2026-10-28T06:20"), at("2026-10-28T07:10"), listOf(a, b), zurich)

    @Test
    fun catchUpCollectsDueAlarms() {
        assertEquals(CatchUpWindow(at("2026-10-28T06:20"), at("2026-10-28T07:10"), setOf("a", "b")), window)
        val later = alarm(hour = 7, minute = 30, id = "c")
        assertEquals(setOf("a"), AlarmSchedule.catchUpAfter(null, at("2026-10-28T06:20"), at("2026-10-28T06:50"), listOf(a, b, later), zurich).alarmIds)
    }

    @Test
    fun missedAlarmRingsRightAfter() {
        val done = mapOf("a" to at("2026-10-28T06:40"))
        val plan = AlarmSchedule.missedPlan(listOf(a, b), window, at("2026-10-28T07:10"), zurich, doneUntil = done)!!
        assertEquals("b", plan.alarmId)
        assertEquals(at("2026-10-28T07:00"), plan.target)
        // Gleich danach erledigt: nichts mehr nachzuholen.
        val both = done + ("b" to at("2026-10-28T07:00"))
        assertNull(AlarmSchedule.missedPlan(listOf(a, b), window, at("2026-10-28T07:11"), zurich, doneUntil = both))
    }

    @Test
    fun missedOnlyWhileValidAndKnown() {
        val done = mapOf("a" to at("2026-10-28T06:40"))
        assertTrue(AlarmSchedule.missedPlan(listOf(a, b), window, at("2026-10-28T07:20"), zurich, doneUntil = done) != null)
        assertNull(AlarmSchedule.missedPlan(listOf(a, b), window, at("2026-10-28T07:21"), zurich, doneUntil = done), "zu spät")
        assertFalse(window.isValid(at("2026-10-28T07:21")))
        assertNull(AlarmSchedule.missedPlan(listOf(a, b), null, at("2026-10-28T07:10"), zurich))
        // Erst nach dem Ende der Nacht angelegt: klingelt nicht nachträglich.
        val fresh = alarm(hour = 6, minute = 50, id = "d")
        assertNull(AlarmSchedule.missedPlan(listOf(a, fresh), window, at("2026-10-28T07:10"), zurich, doneUntil = done))
        // Zielzeit nach dem Ende der Nacht: gewohnter Plan, kein Nachholen.
        val narrow = AlarmSchedule.catchUpAfter(null, at("2026-10-28T06:20"), at("2026-10-28T06:50"), listOf(a, b), zurich)
        assertNull(AlarmSchedule.missedPlan(listOf(a, b), narrow, at("2026-10-28T06:55"), zurich, doneUntil = done))
    }

    @Test
    fun catchUpExtendsAdjoiningNight() {
        val c = alarm(hour = 7, minute = 15, id = "c")
        val next = AlarmSchedule.catchUpAfter(window, at("2026-10-28T07:10"), at("2026-10-28T07:20"), listOf(a, b, c), zurich)
        assertEquals(CatchUpWindow(at("2026-10-28T06:20"), at("2026-10-28T07:20"), setOf("a", "b", "c")), next)
        val apart = AlarmSchedule.catchUpAfter(window, at("2026-10-28T08:00"), at("2026-10-28T08:30"), listOf(a, b, c), zurich)
        assertEquals(CatchUpWindow(at("2026-10-28T08:00"), at("2026-10-28T08:30"), emptySet()), apart)
    }
}
