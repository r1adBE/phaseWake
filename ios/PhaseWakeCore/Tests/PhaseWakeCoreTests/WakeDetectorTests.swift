import XCTest
@testable import PhaseWakeCore

final class WakeDetectorTests: XCTestCase {

    private let still = Epoch(motion: 0.0, heartRate: 52.0)

    private func feed(_ detector: WakeDetector, _ epochs: Epoch...) -> [Decision] {
        epochs.map { detector.onEpoch($0) }
    }

    func testWarmupNeverWakesEvenWhenAwake() {
        let detector = WakeDetector()
        let decisions = feed(detector, Epoch(motion: 1.0, heartRate: 80.0), Epoch(motion: 1.0, heartRate: 80.0))
        XCTAssertEqual(decisions, [.wait, .wait])
    }

    func testWarmupMotionDoesNotCountLater() {
        let detector = WakeDetector()
        _ = feed(detector, Epoch(motion: 1.0, heartRate: 52.0), Epoch(motion: 1.0, heartRate: 52.0))
        // Erste entscheidbare Epoche ist ruhig; die Aufwärmphase darf nicht als Treffer zählen.
        XCTAssertEqual(detector.onEpoch(still), .wait)
    }

    func testAwakeNeedsTwoOfThree() {
        let detector = WakeDetector()
        _ = feed(detector, still, still, Epoch(motion: 0.2, heartRate: 52.0), still)
        XCTAssertEqual(detector.onEpoch(Epoch(motion: 0.2, heartRate: 52.0)), .wake(.awake))
    }

    func testAwakeHitsOutsideWindowDoNotCount() {
        let detector = WakeDetector()
        let hit = Epoch(motion: 0.2, heartRate: 52.0)
        // Treffer in Epoche 2 und 5: Fenster 3 erfasst nur einen davon.
        let decisions = feed(detector, still, still, hit, still, still, hit)
        XCTAssertEqual(decisions.last, .wait)
    }

    func testLightSleepWaitsForHeartRateRise() {
        let detector = WakeDetector()
        _ = feed(detector, still, still, Epoch(motion: 0.003, heartRate: 52.0))
        XCTAssertEqual(detector.onEpoch(Epoch(motion: 0.003, heartRate: 54.0)), .wait)
        // Mittel aus 54 und 56 = 55 >= 52 × 1.05 = 54.6
        XCTAssertEqual(detector.onEpoch(Epoch(motion: 0.003, heartRate: 56.0)), .wake(.lightSleep))
    }

    func testMissingCurrentHeartRateBlocksLightSleep() {
        let detector = WakeDetector()
        _ = feed(detector, still, still, Epoch(motion: 0.003, heartRate: nil))
        // Bewegung allein behauptet keinen leichten Schlaf: ohne aktuellen Puls weiter warten.
        XCTAssertEqual(detector.onEpoch(Epoch(motion: 0.003, heartRate: nil)), .wait)
        // Die Wach-Regel braucht keinen Puls.
        _ = detector.onEpoch(Epoch(motion: 0.5, heartRate: nil))
        XCTAssertEqual(detector.onEpoch(Epoch(motion: 0.5, heartRate: nil)), .wake(.awake))
    }

    func testAwakeTakesPrecedenceOverLightSleep() {
        let detector = WakeDetector()
        _ = feed(detector, still, still, Epoch(motion: 0.5, heartRate: 70.0))
        XCTAssertEqual(detector.onEpoch(Epoch(motion: 0.5, heartRate: 70.0)), .wake(.awake))
    }

    func testMissingMotionNeverCounts() {
        let detector = WakeDetector()
        let gap = Epoch(motion: nil, heartRate: 70.0)
        XCTAssertEqual(feed(detector, still, still, gap, gap, gap, gap).last, .wait)
    }

    func testDecisionLatchesAfterWake() {
        let detector = WakeDetector()
        _ = feed(detector, still, still, Epoch(motion: 0.5, heartRate: 52.0), Epoch(motion: 0.5, heartRate: 52.0))
        XCTAssertEqual(detector.onEpoch(still), .wake(.awake))
        XCTAssertEqual(detector.finish(), .awake)
    }

    func testFinishWithoutWakeIsDeadline() {
        let detector = WakeDetector()
        _ = feed(detector, still, still, still)
        XCTAssertNil(detector.result)
        XCTAssertEqual(detector.finish(), .deadline)
        XCTAssertEqual(detector.onEpoch(Epoch(motion: 1.0, heartRate: 90.0)), .wake(.deadline))
    }

    func testZeroWarmupHasNoBaselineSoNoLightSleep() throws {
        let detector = WakeDetector(config: try DetectorConfig(warmupEpochs: 0))
        _ = detector.onEpoch(Epoch(motion: 0.003, heartRate: 52.0))
        // Ohne Basispuls ist die Pulsbedingung nicht erfüllt.
        XCTAssertEqual(detector.onEpoch(Epoch(motion: 0.003, heartRate: 60.0)), .wait)
        _ = detector.onEpoch(Epoch(motion: 0.5, heartRate: 60.0))
        XCTAssertEqual(detector.onEpoch(Epoch(motion: 0.5, heartRate: 60.0)), .wake(.awake))
    }

    func testInvalidConfigIsRejected() {
        XCTAssertThrowsError(try DetectorConfig(lightThreshold: 0.0))
        XCTAssertThrowsError(try DetectorConfig(wakeThreshold: 0.001))
        XCTAssertThrowsError(try DetectorConfig(heartRateRise: -0.1))
        XCTAssertThrowsError(try DetectorConfig(warmupEpochs: -1))
        XCTAssertThrowsError(try DetectorConfig(awakeHits: 4))
        XCTAssertThrowsError(try DetectorConfig(lightHits: 0))
    }

    func testStandardEqualsCheckedDefaults() throws {
        XCTAssertEqual(DetectorConfig.standard, try DetectorConfig())
    }
}
