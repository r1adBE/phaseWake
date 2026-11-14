package com.phasewake.app.core

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
    fun valuesBeforeStartDoNotCount() {
        // Audit c3b8b01 BE-1b: Nicht nur «nichts geschlossen» – der Wert darf auch in keiner Epoche landen.
        val a = EpochAssembler(start)
        assertTrue(a.addHeartRate(start - s, 90.0).isEmpty())
        a.addHeartRate(start + 5 * s, 60.0)
        val closed = a.advance(start + 31 * s, 0)
        assertEquals(1, closed.size)
        assertEquals(60.0, closed[0].heartRate)
    }

    @Test
    fun lateValuesDoNotLandInTheCurrentEpoch() {
        val a = EpochAssembler(start)
        a.addHeartRate(start + 5 * s, 60.0)
        assertEquals(60.0, a.advance(start + 31 * s, 0).single().heartRate)
        // Verspätet für Epoche 0 (schon abgeschlossen): weder Puls noch Bewegung zählen in Epoche 1.
        assertTrue(a.addHeartRate(start + 6 * s, 100.0).isEmpty())
        assertTrue(a.addMotion(start + 29_950_000_000L, 5.0, 0.0, 9.81).isEmpty())
        a.addHeartRate(start + 40 * s, 62.0)
        a.fillEpoch(1, 0.0)
        val epoch1 = a.advance(start + 61 * s, 0).single()
        assertEquals(62.0, epoch1.heartRate)
        assertEquals(0.0, epoch1.motion!!, 1e-12)
    }

    @Test
    fun valuesMoreThanTwentyFourHoursLaterAreATimeJump() {
        // Epoche 2880 (24 Std.) gilt noch, 2881 ist ein Zeitsprung der Sensoruhr (Abschnitt 7).
        assertEquals(2880L, EpochAssembler.MAX_EPOCHS)
        val jump = EpochAssembler(start)
        assertTrue(jump.addHeartRate(start + 2881L * 30 * s, 60.0).isEmpty())
        assertEquals(0L, jump.currentIndex)
        val last = EpochAssembler(start)
        assertEquals(2880, last.addHeartRate(start + 2880L * 30 * s, 60.0).size)
        assertEquals(2880L, last.currentIndex)
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
