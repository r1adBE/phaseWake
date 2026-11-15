package com.phasewake.app.wear.ui

import androidx.compose.ui.graphics.Color
import com.phasewake.app.wear.ui.components.SunriseSky
import com.phasewake.app.wear.ui.theme.Brand
import com.phasewake.app.wear.ui.theme.WakeSky
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Weckhimmel ohne Sonnenaufgangslicht: Schrift (Mond, Nebel) bleibt überall ≥ 4.5:1, ungünstig gerechnet mit dem warmen
 * Schein unten und dem Lavendel-Schein oben in voller Stärke.
 */
class WakeSkyContrastTest {

    private fun mix(a: Color, b: Color, t: Float) = Color(
        red = a.red + (b.red - a.red) * t,
        green = a.green + (b.green - a.green) * t,
        blue = a.blue + (b.blue - a.blue) * t,
    )

    private fun skyAt(y: Float): Color {
        val i = WakeSky.Stops.indexOfLast { it.first <= y }.coerceAtMost(WakeSky.Stops.size - 2)
        val (a, ca) = WakeSky.Stops[i]
        val (b, cb) = WakeSky.Stops[i + 1]
        val base = mix(ca, cb, ((y - a) / (b - a)).coerceIn(0f, 1f))
        val warm = if (y >= WakeSky.SUN_FROM) mix(base, WakeSky.Sun, WakeSky.SUN_ALPHA) else base
        return if (y <= WakeSky.GLOW_REACH) mix(warm, Brand.Glow, WakeSky.GLOW_ALPHA) else warm
    }

    @Test
    fun textReadableOnWakeSky() {
        for (step in 0..40) {
            val y = step / 40f
            val sky = skyAt(y)
            for (ink in listOf(Brand.Moon, Brand.Mist)) {
                val ratio = SunriseSky.contrast(ink, sky)
                assertTrue(ratio >= SunriseSky.MIN_CONTRAST, "y=$y: $ratio:1")
            }
        }
    }
}
