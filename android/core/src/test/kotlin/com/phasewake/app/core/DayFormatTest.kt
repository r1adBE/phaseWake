package com.phasewake.app.core

import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class DayFormatTest {
    private val de = Locale.GERMAN

    private fun f(vararg days: java.time.DayOfWeek, locale: Locale = de) =
        DayFormat.format(days.toSet(), locale, "Einmalig", "Täglich")

    @Test
    fun rangesAndSingles() {
        assertEquals("Einmalig", f())
        assertEquals("Täglich", f(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY))
        assertEquals("Mo–Fr", f(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY))
        assertEquals("Sa, So", f(SUNDAY, SATURDAY))
        assertEquals("Mo, Mi, Fr", f(MONDAY, WEDNESDAY, FRIDAY))
        assertEquals("Mo–Mi, Sa", f(MONDAY, TUESDAY, WEDNESDAY, SATURDAY))
        assertEquals("Mon–Fri", f(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, locale = Locale.ENGLISH))
    }
}
