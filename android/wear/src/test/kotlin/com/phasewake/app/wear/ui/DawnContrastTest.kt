package com.phasewake.app.wear.ui

import androidx.compose.ui.graphics.Color
import com.phasewake.app.wear.ui.components.SunriseSky
import com.phasewake.app.wear.ui.theme.Brand
import com.phasewake.app.wear.ui.theme.Dawn
import kotlin.test.Test
import kotlin.test.assertTrue

/** Morgendämmerung am unteren Rand des Weckbildschirms: Schrift (Mond, Nebel, leise) bleibt überall ≥ 4.5:1. */
class DawnContrastTest {

    private fun mix(a: Color, b: Color, t: Float) = Color(
        red = a.red + (b.red - a.red) * t,
        green = a.green + (b.green - a.green) * t,
        blue = a.blue + (b.blue - a.blue) * t,
    )

    @Test
    fun textReadableOverDawn() {
        for (step in 0..20) {
            val y = step / 20f
            // Grundverlauf wie nightGlow: Nacht → Nachtgrund (Mitte) → Schwarz.
            val base = if (y < 0.5f) mix(Brand.Night, Brand.NightBottom, y * 2) else mix(Brand.NightBottom, Color.Black, (y - 0.5f) * 2)
            val f = ((y - Dawn.START) / (1f - Dawn.START)).coerceIn(0f, 1f)
            val sky = mix(base, Dawn.Color, Dawn.MAX_ALPHA * f)
            for (ink in listOf(Brand.Moon, Brand.Mist, Brand.Muted)) {
                val ratio = SunriseSky.contrast(ink, sky)
                assertTrue(ratio >= SunriseSky.MIN_CONTRAST, "y=$y: $ratio:1")
            }
        }
    }
}
