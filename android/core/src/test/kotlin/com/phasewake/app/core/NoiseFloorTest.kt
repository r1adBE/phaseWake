package com.phasewake.app.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Grundrauschen der Uhr; dieselben Fälle prüft die Swift-Umsetzung (`NoiseFloorTests`). */
class NoiseFloorTest {

    @Test
    fun tooFewValuesHaveNoFloor() {
        assertNull(NoiseFloor.floor(emptyList()))
        // Leere Bewegung zählt nicht: nur 3 Werte.
        assertNull(NoiseFloor.floor(listOf(0.02, null, 0.03, 0.01)))
    }

    @Test
    fun floorIsTwentiethPercentileByRank() {
        // n = 4 und 5: Position 1; n = 6: Position 2; n = 10: Position 2; n = 11: Position 3.
        assertEquals(0.01, NoiseFloor.floor(listOf(0.04, 0.01, 0.03, 0.02)))
        assertEquals(0.01, NoiseFloor.floor(listOf(0.05, 0.04, 0.01, 0.03, 0.02)))
        assertEquals(0.02, NoiseFloor.floor(listOf(0.06, 0.05, 0.04, 0.01, 0.03, 0.02)))
        assertEquals(0.02, NoiseFloor.floor(List(10) { (10 - it) / 100.0 }))
        assertEquals(0.03, NoiseFloor.floor(List(11) { (11 - it) / 100.0 } + listOf(null)))
    }

    @Test
    fun floorIsCappedAfterRestlessStart() {
        // Unruhiger Beginn (Werte 07.11.2026): 20. Perzentil 0.0933, begrenzt auf 0.04 → Leicht 0.1, Wach 0.2.
        val restless = listOf(0.2358, 0.8285, 0.0933, 0.1414, 0.3556)
        assertEquals(NoiseFloor.MAX_FLOOR, NoiseFloor.floor(restless))
        assertEquals(0.1, NoiseFloor.light(DetectorConfig(), NoiseFloor.floor(restless)), 1e-12)
        assertEquals(0.2, NoiseFloor.wake(DetectorConfig(), NoiseFloor.floor(restless)), 1e-12)
        // Genau auf der Grenze und darunter unverändert.
        assertEquals(0.04, NoiseFloor.floor(List(4) { 0.04 }))
        assertEquals(0.0399, NoiseFloor.floor(List(4) { 0.0399 }))
    }

    @Test
    fun thresholdsOnlyRise() {
        val config = DetectorConfig()
        assertEquals(0.002, NoiseFloor.light(config, null))
        assertEquals(0.1, NoiseFloor.wake(config, null))
        // Kleines Rauschen: Startwerte bleiben.
        assertEquals(0.002, NoiseFloor.light(config, 0.0004))
        assertEquals(0.1, NoiseFloor.wake(config, 0.0004))
        // Galaxy Watch 07.11.2026: ruhig rund 0.02 m/s².
        assertEquals(0.05, NoiseFloor.light(config, 0.02), 1e-12)
        assertEquals(0.1, NoiseFloor.wake(config, 0.02), 1e-12)
        assertEquals(0.075, NoiseFloor.light(config, 0.03), 1e-12)
        assertEquals(0.15, NoiseFloor.wake(config, 0.03), 1e-12)
        // Kalibrierte Schwelle über dem Rauschen bleibt.
        assertEquals(0.004, NoiseFloor.light(0.004, 0.001))
    }

    @Test
    fun summaryForDiagnosis() {
        val config = DetectorConfig()
        assertEquals("floor=0.0200 light=0.0500 wake=0.1000", NoiseFloor.summary(config, listOf(0.02, null, 0.02, 0.02, 0.4)))
        assertEquals("floor=- light=0.0020 wake=0.1000", NoiseFloor.summary(config, listOf(0.02, null)))
    }

    @Test
    fun personalFloorOnlyUntilTheMeasurementHasItsOwn() {
        // Lernphase: Das eigene Grundrauschen gilt, solange die Messung weniger als 4 Werte hat, höchstens 0.04.
        assertEquals(0.02, NoiseFloor.floor(listOf(0.03, null, 0.05), personal = 0.02))
        assertEquals(NoiseFloor.MAX_FLOOR, NoiseFloor.floor(emptyList(), personal = 0.09))
        assertNull(NoiseFloor.floor(listOf(0.03), personal = null))
        // Ab 4 Werten gilt nur das eigene der Messung, auch wenn es tiefer liegt.
        assertEquals(0.001, NoiseFloor.floor(listOf(0.001, 0.002, 0.003, 0.004), personal = 0.02))
        // Zusammenfassung mit dem gelernten Wert, solange die Messung zu wenige hat.
        assertEquals("floor=0.0200 light=0.0500 wake=0.1000", NoiseFloor.summary(DetectorConfig(personalFloor = 0.02), listOf(0.3)))
    }
}
