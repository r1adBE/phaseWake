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

    func testRearmDropsTheMomentAndWaitsForANewOne() {
        let detector = WakeDetector()
        _ = feed(detector, still, still, Epoch(motion: 0.5, heartRate: 52.0), Epoch(motion: 0.5, heartRate: 52.0))
        XCTAssertEqual(detector.onEpoch(still), .wake(.awake))
        detector.rearm()
        XCTAssertNil(detector.result)
        // Solange die Unruhe im Fenster der Regel liegt, meldet sie sich wieder (die Uhr übergeht sie erneut); danach
        // weckt erst neue Bewegung.
        _ = feed(detector, still, still, still)
        detector.rearm()
        XCTAssertEqual(feed(detector, still, Epoch(motion: 0.5, heartRate: 52.0)), [.wait, .wait])
        XCTAssertEqual(detector.onEpoch(Epoch(motion: 0.5, heartRate: 52.0)), .wake(.awake))
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

    func testNoiseFloorRaisesLightThreshold() {
        let detector = WakeDetector()
        let quiet = Epoch(motion: 0.02, heartRate: 52.0)
        _ = feed(detector, quiet, quiet, quiet, quiet)
        // Rauschen 0.02 → Leicht-Schwelle 0.05: trotz Pulsanstieg kein leichter Schlaf (ohne Rauschen: geweckt).
        XCTAssertEqual(detector.onEpoch(Epoch(motion: 0.02, heartRate: 60.0)), .wait)
        XCTAssertEqual(detector.onEpoch(Epoch(motion: 0.06, heartRate: 60.0)), .wait)
        XCTAssertEqual(detector.onEpoch(Epoch(motion: 0.06, heartRate: 60.0)), .wake(.lightSleep))
    }

    func testNoiseFloorRaisesWakeThreshold() {
        let detector = WakeDetector()
        let quiet = Epoch(motion: 0.03, heartRate: 52.0)
        _ = feed(detector, quiet, quiet, quiet, quiet, quiet)
        // Rauschen 0.03 → Wach-Schwelle 0.15: zweimal 0.12 reicht nicht mehr (ohne Rauschen: wach).
        XCTAssertEqual(feed(detector, Epoch(motion: 0.12, heartRate: 52.0), Epoch(motion: 0.12, heartRate: 52.0)), [.wait, .wait])
        XCTAssertEqual(detector.onEpoch(Epoch(motion: 0.2, heartRate: 52.0)), .wait)
        XCTAssertEqual(detector.onEpoch(Epoch(motion: 0.2, heartRate: 52.0)), .wake(.awake))
    }

    func testRestlessStartStillWakesWithCappedFloor() {
        let detector = WakeDetector()
        let restless = Epoch(motion: 0.2358, heartRate: 52.0)
        _ = feed(detector, Epoch(motion: 0.4128, heartRate: 52.0), restless, restless)
        // Ohne Obergrenze: Rauschen 0.2358 → Wach-Schwelle 1.18, nie wach. Mit 0.04 → 0.2: wach.
        XCTAssertEqual(detector.onEpoch(Epoch(motion: 0.4128, heartRate: 52.0)), .wake(.awake))
    }

    func testInvalidConfigIsRejected() {
        XCTAssertThrowsError(try DetectorConfig(lightThreshold: 0.0))
        XCTAssertThrowsError(try DetectorConfig(wakeThreshold: 0.001))
        XCTAssertThrowsError(try DetectorConfig(heartRateRise: -0.1))
        XCTAssertThrowsError(try DetectorConfig(warmupEpochs: -1))
        XCTAssertThrowsError(try DetectorConfig(awakeHits: 4))
        XCTAssertThrowsError(try DetectorConfig(lightHits: 0))
        XCTAssertThrowsError(try DetectorConfig(personalFloor: -0.01))
        XCTAssertThrowsError(try DetectorConfig(restingHeartRate: 0.0))
    }

    func testRestingHeartRateIsBaselineOnlyWithoutWarmupPulse() throws {
        // Aufwärmphase ohne Puls: gelernter Ruhepuls 50 ist Basispuls, 53 >= 52.5 → leichter Schlaf.
        let learned = WakeDetector(config: try DetectorConfig(restingHeartRate: 50.0))
        _ = feed(learned, Epoch(motion: 0.0, heartRate: nil), Epoch(motion: 0.0, heartRate: nil), Epoch(motion: 0.003, heartRate: 53.0))
        XCTAssertEqual(learned.onEpoch(Epoch(motion: 0.003, heartRate: 53.0)), .wake(.lightSleep))
        // Ohne gelernten Ruhepuls wie bisher: kein Basispuls, kein leichter Schlaf.
        let plain = WakeDetector()
        _ = feed(plain, Epoch(motion: 0.0, heartRate: nil), Epoch(motion: 0.0, heartRate: nil), Epoch(motion: 0.003, heartRate: 53.0))
        XCTAssertEqual(plain.onEpoch(Epoch(motion: 0.003, heartRate: 53.0)), .wait)
        // Mit Puls in der Aufwärmphase gilt dessen Basispuls (52 → 54.6), der Ruhepuls 40 ändert nichts.
        let own = WakeDetector(config: try DetectorConfig(restingHeartRate: 40.0))
        _ = feed(own, still, still, Epoch(motion: 0.003, heartRate: 53.0))
        XCTAssertEqual(own.onEpoch(Epoch(motion: 0.003, heartRate: 53.0)), .wait)
    }

    func testPersonalFloorAppliesBeforeTheWindowHasItsOwn() throws {
        // Sensor liefert anfangs keine Bewegung: Ohne eigenes Grundrauschen gälte 0.002, 0.03 wäre leichte Bewegung.
        let gap = Epoch(motion: nil, heartRate: 52.0)
        let plain = WakeDetector()
        _ = feed(plain, gap, gap, gap, Epoch(motion: 0.03, heartRate: 57.0))
        XCTAssertEqual(plain.onEpoch(Epoch(motion: 0.03, heartRate: 57.0)), .wake(.lightSleep))
        // Gelernt 0.02 → Leicht-Schwelle 0.05: 0.03 ist Rauschen.
        let learned = WakeDetector(config: try DetectorConfig(personalFloor: 0.02))
        _ = feed(learned, gap, gap, gap, Epoch(motion: 0.03, heartRate: 57.0))
        XCTAssertEqual(learned.onEpoch(Epoch(motion: 0.03, heartRate: 57.0)), .wait)
    }

    func testStandardEqualsCheckedDefaults() throws {
        XCTAssertEqual(DetectorConfig.standard, try DetectorConfig())
    }
}
