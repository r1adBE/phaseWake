package com.phasewake.app.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** Feiertage auswählen und für die Uhr kodieren; Gegenstück: `HolidaysTests` (Swift). */
class HolidaysTest {
    private val today = LocalDate.of(2026, 10, 29)

    @Test
    fun resolveMergesRegionAndLimitsRange() {
        val national = listOf("2026-01-01", "2026-12-25", "2027-12-25", "kaputt")
        val regional = listOf("2026-12-26", "2026-12-25")
        val dates = Holidays.resolve(national, regional, today)
        assertEquals(listOf(LocalDate.of(2026, 12, 25), LocalDate.of(2026, 12, 26)), dates.toList())
        // Heute zählt, das Ende (heute + Tage) nicht mehr.
        assertEquals(setOf(today), Holidays.resolve(listOf("2026-10-29", "2026-10-31"), emptyList(), today, days = 2))
    }

    @Test
    fun encodeAndDecode() {
        val list = HolidayList("CH-BE", setOf(LocalDate.of(2026, 12, 26), LocalDate.of(2026, 12, 25)))
        val text = Holidays.encode(list)
        assertEquals("CH-BE|2026-12-25,2026-12-26", text)
        assertEquals(list, Holidays.decode(text))
        assertEquals(HolidayList("CH", emptySet()), Holidays.decode(Holidays.encode(HolidayList("CH", emptySet()))))
    }

    @Test
    fun decodeIsTolerant() {
        assertEquals(HolidayList("", emptySet()), Holidays.decode(null))
        assertEquals(HolidayList("", emptySet()), Holidays.decode(" "))
        assertEquals(HolidayList("DE", setOf(LocalDate.of(2026, 10, 3))), Holidays.decode("DE|2026-10-03,2026-13-01,"))
        assertEquals(HolidayList("", setOf(LocalDate.of(2026, 10, 3))), Holidays.decode("|2026-10-03"))
    }

    @Test
    fun regionCode() {
        assertEquals("", Holidays.regionCode("", "BE"))
        assertEquals("CH", Holidays.regionCode("CH", null))
        assertEquals("CH", Holidays.regionCode("CH", ""))
        assertEquals("CH-BE", Holidays.regionCode("CH", "BE"))
    }
}
