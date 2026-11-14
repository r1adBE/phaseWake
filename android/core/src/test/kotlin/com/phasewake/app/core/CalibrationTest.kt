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
        // 3 Nächte × (2 × 0.0 + 8 Werte) = 30; je Nacht Grundrauschen 0.0 (Rang 2 von 10), abgezogen bleibt alles gleich.
        // Werte 0.0005, 0.00055, … : sortiert 6 × 0.0, dann diese; Rang ⌈0.8 · 30⌉ = 24 → der 18. Wert (0.00135).
        val all = (0 until 24).map { 0.0005 + it * 0.00005 }
        val nights = all.chunked(8).map { night(0.0, 0.0, *it.toTypedArray()) }
        val config = Calibration.calibrate(nights)
        assertEquals(all[17], config.lightThreshold)
        assertEquals(all[17] * 50.0, config.wakeThreshold)
    }

    @Test
    fun noiseFloorOfEachNightIsSubtracted() {
        // Audit c3b8b01 E-2: Uhr mit Rauschen 0.02 (Werte 0.02000 … 0.02095). Grundrauschen je Nacht 0.02015 (Rang 4 von
        // 20) abgezogen: 80. Perzentil 0.0006 → untere Grenze 0.001, Wach 0.05. Ohne Abzug: 0.004 und 0.2.
        val nights = List(3) { night(*Array(20) { j -> 0.02 + j * 0.00005 }) }
        val config = Calibration.calibrate(nights)
        assertEquals(0.001, config.lightThreshold, 1e-12)
        assertEquals(0.05, config.wakeThreshold, 1e-12)
    }

    @Test
    fun realNoisyNightsKeepTheDefaultWakeThreshold() {
        // Gemeinsame Fälle mit Swift (shared/testdata/calibration-real.vectors): 4 echte Nächte einer Galaxy Watch.
        val lines = NightFixtures.directory().resolve("calibration-real.vectors").readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() && !it.startsWith("#") }
        val expect = lines.single { it.startsWith("@expect ") }.removePrefix("@expect ").split(" ").map(String::toDouble)
        val nights = lines.filterNot { it.startsWith("@") }.map { line -> line.split(",").map { it.toDoubleOrNull() } }
        assertEquals(4, nights.size)
        val config = Calibration.calibrate(nights)
        assertEquals(expect[0], config.lightThreshold, 1e-12)
        assertEquals(expect[1], config.wakeThreshold, 1e-12)
    }

    @Test
    fun clampsToRange() {
        val quiet = List(3) { night(*Array(20) { 0.0 }) }
        assertEquals(0.001, Calibration.calibrate(quiet).lightThreshold)
        val restless = List(3) { night(*Array(20) { 0.5 }) }
        assertEquals(0.004, Calibration.calibrate(restless).lightThreshold)
        // Wach höchstens beim Startwert 0.1 (E-2, r1AD 14.11.2026), nicht 0.004 × 50 = 0.2; ruhig 0.001 × 50 = 0.05.
        assertEquals(0.1, Calibration.calibrate(restless).wakeThreshold)
        assertEquals(0.05, Calibration.calibrate(quiet).wakeThreshold)
    }

    @Test
    fun onlyLastFourteenNightsCount() {
        val old = List(10) { night(*Array(20) { 0.5 }) }
        val recent = List(14) { night(*Array(20) { 0.0 }) }
        assertEquals(0.001, Calibration.calibrate(old + recent).lightThreshold)
    }
}
