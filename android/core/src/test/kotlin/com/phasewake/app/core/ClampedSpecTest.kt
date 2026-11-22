package com.phasewake.app.core

import java.time.DayOfWeek
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ungültige Werte auf gültig begrenzen ([ClampedSpec]). Gegenstück: `ClampedSpecTests` (Swift), gleiche Fälle. */
class ClampedSpecTest {

    @Test
    fun sharedVectors() {
        // Gemeinsame Fälle mit Swift (shared/testdata/alarm-clamp.vectors); Spalte 2 = Android (Fenster bis 60).
        val lines = NightFixtures.directory().resolve("alarm-clamp.vectors").readLines(Charsets.UTF_8)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        for (line in lines) {
            val p = line.split('\t')
            assertEquals(3, p.size, line)
            val input = p[0].split('|')
            val expected = p[1].split('|')
            assertEquals(5, input.size, line)
            assertEquals(5, expected.size, line)
            val clamped = ClampedSpec(
                id = input[0],
                hour = input[1].toInt(),
                minute = input[2].toInt(),
                windowMinutes = input[3].toInt(),
                sleepDurationMinutes = input[4].takeIf { it != "-" }?.toInt(),
            )
            val spec = clamped.spec
            if (input[0].isBlank()) {
                assertTrue(spec.id.isNotBlank(), line)
                assertNotEquals(input[0], spec.id, line)
            } else {
                assertEquals(input[0], spec.id, line)
            }
            assertEquals(expected[0].toInt(), spec.hour, line)
            assertEquals(expected[1].toInt(), spec.minute, line)
            assertEquals(expected[2].toInt(), spec.windowMinutes, line)
            assertEquals(expected[3].takeIf { it != "-" }?.toInt(), spec.sleepDurationMinutes, line)
            assertEquals(expected[4].takeIf { it != "-" }, clamped.diagLine, line)
            assertEquals(clamped.diagLine == null, clamped.changes.isEmpty(), line)
        }
        assertEquals(25, lines.size)
    }

    @Test
    fun validValuesGiveTheSameSpecAsTheConstructor() {
        val clamped = ClampedSpec(
            id = "a", hour = 6, minute = 45, days = setOf(DayOfWeek.MONDAY, DayOfWeek.SATURDAY), windowMinutes = 30,
            enabled = false, phone = PhoneAlert.VIBRATE, watchSound = true, sound = "raw:wake_birds",
            sleepDurationMinutes = 450, label = "Arbeit", sunrise = Sunrise.WINDOW, sunriseDevice = SunriseDevice.BOTH,
            wakeTask = WakeTask.MATH, skipUntil = 1_794_200_000_000L, skipHolidays = true,
        )
        val direct = AlarmSpec(
            id = "a", hour = 6, minute = 45, days = setOf(DayOfWeek.MONDAY, DayOfWeek.SATURDAY), windowMinutes = 30,
            enabled = false, phone = PhoneAlert.VIBRATE, watchSound = true, sound = "raw:wake_birds",
            sleepDurationMinutes = 450, label = "Arbeit", sunrise = Sunrise.WINDOW, sunriseDevice = SunriseDevice.BOTH,
            wakeTask = WakeTask.MATH, skipUntil = 1_794_200_000_000L, skipHolidays = true,
        )
        assertEquals(direct, clamped.spec)
        assertEquals(emptyList(), clamped.changes)
        assertNull(clamped.diagLine)
    }

    @Test
    fun fieldsWithoutRulesAreKeptWhenClamping() {
        val clamped = ClampedSpec(
            id = "a", hour = 25, minute = 0, days = setOf(DayOfWeek.FRIDAY), windowMinutes = 5, enabled = false,
            phone = PhoneAlert.OFF, label = "Früh", sunrise = Sunrise.RING, skipUntil = 5L, skipHolidays = true,
        )
        assertEquals(
            listOf(SpecChange("hour", "25", "23"), SpecChange("windowMinutes", "5", "10")),
            clamped.changes,
        )
        val spec = clamped.spec
        assertEquals(setOf(DayOfWeek.FRIDAY), spec.days)
        assertEquals(false, spec.enabled)
        assertEquals(PhoneAlert.OFF, spec.phone)
        assertEquals("Früh", spec.label)
        assertEquals(Sunrise.RING, spec.sunrise)
        assertEquals(5L, spec.skipUntil)
        assertEquals(true, spec.skipHolidays)
    }

    @Test
    fun windowAndSleepRules() {
        assertEquals(AlarmSpec.EXACT_WINDOW, ClampedSpec.window(0))
        assertEquals(AlarmSpec.MIN_WINDOW_MINUTES, ClampedSpec.window(-1))
        assertEquals(AlarmSpec.MAX_WINDOW_MINUTES, ClampedSpec.window(Int.MAX_VALUE))
        assertEquals(null, ClampedSpec.sleep(null))
        assertEquals(AlarmSpec.MAX_SLEEP_MINUTES, ClampedSpec.sleep(Int.MAX_VALUE))
        assertEquals("SPEC clamped", ClampedSpec.DIAG_PREFIX)
        assertEquals("windowMinutes 45->30", SpecChange("windowMinutes", "45", "30").toString())
    }
}
