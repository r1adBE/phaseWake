package com.phasewake.app.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SnoozeAndOnsetTest {

    @Test
    fun snoozeThreeTimes() {
        assertTrue(Snooze.canSnooze(0))
        assertTrue(Snooze.canSnooze(2))
        assertFalse(Snooze.canSnooze(3))
        assertEquals(Instant.parse("2026-10-28T06:05:00Z"), Snooze.nextRing(Instant.parse("2026-10-28T06:00:00Z")))
    }

    @Test
    fun onsetIsStartOfFirstStillRun() {
        val restless = List(10) { 0.05 }
        val shortRest = List(20) { 0.0 }
        val still = List(30) { 0.0 }
        assertEquals(31, SleepOnset.find(restless + shortRest + listOf<Double?>(null) + still))
    }

    @Test
    fun noOnsetWhileRestless() {
        assertNull(SleepOnset.find(List(29) { 0.0 }))
        assertNull(SleepOnset.find(List(60) { 0.002 })) // genau auf der Schwelle ist nicht ruhig
    }

    @Test
    fun noisyWatchFallsAsleepWithThresholdFromNoiseFloor() {
        // Galaxy Watch 07.11.2026: unruhig 0.09–0.83, still liegend 0.019–0.029 m/s² (nach Abzug von 0.05).
        val restless = List(30) { listOf(0.2358, 0.8285, 0.0933, 0.1414, 0.3556)[it % 5] }
        val quiet = List(30) { listOf(0.0194, 0.0203, 0.0217, 0.0287, 0.0233, 0.0280)[it % 6] }
        val motions: List<Double?> = restless + quiet
        // Mit der festen Leicht-Schwelle 0.002 nie ruhig.
        assertNull(SleepOnset.find(motions, DetectorConfig.DEFAULT_LIGHT_THRESHOLD))
        // Grundrauschen 0.0217 (20. Perzentil, Rang 12 von 60), wirksame Schwelle 0.05425: eingeschlafen zu Beginn der Ruhe.
        val threshold = NoiseFloor.light(DetectorConfig.DEFAULT_LIGHT_THRESHOLD, NoiseFloor.floor(motions))
        assertEquals(0.05425, threshold, 1e-12)
        assertEquals(30, SleepOnset.find(motions, threshold))
        // Eine Epoche früher (Strecke erst 29 Epochen) noch nicht.
        val early = motions.dropLast(1)
        assertNull(SleepOnset.find(early, NoiseFloor.light(DetectorConfig.DEFAULT_LIGHT_THRESHOLD, NoiseFloor.floor(early))))
    }

    @Test
    fun restlessStartIsNotQuietWithCappedFloor() {
        // 15 Min mässige Unruhe (0.11–0.18, Werte 07.11.2026): 20. Perzentil 0.1121 gäbe Schwelle 0.28 (alles «ruhig»).
        val moderate: List<Double?> = List(30) { listOf(0.1121, 0.1414, 0.1250, 0.1830)[it % 4] }
        assertEquals(NoiseFloor.MAX_FLOOR, NoiseFloor.floor(moderate))
        val threshold = NoiseFloor.light(DetectorConfig.DEFAULT_LIGHT_THRESHOLD, NoiseFloor.floor(moderate))
        assertEquals(0.1, threshold, 1e-12)
        assertNull(SleepOnset.find(moderate, threshold))
    }
}
