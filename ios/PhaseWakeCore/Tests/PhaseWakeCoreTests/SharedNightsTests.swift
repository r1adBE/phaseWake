import XCTest
import PhaseWakeCore

/// Prüft alle gemeinsamen Testnächte; dieselben Nächte prüft die Kotlin-Umsetzung.
final class SharedNightsTests: XCTestCase {

    func testAllNightsMatchExpectation() throws {
        let nights = try NightFixtures.all()
        XCTAssertGreaterThanOrEqual(nights.count, 9, "Testnächte nicht gefunden in \(NightFixtures.directory.path)")

        for night in nights {
            let detector = WakeDetector(config: night.config)
            var wakeIndex: Int?
            var wakeReason: WakeReason?
            for (index, epoch) in night.epochs.enumerated() {
                if case .wake(let reason) = detector.onEpoch(epoch) {
                    wakeIndex = index
                    wakeReason = reason
                    break
                }
            }
            XCTAssertEqual(wakeIndex, night.expectedIndex, "\(night.name): Weck-Epoche")
            XCTAssertEqual(wakeReason, night.expectedReason, "\(night.name): Grund")
        }
    }

    /// Ohne gelernte Werte entscheiden die Lernphasen-Nächte anders: Das zeigt, dass die Angaben wirken.
    func testLearnedNightsDependOnTheLearnedValues() throws {
        let byName = Dictionary(uniqueKeysWithValues: try NightFixtures.all().map { ($0.name, $0) })
        func firstWake(_ night: NightFixture?) -> Int? {
            let detector = WakeDetector()
            for (index, epoch) in (night?.epochs ?? []).enumerated() {
                if case .wake = detector.onEpoch(epoch) { return index }
            }
            return nil
        }
        XCTAssertEqual(firstWake(byName["learned_floor_sensor_start"]), 5)
        XCTAssertNil(firstWake(byName["learned_resting_hr"]))
        XCTAssertNotNil(byName["learned_resting_hr"])
    }
}
