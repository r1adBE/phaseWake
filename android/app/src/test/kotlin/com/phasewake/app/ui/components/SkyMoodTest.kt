package com.phasewake.app.ui.components

import androidx.compose.ui.graphics.Color
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Stimmung des Nachthimmels: Abendrot, Morgendämmerung und tiefe Nacht lassen Schrift auf dem Himmel und auf den
 * durchscheinenden Karten (Weiss 6 %) überall ≥ 4.5:1, auch der leiseren Stufe (Muted mit 80 %); im Lichtschein oben
 * rechts Text und Nebentext.
 */
class SkyMoodTest {

    private val zone = ZoneId.of("Europe/Zurich")

    private fun at(hour: Int, minute: Int = 0): Instant = LocalDateTime.of(2026, 11, 16, hour, minute).atZone(zone).toInstant()

    @Test
    fun textStaysReadableInEveryMood() {
        val moods = buildList {
            for (d in 0..10) for (w in 0..10) for (n in 0..2) add(SkyMood(dusk = d / 10f, dawn = w / 10f, night = n / 2f))
        }
        val inks = listOf(Night.Text, Night.Muted)
        for (mood in moods) for (step in 0..20) {
            val sky = mood.skyAt(step / 20f)
            val card = mixColor(sky, Color.White, 0.06f)
            for (bg in listOf(sky, card)) {
                for (ink in inks) {
                    val ratio = SunriseSky.contrast(ink, bg)
                    assertTrue(ratio >= SunriseSky.MIN_CONTRAST, "$mood y=$step: $ratio:1")
                }
                val soft = mixColor(bg, Night.Muted, 0.8f)
                val ratio = SunriseSky.contrast(soft, bg)
                assertTrue(ratio >= SunriseSky.MIN_CONTRAST, "leise $mood y=$step: $ratio:1")
            }
        }
        for (mood in moods) for (ink in inks) {
            val ratio = SunriseSky.contrast(ink, mood.glowCenter())
            assertTrue(ratio >= SunriseSky.MIN_CONTRAST, "Schein $mood: $ratio:1")
        }
    }

    @Test
    fun starsOnlyInTheDeepNight() {
        val tomorrow = at(6, 30).plusSeconds(24 * 3600L)
        assertEquals(1f, SkyMood.at(at(23, 30), zone, tomorrow, tomorrow.plusSeconds(1800)).night)
        assertEquals(0f, SkyMood.at(at(14, 0), zone, tomorrow, tomorrow.plusSeconds(1800)).night)
        // Kurz vor dem Weckfenster weicht die Nacht der Dämmerung.
        assertEquals(0f, SkyMood.at(at(6, 45), zone, at(6, 30), at(7, 0)).night)
    }

    @Test
    fun dawnRisesBeforeTheWindowAndStaysInIt() {
        val start = at(6, 30)
        val target = at(7, 0)
        assertEquals(0f, SkyMood.at(at(2, 0), zone, start, target).dawn)
        val half = SkyMood.at(at(5, 0), zone, start, target).dawn
        assertTrue(half in 0.4f..0.6f, "1½ Std. vorher: $half")
        assertEquals(1f, SkyMood.at(at(6, 45), zone, start, target).dawn)
    }

    @Test
    fun duskInTheEveningAndPlainAtDay() {
        val tomorrow = at(6, 30).plusSeconds(24 * 3600L)
        val evening = SkyMood.at(at(20, 0), zone, tomorrow, tomorrow.plusSeconds(1800))
        assertEquals(1f, evening.dusk)
        assertEquals(0f, evening.dawn)
        val day = SkyMood.at(at(14, 0), zone, tomorrow, tomorrow.plusSeconds(1800))
        assertEquals(SkyMood.Plain, day)
        assertEquals(Color.Transparent, SkyMood.Plain.horizon)
    }
}
