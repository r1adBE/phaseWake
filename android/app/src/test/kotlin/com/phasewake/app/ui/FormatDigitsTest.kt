package com.phasewake.app.ui

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Audit UI-9: Auf Arabisch erschienen Zeiten mit 0–9, Zahlen aus Texten (`getString` mit %d) mit ٠–٩.
 * [Format.digits] und [Format.number] nehmen dieselben Ziffern wie `String.format` der Sprache.
 */
class FormatDigitsTest {

    @Test
    fun timesUseSameDigitsAsFormattedNumbers() {
        for (tag in listOf("ar", "ar-EG", "fa", "de-CH", "hi", "bn", "en")) {
            val locale = Locale.forLanguageTag(tag)
            val time = Format.digits(DateTimeFormatter.ofPattern("HH:mm", locale), locale).format(LocalTime.of(6, 40))
            assertEquals(String.format(locale, "%02d:%02d", 6, 40), time, tag)
        }
    }

    @Test
    fun numberUsesDefaultLocaleDigits() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar"))
            assertEquals(String.format(Locale.forLanguageTag("ar"), "%d", 5), Format.number(5))
        } finally {
            Locale.setDefault(before)
        }
    }

    /**
     * Nachtrag: Mitteilungen (Erinnerung vor dem Schlafen, nächster Wecker) und Widget nehmen [Format.time]; Arabisch
     * mit ٠–٩, Deutsch mit 0–9.
     */
    @Test
    fun timeForNotificationsAndWidget() {
        val before = Locale.getDefault()
        val mode = Format.use24Hour
        try {
            Format.use24Hour = null
            Locale.setDefault(Locale.forLanguageTag("ar"))
            val arabic = Format.time(LocalTime.of(6, 30))
            assertTrue(arabic.any { it in '٠'..'٩' } && arabic.none { it in '0'..'9' }, arabic)
            Locale.setDefault(Locale.forLanguageTag("de-CH"))
            assertEquals("06:30", Format.time(LocalTime.of(6, 30)))
        } finally {
            Locale.setDefault(before)
            Format.use24Hour = mode
        }
    }
}
