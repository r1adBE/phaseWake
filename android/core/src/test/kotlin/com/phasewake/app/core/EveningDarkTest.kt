package com.phasewake.app.core

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** «Abends dunkel ab» ([EveningDark]); dieselben Fälle prüft Swift (`EveningDarkTests`). */
class EveningDarkTest {

    @Test
    fun sharedVectors() {
        val lines = NightFixtures.directory().resolve("evening-dark.vectors").readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() && !it.startsWith("#") }
        for (line in lines) {
            val p = line.split('\t')
            val zone = ZoneId.of(p[0])
            val now = Instant.parse(p[1])
            val enabled = p[2] == "1"
            val (h, m) = p[3].split(':').map { it.toInt() }
            val from = h * 60 + m
            assertEquals(p[4] == "1", EveningDark.isDark(now, zone, enabled, from), "dunkel: $line")
            assertEquals(p[5], EveningDark.nextChange(now, zone, enabled, from)?.toString() ?: "-", "Wechsel: $line")
        }
        assertEquals(25, lines.size)
    }

    @Test
    fun coversAcrossMidnight() {
        assertTrue(EveningDark.covers(18 * 60, 18 * 60))
        assertTrue(EveningDark.covers(0, 18 * 60))
        assertTrue(EveningDark.covers(359, 18 * 60))
        assertFalse(EveningDark.covers(360, 18 * 60))
        assertFalse(EveningDark.covers(17 * 60 + 59, 18 * 60))
        // Beginn nach Mitternacht: nur bis 06:00 desselben Morgens.
        assertTrue(EveningDark.covers(60, 30))
        assertFalse(EveningDark.covers(23 * 60, 30))
        assertFalse(EveningDark.covers(100, EveningDark.UNTIL_MINUTES))
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
    fun nextChangeAlwaysFlips() {
        // Jede Viertelstunde einer Woche mit Sommerzeit-Ende: der Wechsel liegt in der Zukunft, kippt genau dort und
        // nicht eine Minute früher.
        val zone = ZoneId.of("Europe/Zurich")
        var now = Instant.parse("2026-10-22T00:07:00Z")
        repeat(7 * 96) {
            val dark = EveningDark.isDark(now, zone, true, 19 * 60 + 45)
            val next = EveningDark.nextChange(now, zone, true, 19 * 60 + 45)!!
            assertTrue(next.isAfter(now), "$now")
            assertEquals(!dark, EveningDark.isDark(next, zone, true, 19 * 60 + 45), "$now → $next")
            assertEquals(
                dark,
                EveningDark.isDark(next.minusSeconds(60).coerceAtLeast(now), zone, true, 19 * 60 + 45),
                "$now → $next",
            )
            now = now.plusSeconds(15 * 60L)
        }
        assertNull(EveningDark.nextChange(now, zone, false, 18 * 60))
    }

    private fun Instant.coerceAtLeast(min: Instant): Instant = if (isBefore(min)) min else this
}
