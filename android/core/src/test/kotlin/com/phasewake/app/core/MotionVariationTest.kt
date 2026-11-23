package com.phasewake.app.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** «Auf und Ab» (Variante B); dieselben Fälle prüft die Swift-Umsetzung (`MotionVariationTests`). */
class MotionVariationTest {

    private val floor = 0.02

    @Test
    fun plateauDoesNotVary() {
        val previous = listOf(0.070, 0.074, 0.071, 0.076, 0.072, 0.075, 0.072, 0.071, 0.072, 0.075)
        assertFalse(MotionVariation.varies(previous, 0.072, floor))
    }

    @Test
    fun jumpVaries() {
        assertTrue(MotionVariation.varies(List(10) { 0.02 }, 0.07, floor))
    }

    @Test
    fun dropVaries() {
        assertTrue(MotionVariation.varies(List(10) { 0.07 }, 0.02, floor))
    }

    @Test
    fun withoutPreviousMotionCounts() {
        assertTrue(MotionVariation.varies(emptyList(), 0.07, floor))
        assertTrue(MotionVariation.varies(listOf(null, null), 0.07, floor))
    }

    @Test
    fun evenCountMedianIsMeanOfTheMiddleTwo() {
        // Sortiert 0, 0.03, 0.07, 0.10: Median 0.05; Vorepoche 0.07. Mit 0.03 oder 0.07 als Median kippte je ein Fall.
        val previous = listOf(0.0, 0.10, 0.03, 0.07)
        assertTrue(MotionVariation.varies(previous, 0.08, 0.025))
        assertFalse(MotionVariation.varies(previous, 0.06, 0.025))
    }

    @Test
    fun previousEpochAloneVaries() {
        // Median der 10 vorherigen 0.07 wie die Epoche, die Vorepoche 0.02 weicht ab.
        assertTrue(MotionVariation.varies(List(9) { 0.07 } + listOf(0.02), 0.07, floor))
    }

    @Test
    fun emptyPreviousEpochIsNotCompared() {
        assertFalse(MotionVariation.varies(List(10) { 0.07 } + listOf(null), 0.07, floor))
    }

    @Test
    fun medianUsesOnlyTheLastTenMotions() {
        // Letzte 10: 4 × 0.02 und 6 × 0.07, Median 0.07; über alle 16 wäre er 0.02.
        val previous = List(10) { 0.02 } + List(6) { 0.07 }
        assertFalse(MotionVariation.varies(previous, 0.07, floor))
    }
}
