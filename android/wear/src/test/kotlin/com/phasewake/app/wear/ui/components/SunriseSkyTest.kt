package com.phasewake.app.wear.ui.components

import kotlin.test.Test
import kotlin.test.assertTrue

/** Schrift auf dem Sonnenaufgang der Uhr (Audit UI-6): an jeder Stelle des Zifferblatts ≥ 4.5:1. */
class SunriseSkyTest {

    @Test
    fun textContrastAtLeast45OverWholeSunrise() {
        for (step in 0..100) {
            val p = step / 100f
            for (yStep in 1..19) for (x in listOf(0.3f, 0.5f, 0.7f)) {
                val y = yStep / 20f
                val ink = SunriseSky.ink(p, SunriseSky.Area(x, y, x, y))
                val bg = SunriseSky.backgroundAt(p, x, y, SunriseSky.ASPECT)
                assertTrue(SunriseSky.contrast(ink.content, bg) >= SunriseSky.MIN_CONTRAST, "p=$p y=$y x=$x")
                assertTrue(SunriseSky.contrast(ink.muted, bg) >= SunriseSky.MIN_CONTRAST, "leise p=$p y=$y x=$x")
            }
        }
    }

    @Test
    fun centerTimeKeepsContrast() {
        // Die Uhrzeit des Lichtweckers steht in der Mitte ([SunriseSky.colors]).
        for (step in 0..100) {
            val p = step / 100f
            val bg = SunriseSky.backgroundAt(p, 0.5f, 0.5f, SunriseSky.ASPECT)
            assertTrue(SunriseSky.contrast(SunriseSky.colors(p).content, bg) >= SunriseSky.MIN_CONTRAST, "p=$p")
        }
    }
}
