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
    fun windowLightOnlyLowersToTheLearned() {
        val learned = DetectorConfig(personalFloor = 0.0197)
        // Andere Armlage (Nacht 17.11.2026): Fenster 0.02955 -> 2.5 × gelernt statt 2.5 × Fenster.
        assertEquals(0.04925, NoiseFloor.windowLight(learned, 0.02955), 1e-12)
        assertEquals(0.073875, NoiseFloor.light(learned, 0.02955), 1e-12)
        // Sehr unruhige Armlage: mindestens 1.5 × Fenster.
        assertEquals(0.06, NoiseFloor.windowLight(learned, 0.04), 1e-12)
        // Fenster ruhiger als gelernt: wie bisher.
        assertEquals(0.025, NoiseFloor.windowLight(learned, 0.01), 1e-12)
        // Ohne gelerntes, ohne Grundrauschen und mit unplausiblem gelerntem (< 0.25 × Fenster): wie bisher.
        assertEquals(0.074, NoiseFloor.windowLight(DetectorConfig(), 0.0296), 1e-12)
        assertEquals(0.002, NoiseFloor.windowLight(learned, null))
        assertEquals(0.04975, NoiseFloor.windowLight(DetectorConfig(personalFloor = 0.0045), 0.0199), 1e-12)
        // Genau 0.25 × Fenster gilt als plausibel, knapp darunter nicht.
        assertEquals(0.03, NoiseFloor.windowLight(DetectorConfig(personalFloor = 0.005), 0.02), 1e-12)
        assertEquals(0.05, NoiseFloor.windowLight(DetectorConfig(personalFloor = 0.0049), 0.02), 1e-12)
        // Startwert bleibt Untergrenze, ebenso eine kalibrierte Schwelle.
        assertEquals(0.002, NoiseFloor.windowLight(DetectorConfig(personalFloor = 0.0005), 0.0005))
        val calibrated = DetectorConfig(lightThreshold = 0.06, personalFloor = 0.0197)
        assertEquals(0.06, NoiseFloor.windowLight(calibrated, 0.02955), 1e-12)
    }

    @Test
    fun summaryForDiagnosis() {
        val config = DetectorConfig()
        assertEquals(
            "floor=0.0200 light=0.0500 wake=0.1000",
            NoiseFloor.summary(config, listOf(0.02, null, 0.02, 0.02, 0.4)),
        )
        assertEquals("floor=- light=0.0020 wake=0.1000", NoiseFloor.summary(config, listOf(0.02, null)))
        // Wirksame Leicht-Schwelle im Fenster: Fenster 0.024 (1.5 × gelernt 0.016), Leicht 2.5 × gelernt.
        assertEquals(
            "floor=0.0240 light=0.0400 wake=0.1200",
            NoiseFloor.summary(DetectorConfig(personalFloor = 0.016), List(5) { 0.03 }),
        )
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
        assertEquals(
            "floor=0.0200 light=0.0500 wake=0.1000",
            NoiseFloor.summary(DetectorConfig(personalFloor = 0.02), listOf(0.3)),
        )
    }
}
