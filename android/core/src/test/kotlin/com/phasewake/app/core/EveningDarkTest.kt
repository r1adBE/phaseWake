package com.phasewake.app.core

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** «Abends dunkel» ([EveningDark]); dieselben Fälle prüft Swift (`EveningDarkTests`). */
class EveningDarkTest {
    private companion object {
        const val SIX = EveningDark.DEFAULT_UNTIL_MINUTES
    }

    @Test
    fun sharedVectors() {
        val lines = NightFixtures.directory().resolve("evening-dark.vectors").readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() && !it.startsWith("#") }
        for (line in lines) {
            val p = line.split('\t')
            val zone = ZoneId.of(p[0])
            val now = Instant.parse(p[1])
            val enabled = p[2] == "1"
            assertEquals(8, p.size, line)
            val from = minutes(p[3])
            val until = minutes(p[4])
            assertEquals(p[5] == "1", EveningDark.isDark(now, zone, enabled, from, until), "dunkel: $line")
            assertEquals(
                p[6], EveningDark.nextChange(now, zone, enabled, from, until)?.toString() ?: "-", "Wechsel: $line",
            )
        }
        assertEquals(41, lines.size)
    }

    @Test
    fun coversAcrossMidnight() {
        assertTrue(EveningDark.covers(18 * 60, 18 * 60, SIX))
        assertTrue(EveningDark.covers(0, 18 * 60, SIX))
        assertTrue(EveningDark.covers(359, 18 * 60, SIX))
        assertFalse(EveningDark.covers(360, 18 * 60, SIX))
        assertFalse(EveningDark.covers(17 * 60 + 59, 18 * 60, SIX))
        // Beginn nach Mitternacht: nur bis 06:00 desselben Morgens.
        assertTrue(EveningDark.covers(60, 30, SIX))
        assertFalse(EveningDark.covers(23 * 60, 30, SIX))
        assertFalse(EveningDark.covers(100, SIX, SIX))
    }

    @Test
    fun coversWithinOneDay() {
        // 13:00–15:00: nur am Nachmittag, nicht über Mitternacht.
        assertFalse(EveningDark.covers(12 * 60 + 59, 13 * 60, 15 * 60))
        assertTrue(EveningDark.covers(13 * 60, 13 * 60, 15 * 60))
        assertTrue(EveningDark.covers(14 * 60 + 59, 13 * 60, 15 * 60))
        assertFalse(EveningDark.covers(15 * 60, 13 * 60, 15 * 60))
        assertFalse(EveningDark.covers(0, 13 * 60, 15 * 60))
        // Gleiche Zeiten: nie, zu jeder Minute.
        assertTrue((0 until 24 * 60).none { EveningDark.covers(it, 22 * 60, 22 * 60) })
    }

    @Test
    fun storedStartIsChecked() {
        assertEquals(20 * 60, EveningDark.DEFAULT_FROM_MINUTES)
        assertEquals(20 * 60, EveningDark.checkedFrom(null))
        assertEquals(20 * 60, EveningDark.checkedFrom(-1))
        assertEquals(20 * 60, EveningDark.checkedFrom(24 * 60))
        // Ein gespeicherter Beginn (z. B. der frühere Standard 18:00) bleibt.
        assertEquals(18 * 60, EveningDark.checkedFrom(18 * 60))
        assertEquals(0, EveningDark.checkedFrom(0))
        assertEquals(23 * 60 + 59, EveningDark.checkedFrom(23 * 60 + 59))
        assertTrue(EveningDark.DEFAULT_ENABLED)
    }

    @Test
    fun storedEndIsChecked() {
        assertEquals(6 * 60, EveningDark.DEFAULT_UNTIL_MINUTES)
        assertEquals(6 * 60, EveningDark.checkedUntil(null))
        assertEquals(6 * 60, EveningDark.checkedUntil(-1))
        assertEquals(6 * 60, EveningDark.checkedUntil(24 * 60))
        assertEquals(0, EveningDark.checkedUntil(0))
        assertEquals(23 * 60 + 59, EveningDark.checkedUntil(23 * 60 + 59))
        // Unplausibles Ende in isDark/nextChange: wie 06:00.
        val zone = ZoneId.of("Europe/Zurich")
        val at = Instant.parse("2026-11-17T04:59:00Z")
        assertTrue(EveningDark.isDark(at, zone, true, 20 * 60, 9999))
        assertEquals(Instant.parse("2026-11-17T05:00:00Z"), EveningDark.nextChange(at, zone, true, 20 * 60, -3))
    }

    @Test
    fun nextChangeAlwaysFlips() {
        // Jede Viertelstunde einer Woche mit Sommerzeit-Ende: der Wechsel liegt in der Zukunft, kippt genau dort und
        // nicht eine Minute früher.
        val zone = ZoneId.of("Europe/Zurich")
        var now = Instant.parse("2026-10-22T00:07:00Z")
        repeat(7 * 96) {
            val dark = EveningDark.isDark(now, zone, true, 19 * 60 + 45, SIX)
            val next = EveningDark.nextChange(now, zone, true, 19 * 60 + 45, SIX)!!
            assertTrue(next.isAfter(now), "$now")
            assertEquals(!dark, EveningDark.isDark(next, zone, true, 19 * 60 + 45, SIX), "$now → $next")
            assertEquals(
                dark,
                EveningDark.isDark(next.minusSeconds(60).coerceAtLeast(now), zone, true, 19 * 60 + 45, SIX),
                "$now → $next",
            )
            now = now.plusSeconds(15 * 60L)
        }
        assertNull(EveningDark.nextChange(now, zone, false, 18 * 60, SIX))
        assertNull(EveningDark.nextChange(now, zone, true, 22 * 60, 22 * 60))
    }

    @Test
    fun nextChangeFlipsWithinOneDay() {
        // Spanne 13:00–15:00 über eine Woche mit Sommerzeit-Beginn: jeder Wechsel kippt genau dort.
        val zone = ZoneId.of("Europe/Zurich")
        var now = Instant.parse("2027-03-25T00:07:00Z")
        repeat(7 * 96) {
            val dark = EveningDark.isDark(now, zone, true, 13 * 60, 15 * 60)
            val next = EveningDark.nextChange(now, zone, true, 13 * 60, 15 * 60)!!
            assertTrue(next.isAfter(now), "$now")
            assertEquals(!dark, EveningDark.isDark(next, zone, true, 13 * 60, 15 * 60), "$now → $next")
            now = now.plusSeconds(15 * 60L)
        }
    }

    private fun minutes(hhmm: String): Int = hhmm.split(':').map { it.toInt() }.let { (h, m) -> h * 60 + m }

    private fun Instant.coerceAtLeast(min: Instant): Instant = if (isBefore(min)) min else this
}
