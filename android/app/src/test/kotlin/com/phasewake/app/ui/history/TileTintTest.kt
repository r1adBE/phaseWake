package com.phasewake.app.ui.history

import androidx.compose.ui.graphics.Color
import com.phasewake.app.ui.components.SunriseSky
import kotlin.test.Test
import kotlin.test.assertTrue

/** Getönte Kennzahl-Kacheln: Text und Nebentext bleiben auf der getönten Oberkante ≥ 4.5:1, hell und dunkel. */
class TileTintTest {

    private class Mode(val dark: Boolean, val containers: List<Color>, val inks: List<Color>, val accents: List<Color>)

    @Test
    fun textReadableOnTintedTiles() {
        val modes = listOf(
            Mode(
                true, listOf(Color(0xFF161B3A), Color(0xFF1C2245)), listOf(Color(0xFFFBF7F0), Color(0xFFC9C3E6)),
                listOf(Color(0xFFFF8F85), Color(0xFF6FF2E6), Color(0xFFB9AEFF), Color(0xFF6F5CF0)),
            ),
            Mode(
                false, listOf(Color.White, Color(0xFFF3F0FA)), listOf(Color(0xFF1A1830), Color(0xFF5C5876)),
                listOf(Color(0xFFCF4F3A), Color(0xFF0E8C9E), Color(0xFF7B6CF6), Color(0xFF2F2A85), Color(0xFF3B3486)),
            ),
        )
        for (mode in modes) for (container in mode.containers) for (accent in mode.accents) for (ink in mode.inks) {
            val ratio = SunriseSky.contrast(ink, tileTop(accent, container, mode.dark))
            assertTrue(ratio >= SunriseSky.MIN_CONTRAST, "dark=${mode.dark} $accent: $ratio:1")
        }
    }
}
