package com.phasewake.app.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class SunriseTest {
    private val start = Instant.parse("2026-10-28T05:30:00Z")
    private val end = Instant.parse("2026-10-28T06:00:00Z")

    @Test
    fun progressRunsFromStartToEnd() {
        assertEquals(0f, Sunrise.progress(start, end, start.minusSeconds(60)))
        assertEquals(0f, Sunrise.progress(start, end, start))
        assertEquals(0.5f, Sunrise.progress(start, end, start.plusSeconds(900)))
        assertEquals(1f, Sunrise.progress(start, end, end))
        assertEquals(1f, Sunrise.progress(start, end, end.plusSeconds(60)))
        assertEquals(1f, Sunrise.progress(end, start, start))
    }

    @Test
    fun windowLightLastsAtMostThirtyMinutes() {
        val target = Instant.parse("2026-11-19T06:00:00Z")
        fun light(window: Long) = Sunrise.lightStart(target.minusSeconds(window * 60), target)
        assertEquals(30, Sunrise.MAX_WINDOW_LIGHT_MINUTES)
        // Bis 30 Min mit dem Fenster, länger (Android 45, 60) erst 30 Min vor der Zielzeit.
        assertEquals(target.minusSeconds(10 * 60), light(10))
        assertEquals(target.minusSeconds(30 * 60), light(30))
        assertEquals(target.minusSeconds(30 * 60), light(31))
        assertEquals(target.minusSeconds(30 * 60), light(45))
        assertEquals(target.minusSeconds(30 * 60), light(60))
        // Weckt die Uhr vor dem Licht (z. B. 50 Min vorher), steht es noch bei 0 und wird nachgeholt.
        assertEquals(0f, Sunrise.progress(light(60), target, target.minusSeconds(50 * 60)))
    }

    @Test
    fun brightnessStartsDimAndEndsFull() {
        assertEquals(0.01f, Sunrise.brightness(0f))
        assertEquals(1f, Sunrise.brightness(1f))
        assertEquals(0.2575, Sunrise.brightness(0.5f).toDouble(), 1e-6)
        assertEquals(1f, Sunrise.brightness(2f))
    }
}
