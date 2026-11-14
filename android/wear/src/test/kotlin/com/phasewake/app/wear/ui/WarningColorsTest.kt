package com.phasewake.app.wear.ui

import com.phasewake.app.wear.ui.components.SunriseSky
import com.phasewake.app.wear.ui.theme.colors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Warnknopf «Wecker blockiert» auf der Uhr (Audit O-04): Schrift auf der Fehlerfläche ≥ 4.5:1, Farben aus dem Schema. */
class WarningColorsTest {

    @Test
    fun blockedWarningIsReadable() {
        val (container, content) = warningColors(colors)
        assertEquals(colors.errorContainer, container)
        assertEquals(colors.onErrorContainer, content)
        val ratio = SunriseSky.contrast(content, container)
        assertTrue(ratio >= SunriseSky.MIN_CONTRAST, "Kontrast $ratio:1")
    }
}
