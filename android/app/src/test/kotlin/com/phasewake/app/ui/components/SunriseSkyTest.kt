package com.phasewake.app.ui.components

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Schrift auf dem Sonnenaufgang (Audit UI-6): Mit einem festen Wechsel bei 0.635 hatte der Text bei 0.62 nur etwa
 * 2.5:1. [SunriseSky.ink] wählt die Schrift nach der Stelle des Texts; überall ≥ 4.5:1, im Hoch- und Querformat und
 * auf der runden Uhr (Seitenverhältnis 1).
 */
class SunriseSkyTest {

    private val aspects = listOf(SunriseSky.PORTRAIT, 640f / 360f, 411f / 891f, 1f)

    @Test
    fun textContrastAtLeast45OverWholeSunrise() {
        for (step in 0..100) {
            val p = step / 100f
            for (aspect in aspects) for (yStep in 1..19) for (x in listOf(0.3f, 0.5f, 0.7f)) {
                val y = yStep / 20f
                val area = SunriseSky.Area(x, y, x, y)
                val ink = SunriseSky.ink(p, area, aspect)
                val bg = SunriseSky.backgroundAt(p, x, y, aspect)
                val content = SunriseSky.contrast(ink.content, bg)
                val muted = SunriseSky.contrast(ink.muted, bg)
                assertTrue(content >= SunriseSky.MIN_CONTRAST, "p=$p y=$y x=$x aspect=$aspect: $content")
                assertTrue(muted >= SunriseSky.MIN_CONTRAST, "leise p=$p y=$y x=$x aspect=$aspect: $muted")
            }
        }
    }

    @Test
    fun auditCaseLowerScreenAt062() {
        // Fall aus dem Audit: Fortschritt 0.62, Schlummer-Knopf unten im Hochformat.
        val area = SunriseSky.Area(0.2f, 0.84f, 0.8f, 0.9f)
        val ink = SunriseSky.ink(0.62f, area)
        for (x in listOf(0.2f, 0.5f, 0.8f)) for (y in listOf(0.84f, 0.87f, 0.9f)) {
            assertTrue(SunriseSky.contrast(ink.content, SunriseSky.backgroundAt(0.62f, x, y, SunriseSky.PORTRAIT)) >= SunriseSky.MIN_CONTRAST)
        }
    }

    @Test
    fun centerColorsKeepContrast() {
        for (step in 0..100) {
            val p = step / 100f
            val c = SunriseSky.colors(p)
            val bg = SunriseSky.backgroundAt(p, 0.5f, 0.5f, SunriseSky.PORTRAIT)
            assertTrue(SunriseSky.contrast(c.content, bg) >= SunriseSky.MIN_CONTRAST, "p=$p")
            assertTrue(SunriseSky.contrast(c.contentMuted, bg) >= SunriseSky.MIN_CONTRAST, "p=$p")
        }
    }
}
