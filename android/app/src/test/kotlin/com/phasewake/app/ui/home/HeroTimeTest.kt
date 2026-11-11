package com.phasewake.app.ui.home

import com.phasewake.app.ui.Format
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Gestaltung 1 / Audit N-2: Im Weckfenster der Startseite steht AM/PM (Arabisch «ص»/«م») klein; im 24-h-Format gibt
 * es keinen Zusatz. Vorher brach das arabische Fenster um und «ص» stand riesig auf einer eigenen Zeile.
 */
class HeroTimeTest {
    private val zone = ZoneId.of("Europe/Zurich")
    private val start = LocalDateTime.of(2026, 11, 12, 6, 40).atZone(zone).toInstant()
    private val target = LocalDateTime.of(2026, 11, 12, 7, 0).atZone(zone).toInstant()

    private fun <T> inLocale(tag: String, block: () -> T): T {
        val before = Locale.getDefault()
        val mode = Format.use24Hour
        try {
            Locale.setDefault(Locale.forLanguageTag(tag))
            Format.use24Hour = null
            return block()
        } finally {
            Locale.setDefault(before)
            Format.use24Hour = mode
        }
    }

    @Test
    fun periodFoundInTwelveHourLocales() {
        for (tag in listOf("en-US", "ar", "sq", "ko")) inLocale(tag) {
            val (text, period) = Format.timeWithPeriod(target, zone)
            val range = period ?: error("$tag: kein Zusatz in «$text»")
            val marker = text.substring(range)
            assertTrue(marker.none { it.isDigit() }, "$tag: «$marker»")
        }
    }

    @Test
    fun noPeriodInTwentyFourHourLocale() = inLocale("de-CH") {
        assertNull(Format.timeWithPeriod(target, zone).second)
    }

    @Test
    fun bothPeriodsOfWindowSetSmall() = inLocale("ar") {
        val a = Format.timeWithPeriod(start, zone)
        val b = Format.timeWithPeriod(target, zone)
        val line = a.first + " – " + b.first
        val text = heroText(line, listOf(a, b), 0)
        assertEquals(line, text.text)
        assertEquals(2, text.spanStyles.size)
        val second = text.spanStyles[1]
        assertEquals(b.second!!.first + a.first.length + 3, second.start)
    }

    @Test
    fun exactLineFindsTimeInsideSentence() = inLocale("en-US") {
        val t = Format.timeWithPeriod(target, zone)
        val line = "Exactly at ${t.first}"
        val text = heroText(line, listOf(t), line.indexOf(t.first))
        assertEquals(1, text.spanStyles.size)
        assertEquals("AM", line.substring(text.spanStyles[0].start, text.spanStyles[0].end))
    }

    @Test
    fun noPeriodNoSpan() = inLocale("de-CH") {
        val t = Format.timeWithPeriod(target, zone)
        assertTrue(heroText(t.first, listOf(t), 0).spanStyles.isEmpty())
    }
}
