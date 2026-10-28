package ch.r1ad.phasewake.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MotionEnergyAccumulatorTest {

    private val stepNanos = 40_000_000L // 25 Hz

    /** Füllt eine Epoche mit [count] Werten; [value] liefert den Vektor je Index. */
    private fun MotionEnergyAccumulator.fill(count: Int, start: Long = 0L, value: (Int) -> Triple<Double, Double, Double>) {
        for (i in 0 until count) {
            val (x, y, z) = value(i)
            add(start + i * stepNanos, x, y, z)
        }
    }

    @Test
    fun stillWristIsZero() {
        val acc = MotionEnergyAccumulator()
        acc.fill(750) { Triple(0.0, 0.0, 9.81) }
        assertEquals(0.0, acc.closeEpoch())
    }

    @Test
    fun noiseBelowFloorIsIgnored() {
        val acc = MotionEnergyAccumulator()
        acc.fill(750) { i -> Triple(if (i % 2 == 0) 0.0 else 0.04, 0.0, 9.81) }
        assertEquals(0.0, acc.closeEpoch())
    }

    @Test
    fun motionAboveFloorIsAveraged() {
        val acc = MotionEnergyAccumulator()
        // Jeder Schritt springt um 0.25 → 0.20 über dem Rauschen; 749 Differenzen.
        acc.fill(750) { i -> Triple(if (i % 2 == 0) 0.0 else 0.25, 0.0, 9.81) }
        assertEquals(0.20, acc.closeEpoch()!!, 1e-12)
    }

    @Test
    fun tooFewSamplesGiveNull() {
        val acc = MotionEnergyAccumulator()
        acc.fill(300) { Triple(0.0, 0.0, 9.81) } // 299 Differenzen
        assertNull(acc.closeEpoch())
    }

    @Test
    fun fasterSensorIsThrottled() {
        val acc = MotionEnergyAccumulator()
        // 100 Hz für 30 s: übernommen wird nur jeder vierte Wert (40 ms ≥ 35 ms).
        for (i in 0 until 3000) acc.add(i * 10_000_000L, if (i % 8 < 4) 0.0 else 1.0, 0.0, 9.81)
        // Übernommen: Index 0, 4, 8, … → x wechselt bei jedem übernommenen Wert zwischen 0 und 1.
        assertEquals(0.95, acc.closeEpoch()!!, 1e-12)
    }

    @Test
    fun gapDiffIsDiscarded() {
        val acc = MotionEnergyAccumulator()
        acc.fill(400) { Triple(0.0, 0.0, 9.81) }
        // Nach 2 s Lücke ein ganz anderer Wert: keine Bewegung, nur neuer Bezugspunkt.
        acc.fill(400, start = 400 * stepNanos + 2_000_000_000L) { Triple(5.0, 0.0, 9.81) }
        assertEquals(0.0, acc.closeEpoch())
    }

    @Test
    fun epochsAreIndependentButContinuous() {
        val acc = MotionEnergyAccumulator()
        acc.fill(750) { i -> Triple(if (i % 2 == 0) 0.0 else 0.25, 0.0, 9.81) }
        acc.closeEpoch()
        acc.fill(750, start = 750 * stepNanos) { Triple(0.0, 0.0, 9.81) }
        // Nur die erste Differenz der neuen Epoche (letzter Wert 0.25 → 0.0) zählt: 0.20 / 750.
        assertEquals(0.20 / 750, acc.closeEpoch()!!, 1e-12)
    }
}
