package com.phasewake.app.ui.components

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Seiten im Stil der Startseite (Verlauf, Einstellungen, Editor, Einrichtung, Diagnose): auf dem Himmel jeder Stimmung und
 * Höhe bleibt auf den Karten der Seiten ([Night.Panel]) Schrift ≥ 4.5:1, Phasenfarben und Ränder von Bedienelementen
 * ≥ 3:1 (WCAG 1.4.11); ebenso auf der getönten Oberkante der Kennzahl-Kacheln und im hellen Himmel-Kopf.
 */
class SkyPageContrastTest {

    private val moods = buildList {
        for (d in 0..10) for (w in 0..10) for (n in 0..2) add(SkyMood(dusk = d / 10f, dawn = w / 10f, night = n / 2f))
    }

    /** Alle Hintergründe: Himmel jeder Stimmung, Höhe 0–1 in 5-%-Schritten. */
    private val skies = moods.flatMap { mood -> (0..20).map { mood.skyAt(it / 20f) } }

    private fun panelOn(sky: Color): Color = mixColor(sky, Night.Panel.copy(alpha = 1f), Night.Panel.alpha)

    /** Schrift auf den Karten (dunkles Design): Text, Nebentext, Hauptfarbe, Fehler, Phasenfarben als Text. */
    private val textInks = listOf(
        Night.Text, Night.Muted, Color(0xFFB9AEFF), Color(0xFFFFB4AB), Color(0xFFFF8F85), Color(0xFF6FF2E6),
    )

    /** Nicht-Text: tiefe Phase (dunkelste Phasenfarbe) und Rand der Bedienelemente (outline). */
    private val shapeInks = listOf(Color(0xFF6F5CF0), Color(0xFF726EA4))

    @Test
    fun textReadableOnPanels() {
        for (sky in skies) {
            val panel = panelOn(sky)
            for (ink in textInks) {
                val ratio = SunriseSky.contrast(ink, panel)
                assertTrue(ratio >= SunriseSky.MIN_CONTRAST, "$ink auf $panel: $ratio:1")
            }
            // Leisere Stufe: Muted mit 80 %.
            val soft = mixColor(panel, Night.Muted, 0.8f)
            assertTrue(SunriseSky.contrast(soft, panel) >= SunriseSky.MIN_CONTRAST, "leise auf $panel")
        }
    }

    @Test
    fun shapesVisibleOnPanels() {
        for (sky in skies) for (ink in shapeInks) {
            val ratio = SunriseSky.contrast(ink, panelOn(sky))
            assertTrue(ratio >= NON_TEXT, "$ink: $ratio:1")
        }
    }

    @Test
    fun textReadableOnTintedTiles() {
        val accents = listOf(Color(0xFFB9AEFF), Color(0xFFFF8F85), Color(0xFF6FF2E6), Color(0xFF6F5CF0))
        for (sky in skies) for (accent in accents) {
            val top = mixColor(panelOn(sky), accent, com.phasewake.app.ui.history.TILE_TINT_DARK)
            for (ink in listOf(Night.Text, Night.Muted)) {
                val ratio = SunriseSky.contrast(ink, top)
                assertTrue(ratio >= SunriseSky.MIN_CONTRAST, "$accent/$ink: $ratio:1")
            }
        }
    }

    /** Heller Himmel-Kopf: Titel, Schrittzahl und Textknopf (Lavendel) auf dem Himmel, Schrift im gefüllten Knopf. */
    @Test
    fun headerReadable() {
        for (sky in skies) for (ink in listOf(Night.Text, Night.Muted, Night.Wave)) {
            val ratio = SunriseSky.contrast(ink, sky)
            assertTrue(ratio >= SunriseSky.MIN_CONTRAST, "Kopf $ink: $ratio:1")
        }
        assertTrue(SunriseSky.contrast(Night.Top, Night.Wave) >= SunriseSky.MIN_CONTRAST, "Knopf im Kopf")
    }

    private companion object {
        const val NON_TEXT = 3f
    }
}
