import XCTest
import PhaseWakeCore

/// Grundrauschen der Uhr; dieselben Fälle prüft die Kotlin-Umsetzung (`NoiseFloorTest`).
final class NoiseFloorTests: XCTestCase {

    func testTooFewValuesHaveNoFloor() {
        XCTAssertNil(NoiseFloor.floor([]))
        // Leere Bewegung zählt nicht: nur 3 Werte.
        XCTAssertNil(NoiseFloor.floor([0.02, nil, 0.03, 0.01]))
    }

    func testFloorIsTwentiethPercentileByRank() {
        // n = 4 und 5: Position 1; n = 6: Position 2; n = 10: Position 2; n = 11: Position 3.
        XCTAssertEqual(NoiseFloor.floor([0.04, 0.01, 0.03, 0.02]), 0.01)
        XCTAssertEqual(NoiseFloor.floor([0.05, 0.04, 0.01, 0.03, 0.02]), 0.01)
        XCTAssertEqual(NoiseFloor.floor([0.06, 0.05, 0.04, 0.01, 0.03, 0.02]), 0.02)
        let ten: [Double?] = (0..<10).map { Double(10 - $0) / 100.0 }
        XCTAssertEqual(NoiseFloor.floor(ten), 0.02)
        var eleven: [Double?] = (0..<11).map { Double(11 - $0) / 100.0 }
        eleven.append(nil)
        XCTAssertEqual(NoiseFloor.floor(eleven), 0.03)
    }

    func testFloorIsCappedAfterRestlessStart() {
        // Unruhiger Beginn (Werte 07.11.2026): 20. Perzentil 0.0933, begrenzt auf 0.04 → Leicht 0.1, Wach 0.2.
        let restless: [Double?] = [0.2358, 0.8285, 0.0933, 0.1414, 0.3556]
        XCTAssertEqual(NoiseFloor.floor(restless), NoiseFloor.maxFloor)
        XCTAssertEqual(NoiseFloor.light(config: .standard, floor: NoiseFloor.floor(restless)), 0.1, accuracy: 1e-12)
        XCTAssertEqual(NoiseFloor.wake(config: .standard, floor: NoiseFloor.floor(restless)), 0.2, accuracy: 1e-12)
        // Genau auf der Grenze und darunter unverändert.
        XCTAssertEqual(NoiseFloor.floor([0.04, 0.04, 0.04, 0.04]), 0.04)
        XCTAssertEqual(NoiseFloor.floor([0.0399, 0.0399, 0.0399, 0.0399]), 0.0399)
    }

    func testThresholdsOnlyRise() {
        let config = DetectorConfig.standard
        XCTAssertEqual(NoiseFloor.light(config: config, floor: nil), 0.002)
        XCTAssertEqual(NoiseFloor.wake(config: config, floor: nil), 0.1)
        // Kleines Rauschen: Startwerte bleiben.
        XCTAssertEqual(NoiseFloor.light(config: config, floor: 0.0004), 0.002)
        XCTAssertEqual(NoiseFloor.wake(config: config, floor: 0.0004), 0.1)
        // Galaxy Watch 07.11.2026: ruhig rund 0.02 m/s².
        XCTAssertEqual(NoiseFloor.light(config: config, floor: 0.02), 0.05, accuracy: 1e-12)
        XCTAssertEqual(NoiseFloor.wake(config: config, floor: 0.02), 0.1, accuracy: 1e-12)
        XCTAssertEqual(NoiseFloor.light(config: config, floor: 0.03), 0.075, accuracy: 1e-12)
        XCTAssertEqual(NoiseFloor.wake(config: config, floor: 0.03), 0.15, accuracy: 1e-12)
        // Kalibrierte Schwelle über dem Rauschen bleibt.
        XCTAssertEqual(NoiseFloor.light(threshold: 0.004, floor: 0.001), 0.004)
    }

    func testWindowLightOnlyLowersToTheLearned() throws {
        let learned = try DetectorConfig(personalFloor: 0.0197)
        // Andere Armlage (Nacht 17.11.2026): Fenster 0.02955 -> 2.5 × gelernt statt 2.5 × Fenster.
        XCTAssertEqual(NoiseFloor.windowLight(config: learned, floor: 0.02955), 0.04925, accuracy: 1e-12)
        XCTAssertEqual(NoiseFloor.light(config: learned, floor: 0.02955), 0.073875, accuracy: 1e-12)
        // Sehr unruhige Armlage: mindestens 1.5 × Fenster.
        XCTAssertEqual(NoiseFloor.windowLight(config: learned, floor: 0.04), 0.06, accuracy: 1e-12)
        // Fenster ruhiger als gelernt: wie bisher.
        XCTAssertEqual(NoiseFloor.windowLight(config: learned, floor: 0.01), 0.025, accuracy: 1e-12)
        // Ohne gelerntes, ohne Grundrauschen und mit unplausiblem gelerntem (< 0.25 × Fenster): wie bisher.
        XCTAssertEqual(NoiseFloor.windowLight(config: .standard, floor: 0.0296), 0.074, accuracy: 1e-12)
        XCTAssertEqual(NoiseFloor.windowLight(config: learned, floor: nil), 0.002)
        XCTAssertEqual(NoiseFloor.windowLight(config: try DetectorConfig(personalFloor: 0.0045), floor: 0.0199),
                       0.04975, accuracy: 1e-12)
        // Genau 0.25 × Fenster gilt als plausibel, knapp darunter nicht.
        XCTAssertEqual(NoiseFloor.windowLight(config: try DetectorConfig(personalFloor: 0.005), floor: 0.02), 0.03,
                       accuracy: 1e-12)
        XCTAssertEqual(NoiseFloor.windowLight(config: try DetectorConfig(personalFloor: 0.0049), floor: 0.02), 0.05,
                       accuracy: 1e-12)
        // Startwert bleibt Untergrenze, ebenso eine kalibrierte Schwelle.
        XCTAssertEqual(NoiseFloor.windowLight(config: try DetectorConfig(personalFloor: 0.0005), floor: 0.0005), 0.002)
        let calibrated = try DetectorConfig(lightThreshold: 0.06, personalFloor: 0.0197)
        XCTAssertEqual(NoiseFloor.windowLight(config: calibrated, floor: 0.02955), 0.06, accuracy: 1e-12)
    }

    func testSummaryForDiagnosis() throws {
        let config = DetectorConfig.standard
        XCTAssertEqual(NoiseFloor.summary(config: config, motions: [0.02, nil, 0.02, 0.02, 0.4]),
                       "floor=0.0200 light=0.0500 wake=0.1000")
        XCTAssertEqual(NoiseFloor.summary(config: config, motions: [0.02, nil]), "floor=- light=0.0020 wake=0.1000")
        // Wirksame Leicht-Schwelle im Fenster: Fenster 0.024 (1.5 × gelernt 0.016), Leicht 2.5 × gelernt.
        XCTAssertEqual(NoiseFloor.summary(config: try DetectorConfig(personalFloor: 0.016),
                                          motions: Array(repeating: 0.03, count: 5)),
                       "floor=0.0240 light=0.0400 wake=0.1200")
    }

    func testPersonalFloorOnlyUntilTheMeasurementHasItsOwn() throws {
        // Lernphase: Das eigene Grundrauschen gilt, solange die Messung weniger als 4 Werte hat, höchstens 0.04.
        XCTAssertEqual(NoiseFloor.floor([0.03, nil, 0.05], personal: 0.02), 0.02)
        XCTAssertEqual(NoiseFloor.floor([], personal: 0.09), NoiseFloor.maxFloor)
        XCTAssertNil(NoiseFloor.floor([0.03], personal: nil))
        // Ab 4 Werten gilt nur das eigene der Messung, auch wenn es tiefer liegt.
        XCTAssertEqual(NoiseFloor.floor([0.001, 0.002, 0.003, 0.004], personal: 0.02), 0.001)
        // Zusammenfassung mit dem gelernten Wert, solange die Messung zu wenige hat.
        XCTAssertEqual(
            NoiseFloor.summary(config: try DetectorConfig(personalFloor: 0.02), motions: [0.3]),
            "floor=0.0200 light=0.0500 wake=0.1000"
        )
    }
}
