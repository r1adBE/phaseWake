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

/** «Nächstes Mal überspringen» und «An Feiertagen nicht wecken»; Gegenstück: `SkipTests` (Swift). */
class SkipTest {
    private val zurich = ZoneId.of("Europe/Zurich")
    private val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

    private fun at(text: String): Instant = LocalDateTime.parse(text).atZone(zurich).toInstant()

    private fun alarm(days: Set<DayOfWeek> = weekdays) = AlarmSpec(id = "a", hour = 7, minute = 0, days = days)

    // Mittwoch, 28. Oktober 2026, 22:00.
    private val evening = at("2026-10-28T22:00")

    @Test
    fun skipNextSkipsExactlyOneOccurrence() {
        val skipped = AlarmSchedule.skipNext(alarm(), evening, zurich)
        assertEquals(at("2026-10-29T07:00").toEpochMilli(), skipped.skipUntil)
        assertEquals(at("2026-10-30T07:00"), AlarmSchedule.nextTarget(skipped, evening, zurich))
        assertEquals(LocalDate.of(2026, 10, 29), AlarmSchedule.skippedDate(skipped, evening, zurich))
        // Nach dem übersprungenen Termin gilt alles wieder normal.
        assertNull(AlarmSchedule.skippedDate(skipped, at("2026-10-29T07:00"), zurich))
        assertEquals(at("2026-10-30T07:00"), AlarmSchedule.nextTarget(skipped, at("2026-10-29T08:00"), zurich))
        assertTrue(skipped.enabled)
    }

    @Test
    fun skipDuringRunningWindowSkipsThisMorning() {
        val skipped = AlarmSchedule.skipNext(alarm(), at("2026-10-29T06:45"), zurich)
        assertEquals(at("2026-10-30T07:00"), AlarmSchedule.nextPlan(skipped, at("2026-10-29T06:45"), zurich)?.target)
    }

    @Test
    fun skipOverWeekend() {
        val friday = at("2026-10-30T20:00")
        val skipped = AlarmSchedule.skipNext(alarm(), at("2026-10-30T07:30"), zurich)
        // Nächster Termin wäre Montag 2.11.; übersprungen → Dienstag.
        assertEquals(at("2026-11-02T07:00").toEpochMilli(), skipped.skipUntil)
        assertEquals(at("2026-11-03T07:00"), AlarmSchedule.nextTarget(skipped, friday, zurich))
    }

    @Test
    fun undoSkipRestoresNextOccurrence() {
        val skipped = AlarmSchedule.skipNext(alarm(), evening, zurich)
        val undone = AlarmSchedule.undoSkip(skipped)
        assertEquals(0L, undone.skipUntil)
        assertEquals(at("2026-10-29T07:00"), AlarmSchedule.nextTarget(undone, evening, zurich))
        assertNull(AlarmSchedule.skippedDate(undone, evening, zurich))
    }

    @Test
    fun oneShotIgnoresSkipAndHolidays() {
        val once = alarm(days = emptySet())
        assertEquals(once, AlarmSchedule.skipNext(once, evening, zurich))
        val forced = once.copy(skipUntil = at("2026-10-29T07:00").toEpochMilli(), skipHolidays = true)
        val holidays = setOf(LocalDate.of(2026, 10, 29))
        assertEquals(at("2026-10-29T07:00"), AlarmSchedule.nextTarget(forced, evening, zurich, holidays))
        assertNull(AlarmSchedule.skippedDate(forced, evening, zurich))
        assertNull(AlarmSchedule.skippedHoliday(forced, evening, zurich, holidays))
    }

    @Test
    fun disabledAlarmIsNotSkipped() {
        val off = alarm().copy(enabled = false)
        assertEquals(off, AlarmSchedule.skipNext(off, evening, zurich))
    }

    @Test
    fun holidaysAreSkippedOnlyWhenWanted() {
        val holidays = setOf(LocalDate.of(2026, 10, 29), LocalDate.of(2026, 10, 30))
        assertEquals(at("2026-10-29T07:00"), AlarmSchedule.nextTarget(alarm(), evening, zurich, holidays))
        val wanted = alarm().copy(skipHolidays = true)
        assertEquals(at("2026-11-02T07:00"), AlarmSchedule.nextTarget(wanted, evening, zurich, holidays))
        assertEquals(LocalDate.of(2026, 10, 29), AlarmSchedule.skippedHoliday(wanted, evening, zurich, holidays))
        assertNull(AlarmSchedule.skippedHoliday(alarm(), evening, zurich, holidays))
        assertNull(AlarmSchedule.skippedHoliday(wanted, evening, zurich, emptySet()))
        // Kein Feiertag als nächster Termin: nichts anzuzeigen.
        assertNull(AlarmSchedule.skippedHoliday(wanted, at("2026-10-30T08:00"), zurich, holidays))
    }

    @Test
    fun skipNextJumpsOverHolidays() {
        val holidays = setOf(LocalDate.of(2026, 10, 29))
        val skipped = AlarmSchedule.skipNext(alarm().copy(skipHolidays = true), evening, zurich, holidays)
        assertEquals(at("2026-10-30T07:00").toEpochMilli(), skipped.skipUntil)
        assertEquals(at("2026-11-02T07:00"), AlarmSchedule.nextTarget(skipped, evening, zurich, holidays))
    }

    @Test
    fun everyDayHolidayEndsWithoutTarget() {
        val today = LocalDate.of(2026, 10, 28)
        val all = (0L..AlarmSchedule.MAX_SEARCH_DAYS + 1).map { today.plusDays(it) }.toSet()
        assertNull(AlarmSchedule.nextTarget(alarm().copy(skipHolidays = true), evening, zurich, all))
    }

    @Test
    fun earliestPlanAndBedtimeUseHolidays() {
        val holidays = setOf(LocalDate.of(2026, 10, 29))
        val a = alarm().copy(id = "a", skipHolidays = true)
        val b = alarm().copy(id = "b", hour = 9)
        assertEquals("b", AlarmSchedule.earliestPlan(listOf(a, b), evening, zurich, holidays)?.alarmId)
        val reminder = Bedtime.next(listOf(a), evening, zurich, goalMinutes = 480, holidays = holidays)
        assertEquals(at("2026-10-30T07:00"), reminder?.target)
    }

    @Test
    fun codecKeepsSkipFields() {
        val a = alarm().copy(skipUntil = 1_793_000_000_000L, skipHolidays = true)
        val text = Codec.encodeAlarms(listOf(a))
        assertTrue(text.endsWith("|1793000000000|1\n"))
        assertEquals(a, Codec.decodeAlarms(text).single())
        // 14 Felder (vor dieser Version): nichts überspringen.
        val old = Codec.decodeAlarms("phasewake-alarms 1\na|7|0|MO|30|1|SOUND|0||||OFF|NONE|PHONE\n").single()
        assertEquals(0L, old.skipUntil)
        assertFalse(old.skipHolidays)
        // Unlesbare oder negative Werte: tolerant.
        val bad = Codec.decodeAlarms("phasewake-alarms 1\na|7|0|MO|30|1|SOUND|0||||OFF|NONE|PHONE|x|ja|neu\n").single()
        assertEquals(0L, bad.skipUntil)
        assertFalse(bad.skipHolidays)
        assertEquals(0L, Codec.decodeAlarms("phasewake-alarms 1\na|7|0|MO|30|1|SOUND|0||||OFF|NONE|PHONE|-5|0\n").single().skipUntil)
    }
}
