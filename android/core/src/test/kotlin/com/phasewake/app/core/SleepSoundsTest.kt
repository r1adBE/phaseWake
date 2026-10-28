package com.phasewake.app.core

import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SleepSoundsTest {
    private val rate = 48_000

    private fun render(sound: SleepSound, seconds: Int, seed: Long = 7L): FloatArray {
        val out = FloatArray(rate * seconds)
        NoiseGenerator(sound, rate, seed).fill(out)
        return out
    }

    private fun maxStep(samples: FloatArray): Float {
        var max = 0f
        for (i in 1 until samples.size) max = maxOf(max, abs(samples[i] - samples[i - 1]))
        return max
    }

    @Test
    fun staysWithinPeakWithoutDcAndNotSilent() {
        for (sound in SleepSound.entries) {
            val samples = render(sound, 20)
            val peak = samples.maxOf { abs(it) }
            assertTrue(peak <= SleepSounds.PEAK, "$sound Spitze $peak")
            // Nach dem Einschwingen (1 s) praktisch ohne Gleichanteil.
            val mean = samples.drop(rate).average()
            assertTrue(abs(mean) < 0.005, "$sound Gleichanteil $mean")
            val rms = kotlin.math.sqrt(samples.drop(rate).sumOf { it.toDouble() * it } / (samples.size - rate))
            assertTrue(rms > 0.01, "$sound zu leise ($rms)")
        }
    }

    @Test
    fun deterministicAndSeamlessAcrossBuffers() {
        for (sound in SleepSound.entries) {
            val whole = render(sound, 1, seed = 42L)
            val split = FloatArray(rate)
            val gen = NoiseGenerator(sound, rate, 42L)
            gen.fill(split, 0, 1000)
            gen.fill(split, 1000, rate - 1000)
            assertTrue(whole.contentEquals(split), "$sound nicht nahtlos")
        }
    }

    @Test
    fun darkNoisesHaveNoClicks() {
        // Braun und Meer sind tieffrequent: Sprünge von Wert zu Wert bleiben klein.
        val white = maxStep(render(SleepSound.WHITE, 10))
        assertTrue(maxStep(render(SleepSound.BROWN, 10)) < white / 4)
        assertTrue(maxStep(render(SleepSound.OCEAN, 10)) < white / 4)
    }

    @Test
    fun fadesAndStop() {
        assertEquals(0f, SleepSounds.gain(0, null))
        assertTrue(abs(SleepSounds.gain(1_500, null) - 0.5f) < 1e-6f)
        assertEquals(1f, SleepSounds.gain(10_000, null))
        assertEquals(1f, SleepSounds.gain(10_000, 60_000))
        assertTrue(abs(SleepSounds.gain(60_000, 15_000) - 0.5f) < 1e-6f)
        assertEquals(0f, SleepSounds.gain(60_000, 0))
        assertEquals(0f, SleepSounds.gain(60_000, -5))

        val start = Instant.parse("2026-10-27T21:00:00Z")
        assertEquals(start.plusSeconds(1800), SleepSounds.stopAt(start, 30, null))
        // Das Weckfenster beginnt früher als der Timer: dann dort.
        assertEquals(start.plusSeconds(600), SleepSounds.stopAt(start, 30, start.plusSeconds(600)))
        assertEquals(start.plusSeconds(600), SleepSounds.stopAt(start, null, start.plusSeconds(600)))
        assertNull(SleepSounds.stopAt(start, null, null))
        // Läuft das Weckfenster schon, enden die Klänge sofort.
        assertEquals(start, SleepSounds.stopAt(start, 15, start.minusSeconds(60)))
    }
}
