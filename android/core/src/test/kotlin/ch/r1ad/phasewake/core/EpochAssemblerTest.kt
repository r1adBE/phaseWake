package ch.r1ad.phasewake.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpochAssemblerTest {

    private val s = 1_000_000_000L
    private val start = 500 * s

    /** Füllt eine Epoche [epoch] mit 25-Hz-Werten, die bei jedem Schritt um [jump] springen. */
    private fun EpochAssembler.fillEpoch(epoch: Int, jump: Double): List<Epoch> {
        val closed = mutableListOf<Epoch>()
        for (i in 0 until 750) {
            val t = start + epoch * 30 * s + i * 40_000_000L
            closed += addMotion(t, if (i % 2 == 0) 0.0 else jump, 0.0, 9.81)
        }
        return closed
    }

    @Test
    fun closesEpochWhenNextBegins() {
        val a = EpochAssembler(start)
        assertTrue(a.fillEpoch(0, 0.25).isEmpty())
        a.addHeartRate(start + 10 * s, 50.0)
        a.addHeartRate(start + 20 * s, 54.0)
        val closed = a.addMotion(start + 30 * s, 0.0, 0.0, 9.81)
        assertEquals(1, closed.size)
        assertEquals(0.20, closed[0].motion!!, 1e-12)
        assertEquals(52.0, closed[0].heartRate)
        assertEquals(1, a.currentIndex)
    }

    @Test
    fun gapProducesEmptyEpochs() {
        val a = EpochAssembler(start)
        a.fillEpoch(0, 0.0)
        val closed = a.fillEpoch(3, 0.0)
        assertEquals(3, closed.size)
        assertEquals(0.0, closed[0].motion)
        assertNull(closed[1].motion)
        assertNull(closed[2].heartRate)
    }

    @Test
    fun samplesBeforeStartAndLateSamplesAreIgnored() {
        val a = EpochAssembler(start)
        assertTrue(a.addMotion(start - 1, 0.0, 0.0, 9.81).isEmpty())
        a.addMotion(start + 31 * s, 0.0, 0.0, 9.81)
        assertTrue(a.addHeartRate(start + 5 * s, 60.0).isEmpty()) // gehört zu Epoche 0, schon zu
        assertEquals(1, a.currentIndex)
    }

    @Test
    fun advanceClosesWithGrace() {
        val a = EpochAssembler(start)
        a.fillEpoch(0, 0.0)
        assertTrue(a.advance(start + 35 * s, 10 * s).isEmpty()) // 25 s: Epoche 0 läuft noch
        assertEquals(1, a.advance(start + 41 * s, 10 * s).size)
        assertTrue(a.advance(start - 100 * s, 0).isEmpty())
    }
}
