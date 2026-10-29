package com.phasewake.app.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Überspringen nach frühem Wecken (erledigt bis), auf Reisen (Wandzeit), beim Bearbeiten und der
 * Feiertagshinweis der Startseite. Gegenstück: `SkipDoneTests` (Swift).
 */
class SkipDoneTest {
    private val zurich = ZoneId.of("Europe/Zurich")
    private val newYork = ZoneId.of("America/New_York")
    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

    private fun at(text: String, zone: ZoneId = zurich): Instant = LocalDateTime.parse(text).atZone(zone).toInstant()

    private fun wall(text: String): Long = LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun alarm(id: String = "a", hour: Int = 7, days: Set<DayOfWeek> = weekdays) =
        AlarmSpec(id = id, hour = hour, minute = 0, windowMinutes = 30, days = days)

    @Test
    fun nightDoneTargetAheadSkipsNextOccurrence() {
        // Do 29.10., früh geweckt um 6:41; um 6:50 «Nächstes Mal überspringen». Die Nacht bis 7:00 ist erledigt.
        val now = at("2026-10-29T06:50")
        val done = at("2026-10-29T07:00")
        val skipped = AlarmSchedule.skipNext(alarm(), now, zurich, doneUntil = done)
        assertEquals(wall("2026-10-30T07:00"), skipped.skipUntil)
        assertEquals(LocalDate.of(2026, 10, 30), AlarmSchedule.nextSkipDate(alarm(), now, zurich, doneUntil = done))
        assertEquals(LocalDate.of(2026, 10, 30), AlarmSchedule.skippedDate(skipped, now, zurich))
        assertEquals(at("2026-11-02T07:00"), AlarmSchedule.nextTarget(skipped, at("2026-10-29T07:30"), zurich))
        // Sperre des Zweitweckers am Handy: Zielzeit + 2 Min.
        assertEquals(wall("2026-10-30T07:00"), AlarmSchedule.skipNext(alarm(), now, zurich, doneUntil = done.plusSeconds(120)).skipUntil)
        // Ohne erledigte Nacht (oder erledigt in der Vergangenheit) gilt der heutige Termin.
        assertEquals(LocalDate.of(2026, 10, 29), AlarmSchedule.nextSkipDate(alarm(), now, zurich))
        assertEquals(wall("2026-10-29T07:00"), AlarmSchedule.skipNext(alarm(), now, zurich, doneUntil = at("2026-10-28T07:00")).skipUntil)
    }

    @Test
    fun nextSkipDateWithoutOccurrence() {
        val now = at("2026-10-28T22:00")
        assertNull(AlarmSchedule.nextSkipDate(alarm(days = emptySet()), now, zurich))
        assertNull(AlarmSchedule.nextSkipDate(alarm().copy(enabled = false), now, zurich))
    }

    @Test
    fun earliestPlanRespectsDoneUntil() {
        val now = at("2026-10-29T06:50")
        val done = mapOf("a" to at("2026-10-29T07:00"))
        val a = alarm("a")
        val b = alarm("b", hour = 8)
        assertEquals("a", AlarmSchedule.earliestPlan(listOf(a, b), now, zurich)?.alarmId)
        // Der zweite Wecker am selben Morgen bleibt.
        assertEquals(at("2026-10-29T08:00"), AlarmSchedule.earliestPlan(listOf(a, b), now, zurich, doneUntil = done)?.target)
        assertEquals(at("2026-10-30T07:00"), AlarmSchedule.earliestPlan(listOf(a), now, zurich, doneUntil = done)?.target)
    }

    @Test
    fun skipKeepsCalendarDayWhenTravellingWest() {
        // In Zürich am Mittwochabend Do 7:00 übersprungen, dann nach New York geflogen.
        val skipped = AlarmSchedule.skipNext(alarm(), at("2026-10-28T22:00"), zurich)
        assertEquals(wall("2026-10-29T07:00"), skipped.skipUntil)
        val nyMorning = at("2026-10-29T05:00", newYork)
        assertEquals(at("2026-10-30T07:00", newYork), AlarmSchedule.nextTarget(skipped, nyMorning, newYork))
        assertEquals(LocalDate.of(2026, 10, 29), AlarmSchedule.skippedDate(skipped, nyMorning, newYork))
        assertNull(AlarmSchedule.skippedDate(skipped, at("2026-10-29T07:00", newYork), newYork))
    }

    @Test
    fun skipKeepsCalendarDayWhenTravellingEast() {
        val skipped = AlarmSchedule.skipNext(alarm(), at("2026-10-28T20:00", newYork), newYork)
        val tokyoEvening = at("2026-10-28T23:00", tokyo)
        // Do 7:00 in Tokio fällt aus, Fr 7:00 nicht.
        assertEquals(at("2026-10-30T07:00", tokyo), AlarmSchedule.nextTarget(skipped, tokyoEvening, tokyo))
        assertEquals(LocalDate.of(2026, 10, 29), AlarmSchedule.skippedDate(skipped, tokyoEvening, tokyo))
    }

    @Test
    fun wallMillisIgnoresZoneAndHandlesGap() {
        assertEquals(1_793_257_200_000L, AlarmSchedule.wallMillis(at("2026-10-29T07:00"), zurich))
        assertEquals(1_793_257_200_000L, AlarmSchedule.wallMillis(at("2026-10-29T07:00", newYork), newYork))
        // Zeitumstellung (28.3.2027, 2:30 fällt aus → 3:30): Übersprungen wird genau dieser Termin.
        val night = alarm(hour = 2, days = DayOfWeek.entries.toSet()).copy(minute = 30)
        val skipped = AlarmSchedule.skipNext(night, at("2027-03-27T22:00"), zurich)
        assertEquals(wall("2027-03-28T03:30"), skipped.skipUntil)
        assertEquals(at("2027-03-29T02:30"), AlarmSchedule.nextTarget(skipped, at("2027-03-27T22:00"), zurich))
    }

    @Test
    fun keepSkipOnlyForSameSchedule() {
        val old = alarm().copy(skipUntil = wall("2026-10-29T07:00"))
        assertEquals(old.skipUntil, AlarmSchedule.keepSkip(old, old.copy(label = "Neu", windowMinutes = 20, enabled = false)))
        assertEquals(0L, AlarmSchedule.keepSkip(old, old.copy(hour = 8)))
        assertEquals(0L, AlarmSchedule.keepSkip(old, old.copy(minute = 15)))
        assertEquals(0L, AlarmSchedule.keepSkip(old, old.copy(days = setOf(DayOfWeek.MONDAY))))
        assertEquals(0L, AlarmSchedule.keepSkip(old, old.copy(days = emptySet())))
    }

    @Test
    fun editedSkipFollowsSwitch() {
        val now = at("2026-10-28T22:00")
        val old = alarm().copy(skipUntil = wall("2026-10-29T07:00"))
        // Schalter nicht angefasst: wie keepSkip.
        assertEquals(old.skipUntil, AlarmSchedule.editedSkip(old, old.copy(windowMinutes = 20), now, zurich))
        assertEquals(0L, AlarmSchedule.editedSkip(old, old.copy(hour = 8), now, zurich))
        // Ausgeschaltet.
        assertEquals(0L, AlarmSchedule.editedSkip(old, old.copy(skipUntil = 0L), now, zurich))
        // Zeit geändert und Schalter bewusst eingeschaltet: gilt für die neue Zeit.
        val plain = alarm()
        val edited = plain.copy(hour = 8, skipUntil = wall("2026-10-29T07:00"))
        assertEquals(wall("2026-10-29T08:00"), AlarmSchedule.editedSkip(plain, edited, now, zurich))
        // … und nach frühem Wecken ab der erledigten Nacht.
        val early = at("2026-10-29T06:50")
        assertEquals(
            wall("2026-10-30T08:00"),
            AlarmSchedule.editedSkip(plain, edited, early, zurich, doneUntil = at("2026-10-29T08:00")),
        )
    }

    @Test
    fun holidayNoteOnlyUpToNextPlan() {
        val holidays = setOf(LocalDate.of(2026, 10, 29))
        val a = alarm("a").copy(skipHolidays = true)
        val b = alarm("b", hour = 9, days = DayOfWeek.entries.toSet())
        // Dienstag: B weckt am Mittwoch zuerst, der Feiertag am Donnerstag ist noch kein Hinweis.
        assertNull(AlarmSchedule.holidayNote(listOf(a, b), at("2026-10-27T22:00"), zurich, holidays))
        // Mittwochabend: B weckt am Feiertag selbst (ohne Feiertagsregel) → Hinweis für A.
        assertEquals(LocalDate.of(2026, 10, 29), AlarmSchedule.holidayNote(listOf(a, b), at("2026-10-28T22:00"), zurich, holidays))
        assertEquals(LocalDate.of(2026, 10, 29), AlarmSchedule.holidayNote(listOf(a), at("2026-10-28T22:00"), zurich, holidays))
        assertNull(AlarmSchedule.holidayNote(listOf(b), at("2026-10-28T22:00"), zurich, holidays))
        assertNull(AlarmSchedule.holidayNote(emptyList(), at("2026-10-28T22:00"), zurich, holidays))
        // Jeder Tag Feiertag: kein Plan, der Hinweis gilt trotzdem.
        val all = (0L..AlarmSchedule.MAX_SEARCH_DAYS + 1).map { LocalDate.of(2026, 10, 28).plusDays(it) }.toSet()
        assertEquals(LocalDate.of(2026, 10, 29), AlarmSchedule.holidayNote(listOf(a), at("2026-10-28T22:00"), zurich, all))
    }

    @Test
    fun holidayNoteAfterEarlyWake() {
        val holidays = setOf(LocalDate.of(2026, 10, 30))
        val a = alarm("a").copy(skipHolidays = true)
        val now = at("2026-10-29T06:50")
        assertNull(AlarmSchedule.holidayNote(listOf(a), now, zurich, holidays))
        val done = mapOf("a" to at("2026-10-29T07:00"))
        assertEquals(LocalDate.of(2026, 10, 30), AlarmSchedule.holidayNote(listOf(a), now, zurich, holidays, done))
    }
}
