package com.phasewake.app.ui

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/** Eine Nachkommastelle mit dem Dezimalzeichen der Sprache: «Befinden 1,5» auch in der Schweiz, nicht «1.5». */
class FormatDecimalTest {
    private fun decimal(tag: String, value: Double, signed: Boolean = false) =
        Format.decimal(Locale.forLanguageTag(tag), value, signed)

    @Test
    fun separatorFollowsTheLanguage() {
        assertEquals("1,5", decimal("de-CH", 1.5))
        assertEquals("1,5", decimal("de-DE", 1.5))
        assertEquals("1,5", decimal("fr-CH", 1.5))
        assertEquals("1,5", decimal("ru", 1.5))
        assertEquals("1,5", decimal("fi", 1.5))
        assertEquals("1.5", decimal("en-US", 1.5))
        assertEquals("3.0", decimal("en", 3.0))
    }

    @Test
    fun signedUsesARealMinus() {
        assertEquals("−0,8", decimal("de-CH", -0.8, signed = true))
        assertEquals("+0,5", decimal("de-CH", 0.5, signed = true))
        assertEquals("+0.5", decimal("en", 0.5, signed = true))
    }

    @Test
    fun roundsHalfUpLikeBefore() {
        assertEquals("3,3", decimal("de", 3.25))
        assertEquals("3,8", decimal("de", 3.75))
    }

    /** Ziffern bleiben die der Sprache und Region (wie `String.format`); Arabisch mit ٠–٩. */
    @Test
    fun keepsTheDigitsOfTheLocale() {
        for (tag in listOf("ar", "ar-EG", "fa", "hi", "de-CH")) {
            val locale = Locale.forLanguageTag(tag)
            val text = decimal(tag, 1.5)
            assertEquals(String.format(locale, "%d", 1), text.take(1), tag)
            assertEquals(String.format(locale, "%d", 5), text.takeLast(1), tag)
        }
    }
}
