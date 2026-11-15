package com.phasewake.app.wear.ui

import androidx.compose.ui.graphics.Color
import com.phasewake.app.wear.ui.components.SunriseSky
import com.phasewake.app.wear.ui.theme.Brand
import com.phasewake.app.wear.ui.theme.PAGE_GLOW
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Seiten der Uhr im Stil des Starts (Weckerliste, Editor, Powernap, Diagnose) auf dem schwachen Nachthimmel
 * ([PAGE_GLOW]): Schrift, Nebentext und Lavendel bleiben ≥ 4.5:1, oben im Lavendel-Schein (ungünstig: voll) wie unten.
 */
class WatchPagesContrastTest {

    private fun mix(a: Color, b: Color, t: Float) = Color(
        red = a.red + (b.red - a.red) * t,
        green = a.green + (b.green - a.green) * t,
        blue = a.blue + (b.blue - a.blue) * t,
    )

    @Test
    fun textReadableOnPageGlow() {
        val top = mix(Color.Black, Brand.Night, PAGE_GLOW)
        val middle = mix(Color.Black, Brand.NightBottom, PAGE_GLOW)
        val backgrounds = listOf(top, middle, Color.Black, mix(top, Brand.Glow, 0.5f * PAGE_GLOW))
        for (bg in backgrounds) for (ink in listOf(Brand.Moon, Brand.Mist, Brand.Muted, Brand.Wave)) {
            val ratio = SunriseSky.contrast(ink, bg)
            assertTrue(ratio >= SunriseSky.MIN_CONTRAST, "$ink auf $bg: $ratio:1")
        }
    }
}
