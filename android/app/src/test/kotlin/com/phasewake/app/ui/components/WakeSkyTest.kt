package com.phasewake.app.ui.components

import kotlin.test.Test
import kotlin.test.assertTrue

/** Weckhimmel ohne Sonnenaufgangslicht: Uhrzeit, Gruss und Schlummern (hell) bleiben überall ≥ 4.5:1. */
class WakeSkyTest {

    @Test
    fun textReadableEverywhere() {
        for (step in 0..40) {
            val sky = WakeSky.at(step / 40f)
            for (ink in listOf(Night.Text, Night.Muted)) {
                val ratio = SunriseSky.contrast(ink, sky)
                assertTrue(ratio >= SunriseSky.MIN_CONTRAST, "y=${step / 40f}: $ratio:1")
            }
        }
    }
}
