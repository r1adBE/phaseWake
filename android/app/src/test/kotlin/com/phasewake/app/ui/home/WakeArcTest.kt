package com.phasewake.app.ui.home

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Lage auf dem Countdown-Bogen: Anfang, Beginn des Weckfensters, Zielzeit; «Genau» ohne Fensteranteil. */
class WakeArcTest {

    private val target = Instant.parse("2026-11-16T06:00:00Z")
    private val start = target.minusSeconds(20 * 60L)

    @Test
    fun windowKeepsItsShareAndTimeRunsToTheTarget() {
        assertEquals(0f, arcPosition(target.minusSeconds(20 * 3600L), start, target))
        assertEquals(1f - WINDOW_SHARE, arcPosition(start, start, target), 0.001f)
        assertEquals(1f - WINDOW_SHARE / 2, arcPosition(start.plusSeconds(10 * 60L), start, target), 0.001f)
        assertEquals(1f, arcPosition(target, start, target))
        val earlier = arcPosition(target.minusSeconds(6 * 3600L), start, target)
        val later = arcPosition(target.minusSeconds(3 * 3600L), start, target)
        assertTrue(earlier < later && later < 1f - WINDOW_SHARE)
    }

    @Test
    fun exactHasNoWindow() {
        assertEquals(0.5f, arcPosition(target.minusSeconds(6 * 3600L), target, target), 0.001f)
        assertEquals(1f, arcPosition(target, target, target))
    }
}
