package com.phasewake.app.core

import java.time.Instant
import kotlin.math.abs
import kotlin.test.assertFailsWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SleepSoundsTest {
    private val rate = 48_000
    private val generated = SleepSound.entries.filter { !it.recorded }

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
        for (sound in generated) {
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
        for (sound in generated) {
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
        // Braun und Ventilator sind tieffrequent: Sprünge von Wert zu Wert bleiben klein.
        val white = maxStep(render(SleepSound.WHITE, 10))
        assertTrue(maxStep(render(SleepSound.BROWN, 10)) < white / 4)
        assertTrue(maxStep(render(SleepSound.FAN, 10)) < white / 4)
    }

    @Test
    fun binauralIsStereoAndSoft() {
        val gen = NoiseGenerator(SleepSound.BINAURAL, rate, 3L)
        assertEquals(2, gen.channels)
        assertEquals(1, NoiseGenerator(SleepSound.PINK, rate).channels)
        val out = FloatArray(rate * 2 * 4)
        gen.fill(out)
        val left = out.filterIndexed { i, _ -> i % 2 == 0 }
        val right = out.filterIndexed { i, _ -> i % 2 == 1 }
        fun rms(v: List<Float>) = kotlin.math.sqrt(v.sumOf { it.toDouble() * it } / v.size)
        // Je Ohr etwa −30 dBFS; die Kanäle unterscheiden sich (200 Hz gegen 202 Hz).
        for (channel in listOf(left, right)) assertTrue(rms(channel) in 0.025..0.045, "RMS ${rms(channel)}")
        assertTrue(left.zip(right).any { (l, r) -> abs(l - r) > 0.01f })
    }

    @Test
    fun soundsAndStoredNames() {
        // Ältere gespeicherte Werte bleiben gültig; Aufnahmen werden nicht erzeugt.
        for (name in listOf("WHITE", "PINK", "BROWN", "RAIN", "OCEAN")) assertTrue(SleepSound.entries.any { it.name == name }, name)
        assertEquals(listOf(SleepSound.RAIN, SleepSound.OCEAN, SleepSound.FOREST, SleepSound.FIRE, SleepSound.BROOK), SleepSound.entries.filter { it.recorded })
        assertTrue(runCatching { NoiseGenerator(SleepSound.RAIN) }.isFailure)
    }

    @Test
    fun recordingsPlayLouderWithoutClipping() {
        // Aufnahmen +6 dB (Faktor ≈ 2), erzeugte Klänge unverändert.
        for (sound in SleepSound.entries) {
            assertEquals(if (sound.recorded) 6.0 else 0.0, sound.gainDb, sound.name)
            assertEquals(if (sound.recorded) 1.9952623f else 1f, sound.gain, sound.name)
        }
        assertEquals(0.4f, SleepSounds.amplify(0.2f, 2f))
        assertEquals(-0.4f, SleepSounds.amplify(-0.2f, 2f))
        // Laute Stellen werden begrenzt, nie über −0,5 dBFS.
        assertEquals(SleepSounds.CEILING, SleepSounds.amplify(0.9f, SleepSound.RAIN.gain))
        assertEquals(-SleepSounds.CEILING, SleepSounds.amplify(-1f, SleepSound.RAIN.gain))
        assertEquals(0.3f, SleepSounds.amplify(0.3f, SleepSound.PINK.gain))
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

    @Test
    fun recordingsAreNotGeneratedAndSeedZoneIsValid() {
        for (sound in SleepSound.entries.filter { it.recorded }) {
            assertFailsWith<IllegalArgumentException> { NoiseGenerator(sound) }
        }
        // Startwert 0 wäre für xorshift ein Fixpunkt (nur Nullen); er wird ersetzt.
        val samples = FloatArray(4_800).also { NoiseGenerator(SleepSound.WHITE, rate, seed = 0L).fill(it) }
        assertTrue(samples.any { it != 0f })
        assertEquals(1, NoiseGenerator(SleepSound.PINK).channels)
        assertEquals(2, NoiseGenerator(SleepSound.BINAURAL).channels)
        assertNull(SleepSounds.stopAt(Instant.EPOCH, null, null))
    }
}
