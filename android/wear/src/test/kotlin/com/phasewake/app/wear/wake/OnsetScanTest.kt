package com.phasewake.app.wear.wake

import com.phasewake.app.core.NoiseFloor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Fix KN-3: Die Einschlaf-Messung wertet Epoche für Epoche aus ([OnsetRules.scan]); ihr Grundrauschen (Vorwissen im
 * Weckfenster) stammt nur aus den Epochen bis zum erkannten Einschlafen, wie `RecorderOnset.measure` auf der Apple Watch.
 */
class OnsetScanTest {
    private val threshold = 0.002

    @Test
    fun floorStopsAtTheDetectingEpochEvenInOneBundle() {
        // 30 × 0.01, dann 30 sehr ruhige (0.001) im selben Bündel: erkannt in Epoche 29, Grundrauschen 0.01 (über alle 0.001).
        val motions = List(30) { 0.01 } + List(30) { 0.001 }
        val (index, floor) = OnsetRules.scan(motions, List(60) { true }, 0, threshold, null, continuous = true, floor = null)
        assertEquals(0, index)
        assertEquals(0.01, floor!!, 1e-12)
    }

    @Test
    fun withoutOnsetTheFloorCoversAllMeasuredEpochs() {
        val motions = List(10) { 0.5 } + List(10) { 0.03 }
        val measured = List(20) { it % 2 == 0 }
        val (index, floor) = OnsetRules.scan(motions, measured, 0, threshold, null, continuous = false, floor = null)
        assertNull(index)
        // Gemessen: 5 × 0.5 und 5 × 0.03 → 20. Perzentil 0.03.
        assertEquals(0.03, floor!!, 1e-12)
        // Keine neuen Epochen: das bisherige bleibt.
        assertEquals(null to 0.02, OnsetRules.scan(motions, measured, motions.size, threshold, null, true, 0.02))
    }

    @Test
    fun learnedFloorWhileTooFewValues() {
        val (index, floor) = OnsetRules.scan(listOf(0.5, 0.5), listOf(true, true), 0, threshold, 0.05, continuous = true, floor = null)
        assertNull(index)
        assertEquals(NoiseFloor.MAX_FLOOR, floor!!, 1e-12)
    }

    @Test
    fun laterEpochsInTheBundleDoNotChangeTheDecision() {
        // Erst 3 × 0.03 (Schwelle 0.002 bzw. ab Epoche 3 2.5 × 0.0015: unruhig), dann 30 × 0.0015, danach 200 × 0.2 im
        // selben Bündel. Epoche für Epoche: erkannt in Epoche 32, Beginn 3. Mit dem Grundrauschen des ganzen Bündels (0.04
        // → Schwelle 0.1) begänne die Strecke schon bei 0: Das Bündel änderte die Einschlafzeit.
        val motions = List(3) { 0.03 } + List(30) { 0.0015 } + List(200) { 0.2 }
        val (index, _) = OnsetRules.scan(motions, List(motions.size) { true }, 0, threshold, null, continuous = true, floor = null)
        assertEquals(3, index)
    }
}
