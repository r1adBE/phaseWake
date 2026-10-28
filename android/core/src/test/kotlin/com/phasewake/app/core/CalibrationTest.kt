package com.phasewake.app.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class CalibrationTest {

    /** Eine Nacht: zwei Aufwärm-Epochen (werden verworfen) plus [values]. */
    private fun night(vararg values: Double?): List<Double?> = listOf(9.0, 9.0) + values.toList()

    @Test
    fun tooFewNightsKeepsDefaults() {
        val defaults = DetectorConfig()
        val nights = List(2) { night(*Array(40) { 0.003 }) }
        assertSame(defaults, Calibration.calibrate(nights, defaults))
    }

    @Test
    fun emptyNightsDoNotCount() {
        // Zwei Nächte mit Werten, zwei ohne (Uhr nicht getragen): noch keine drei gemessenen Nächte.
        val defaults = DetectorConfig()
        val nights = List(2) { night(*Array(40) { 0.003 }) } + List(2) { night(null, null) }
        assertSame(defaults, Calibration.calibrate(nights, defaults))
    }

    @Test
    fun tooFewValuesKeepsDefaults() {
        val defaults = DetectorConfig()
        val nights = List(3) { night(0.003, null, 0.003) }
        assertSame(defaults, Calibration.calibrate(nights, defaults))
    }

    @Test
    fun usesEightiethPercentile() {
        // 3 Nächte × 10 Werte = 30: 0.0010, 0.0011, … 0.0039. Rang ⌈0.8 · 30⌉ = 24 → 0.0033.
        val all = (0 until 30).map { 0.001 + it * 0.0001 }
        val nights = all.chunked(10).map { night(*it.toTypedArray()) }
        val config = Calibration.calibrate(nights)
        assertEquals(all[23], config.lightThreshold)
        assertEquals(all[23] * 50.0, config.wakeThreshold)
    }

    @Test
    fun clampsToRange() {
        val quiet = List(3) { night(*Array(20) { 0.0 }) }
        assertEquals(0.001, Calibration.calibrate(quiet).lightThreshold)
        val restless = List(3) { night(*Array(20) { 0.5 }) }
        assertEquals(0.004, Calibration.calibrate(restless).lightThreshold)
    }

    @Test
    fun onlyLastFourteenNightsCount() {
        val old = List(10) { night(*Array(20) { 0.5 }) }
        val recent = List(14) { night(*Array(20) { 0.0 }) }
        assertEquals(0.001, Calibration.calibrate(old + recent).lightThreshold)
    }
}
