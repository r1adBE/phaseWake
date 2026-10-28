package com.phasewake.app.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SleepNotesTest {
    private val zone: ZoneId = ZoneId.of("Europe/Zurich")
    private fun at(text: String): Instant = LocalDateTime.parse(text).atZone(zone).toInstant()
    private val day = LocalDate.of(2026, 10, 27)

    @Test
    fun nightDateIsTheEvening() {
        // Fensterbeginn 06:00 am 28. → Nacht vom 27.
        assertEquals(day, SleepNotes.nightDate(at("2026-10-28T06:00"), zone))
        assertEquals(day, SleepNotes.nightDate(at("2026-10-27T18:00"), zone))
        assertEquals(day, SleepNotes.nightDate(at("2026-10-28T02:00"), zone))
        // Mittag gehört schon zum kommenden Abend.
        assertEquals(day, SleepNotes.nightDate(at("2026-10-27T12:00"), zone))
        assertEquals(day.minusDays(1), SleepNotes.nightDate(at("2026-10-27T11:59"), zone))
    }

    @Test
    fun entryVisibleFromEveningUntilWindow() {
        val window = at("2026-10-28T06:00")
        assertFalse(SleepNotes.entryVisible(at("2026-10-27T16:59"), zone, window))
        assertTrue(SleepNotes.entryVisible(at("2026-10-27T17:00"), zone, window))
        assertTrue(SleepNotes.entryVisible(at("2026-10-28T02:00"), zone, window))
        assertFalse(SleepNotes.entryVisible(at("2026-10-28T06:00"), zone, window))
        // Ohne Wecker in dieser Nacht: bis Mitternacht.
        assertTrue(SleepNotes.entryVisible(at("2026-10-27T23:59"), zone, null))
        assertFalse(SleepNotes.entryVisible(at("2026-10-28T00:30"), zone, null))
        // Ein Fenster erst in der übernächsten Nacht zählt nicht für heute Vormittag.
        assertFalse(SleepNotes.entryVisible(at("2026-10-28T10:00"), zone, at("2026-10-29T06:00")))
        assertTrue(SleepNotes.entryVisible(at("2026-10-27T20:00"), zone, at("2026-10-29T06:00")))
    }

    @Test
    fun toggleAndStorageRoundTrip() {
        var notes = SleepNotes.toggle(emptyMap(), day, SleepNotes.ALCOHOL)
        notes = SleepNotes.toggle(notes, day, "custom:abc")
        notes = SleepNotes.toggle(notes, day.minusDays(1), SleepNotes.SICK)
        assertEquals(listOf(SleepNotes.ALCOHOL, "custom:abc"), notes[day])
        val text = SleepNotes.encode(notes)
        assertEquals("2026-10-26|SICK\n2026-10-27|ALCOHOL,custom:abc", text)
        assertEquals(notes, SleepNotes.decode(text))
        // Abschalten der letzten Notiz entfernt das Datum.
        assertNull(SleepNotes.toggle(notes, day.minusDays(1), SleepNotes.SICK)[day.minusDays(1)])
        assertEquals(mapOf(day to listOf("NAP")), SleepNotes.decode("kaputt\n2026-10-27|NAP,,NAP\n2026-10-28|"))
    }

    @Test
    fun keepsNewestNinetyDates() {
        val many = (0L until 100L).associate { day.minusDays(it) to listOf(SleepNotes.NAP) }
        val kept = SleepNotes.trim(many)
        assertEquals(90, kept.size)
        assertEquals(day.minusDays(89), kept.keys.first())
        assertEquals(day, kept.keys.last())
    }

    @Test
    fun customNotesRoundTripAndLimits() {
        val notes = listOf(CustomNote("custom:1", "Meditation"), CustomNote("custom:2", "  Lange Arbeit | spät\n"))
        val text = SleepNotes.encodeCustom(notes)
        assertEquals("custom:1|Meditation\ncustom:2|Lange Arbeit | spät", text)
        assertEquals(listOf(CustomNote("custom:1", "Meditation"), CustomNote("custom:2", "Lange Arbeit | spät")), SleepNotes.decodeCustom(text))
        assertEquals(30, SleepNotes.cleanName("x".repeat(40)).length)
        assertEquals(emptyList(), SleepNotes.decodeCustom("ALCOHOL|x\ncustom:|y\ncustom:3|"))
        assertEquals(5, SleepNotes.decodeCustom((1..7).joinToString("\n") { "custom:$it|n$it" }).size)
        assertEquals("custom", SleepNotes.exportId("custom:1"))
        assertEquals("CAFFEINE", SleepNotes.exportId(SleepNotes.CAFFEINE))
    }

    private val base = at("2026-09-01T06:00")

    /** Nacht [index] Tage nach [base], Fensterbeginn 06:00. */
    private fun night(index: Long, feeling: Int?) = NightRecord(
        alarmId = "a",
        windowStart = base.plus(Duration.ofDays(index)),
        target = base.plus(Duration.ofDays(index)).plusSeconds(1800),
        wokeAt = base.plus(Duration.ofDays(index)).plusSeconds(1200),
        reason = WakeReason.LIGHT_SLEEP,
        snoozes = 0,
        epochs = emptyList(),
        feeling = feeling,
    )

    private fun dateOf(index: Long) = SleepNotes.nightDate(base.plus(Duration.ofDays(index)), zone)

    @Test
    fun noteEffectNeedsThreeNightsEachSide() {
        // Nächte 0–2 mit Alkohol (Befinden 2), 3–6 ohne (Befinden 4); Kaffee nur zweimal.
        val nights = (0L..6L).map { night(it, if (it < 3) 2 else 4) } + night(7, null)
        val notes = mapOf(
            dateOf(0) to listOf(SleepNotes.ALCOHOL, SleepNotes.CAFFEINE),
            dateOf(1) to listOf(SleepNotes.ALCOHOL),
            dateOf(2) to listOf(SleepNotes.ALCOHOL, SleepNotes.CAFFEINE),
            dateOf(7) to listOf(SleepNotes.SICK), // Nacht ohne Befinden: zählt nicht
        )
        val effects = NoteEffect.compute(nights, notes, zone)
        assertEquals(1, effects.size)
        val alcohol = effects.single()
        assertEquals(SleepNotes.ALCOHOL, alcohol.noteId)
        assertEquals(3, alcohol.nightsWith)
        assertEquals(4, alcohol.nightsWithout)
        assertEquals(2.0, alcohol.avgFeelingWith, 1e-9)
        assertEquals(4.0, alcohol.avgFeelingWithout, 1e-9)
        assertEquals(-2.0, alcohol.delta, 1e-9)
        assertTrue(alcohol.isNotable)
        assertNull(alcohol.asleepWith)
        assertNull(alcohol.asleepWithout)
    }

    @Test
    fun noteEffectSleepDurationAndOrder() {
        // Sport: mit 4,4,4, ohne 3,3,3 → +1; Stress umgekehrt → −1. Gleichstand: nach Kennung.
        val feelings = listOf(4, 4, 4, 3, 3, 3)
        val nights = feelings.mapIndexed { i, f -> night(i.toLong(), f) }
        val notes = mapOf(
            dateOf(0) to listOf(SleepNotes.EXERCISE),
            dateOf(1) to listOf(SleepNotes.EXERCISE),
            dateOf(2) to listOf(SleepNotes.EXERCISE),
            dateOf(3) to listOf(SleepNotes.STRESS),
            dateOf(4) to listOf(SleepNotes.STRESS),
            dateOf(5) to listOf(SleepNotes.STRESS),
        )
        val asleep = (0L..5L).associate { dateOf(it) to if (it < 3) 480L else 400L }
        val effects = NoteEffect.compute(nights, notes, zone, asleep)
        assertEquals(listOf(SleepNotes.EXERCISE, SleepNotes.STRESS), effects.map { it.noteId })
        assertEquals(1.0, effects[0].delta, 1e-9)
        assertEquals(480.0, effects[0].asleepWith!!, 1e-9)
        assertEquals(400.0, effects[0].asleepWithout!!, 1e-9)
        // Nur zwei Nächte mit Schlafdauer auf einer Seite: keine Angabe.
        val partial = NoteEffect.compute(nights, notes, zone, asleep - dateOf(0))
        assertNull(partial.first { it.noteId == SleepNotes.EXERCISE }.asleepWith)
        assertNull(partial.first { it.noteId == SleepNotes.EXERCISE }.asleepWithout)
        // Kleiner Unterschied ist nicht deutlich.
        val small = NoteEffect(SleepNotes.NAP, 3, 3, 3.4, 3.0, null, null)
        assertFalse(small.isNotable)
        assertTrue(NoteEffect(SleepNotes.NAP, 3, 3, 3.5, 3.0, null, null).isNotable)
    }

    @Test
    fun sameNightDateCountsOnceAndHealthNightsMapByEnd() {
        // Zwei Wecker am selben Morgen: eine Nacht, das Befinden der zuletzt geweckten zählt.
        val first = night(0, 1)
        val second = first.copy(alarmId = "b", windowStart = first.windowStart.plusSeconds(1800), wokeAt = first.wokeAt.plusSeconds(1800), feeling = 5)
        val nights = listOf(first, second) + (1L..5L).map { night(it, 3) }
        val notes = (0L..2L).associate { dateOf(it) to listOf(SleepNotes.NAP) }
        val effect = NoteEffect.compute(nights, notes, zone).single()
        assertEquals(3, effect.nightsWith)
        assertEquals((5.0 + 3 + 3) / 3, effect.avgFeelingWith, 1e-9)

        val sleeps = listOf(SleepSummary(at("2026-10-27T23:00"), at("2026-10-28T06:30"), 420), SleepSummary(at("2026-10-28T13:00"), at("2026-10-28T16:30"), 200))
        assertEquals(mapOf(day to 420L, day.plusDays(1) to 200L), NoteEffect.asleepByNight(sleeps, zone))
    }

    @Test
    fun csvCarriesNotes() {
        val nights = listOf(night(0, 3).copy(epochs = listOf(Epoch(0.0, 50.0))))
        val csv = NightHistory.exportCsv(nights, mapOf(dateOf(0) to listOf(SleepNotes.ALCOHOL, "custom:x")), zone)
        assertEquals("1,30,LIGHT_SLEEP,10,0,3,0,0.000000,50.0,ALCOHOL;custom", csv.trimEnd().lines()[1])
    }
}
