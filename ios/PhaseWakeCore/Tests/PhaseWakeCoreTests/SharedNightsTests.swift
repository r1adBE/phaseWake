import XCTest
import PhaseWakeCore

/// Prüft alle gemeinsamen Testnächte; dieselben Nächte prüft die Kotlin-Umsetzung.
final class SharedNightsTests: XCTestCase {

    func testAllNightsMatchExpectation() throws {
        let nights = try NightFixtures.all()
        XCTAssertGreaterThanOrEqual(nights.count, 9, "Testnächte nicht gefunden in \(NightFixtures.directory.path)")

        for night in nights {
            let detector = WakeDetector()
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
}
