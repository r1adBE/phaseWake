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

    /** Flächen echter Texte: schmal bis ganze Breite, eine bis drei Zeilen hoch, überall auf dem Bildschirm. */
    private val areas = buildList {
        for (top in 0..18) for ((left, right) in listOf(0.05f to 0.95f, 0.2f to 0.8f, 0.35f to 0.65f, 0.45f to 0.55f)) {
            for (height in listOf(0.03f, 0.06f, 0.12f)) {
                val t = top / 20f
                add(SunriseSky.Area(left, t, right, (t + height).coerceAtMost(1f)))
            }
        }
    }

    /**
     * Audit N-4 / Gestaltung 5: Über ganze Textflächen (nicht nur Punkte) erreicht die Schrift mit dem Schild dahinter
     * überall ≥ 4.5:1 – geprüft dichter (9 × 9 Punkte) als das Schild rechnet ([SunriseSky.GRID]).
     */
    @Test
    fun plaqueKeepsContrastOverWholeTextAreas() {
        val fine = (0..8).map { it / 8f }
        for (step in 0..50) {
            val p = step / 50f
            for (aspect in listOf(SunriseSky.ASPECT)) for (area in areas) {
                val ink = SunriseSky.ink(p, area, aspect)
                val plaque = SunriseSky.plaque(p, ink, area, aspect)
                assertTrue(plaque.alpha in SunriseSky.MIN_PLAQUE..1f)
                for (fx in fine) for (fy in fine) {
                    val x = area.left + (area.right - area.left) * fx
                    val y = area.top + (area.bottom - area.top) * fy
                    val under = SunriseSky.underPlaque(p, plaque, x, y, aspect)
                    val content = SunriseSky.contrast(ink.content, under)
                    val muted = SunriseSky.contrast(ink.muted, under)
                    assertTrue(content >= SunriseSky.MIN_CONTRAST, "p=$p $area aspect=$aspect: $content")
                    assertTrue(muted >= SunriseSky.MIN_CONTRAST, "leise p=$p $area aspect=$aspect: $muted")
                }
            }
        }
    }

    /** Wo die Schrift allein reicht, bleibt das Schild bei seiner kleinsten Deckkraft (leicht, nicht deckend). */
    @Test
    fun plaqueStaysLightWhereInkSuffices() {
        val area = SunriseSky.Area(0.45f, 0.1f, 0.55f, 0.12f)
        val plaque = SunriseSky.plaque(0f, SunriseSky.ink(0f, area), area)
        assertTrue(plaque.alpha == SunriseSky.MIN_PLAQUE, "$plaque")
    }

    /** Das Schild ist der Gegenpol der Schrift: hinter heller Schrift dunkel, hinter dunkler hell. */
    @Test
    fun plaqueIsOppositeOfInk() {
        val area = SunriseSky.Area(0.2f, 0.4f, 0.8f, 0.5f)
        for (p in listOf(0f, 1f)) {
            val ink = SunriseSky.ink(p, area)
            val plaque = SunriseSky.plaque(p, ink, area)
            assertTrue(SunriseSky.contrast(ink.content, plaque.color) > 10f, "p=$p")
        }
    }
}
