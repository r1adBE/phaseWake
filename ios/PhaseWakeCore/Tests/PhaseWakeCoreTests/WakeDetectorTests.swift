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

    func testZeroWarmupHasNoBaselineUntilTwoPulses() throws {
        let detector = WakeDetector(config: try DetectorConfig(warmupEpochs: 0))
        _ = detector.onEpoch(Epoch(motion: 0.003, heartRate: 52.0))
        // Erst ein Puls davor: kein Basispuls, die Pulsbedingung ist nicht erfüllt.
        XCTAssertEqual(detector.onEpoch(Epoch(motion: 0.003, heartRate: 60.0)), .wait)
        // Ab zwei Pulsen (Vollaudit K-3): Median 56 → 58.8; 60 reicht.
        XCTAssertEqual(detector.onEpoch(Epoch(motion: 0.003, heartRate: 60.0)), .wake(.lightSleep))
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
        XCTAssertThrowsError(try DetectorConfig(personalFloor: .nan))
        XCTAssertThrowsError(try DetectorConfig(restingHeartRate: .nan))
        XCTAssertThrowsError(try DetectorConfig(onsetFloor: -0.01))
    }

    private func run(_ detector: WakeDetector, _ epochs: [Epoch]) -> [Decision] {
        epochs.map { detector.onEpoch($0) }
    }

    private let noPulse = Array(repeating: Epoch(motion: 0.0, heartRate: nil), count: 6)

    func testRestingHeartRateFallbackOnlyWithoutPulseAtTheStart() throws {
        // Die ersten 6 Epochen ohne Puls: Ersatz-Basispuls = Ruhepuls 50 × 1.10 = 55 → nötig 57.75 (Vollaudit K-2).
        let learned = WakeDetector(config: try DetectorConfig(restingHeartRate: 50.0))
        _ = run(learned, noPulse + [Epoch(motion: 0.003, heartRate: 58.0)])
        XCTAssertEqual(learned.onEpoch(Epoch(motion: 0.003, heartRate: 58.0)), .wake(.lightSleep))
        // Ohne gelernten Ruhepuls wie bisher: kein Basispuls, kein leichter Schlaf.
        let plain = WakeDetector()
        _ = run(plain, noPulse + [Epoch(motion: 0.003, heartRate: 58.0)])
        XCTAssertEqual(plain.onEpoch(Epoch(motion: 0.003, heartRate: 58.0)), .wait)
        // Mit Puls zu Beginn gilt dessen Basispuls (52 → 54.6); der Ruhepuls 40 begrenzt ihn nur nach unten.
        let own = WakeDetector(config: try DetectorConfig(restingHeartRate: 40.0))
        _ = feed(own, still, still, Epoch(motion: 0.003, heartRate: 53.0))
        XCTAssertEqual(own.onEpoch(Epoch(motion: 0.003, heartRate: 53.0)), .wait)
    }

    func testMorningPulseNearRestingIsNoRise() throws {
        // Vollaudit K-2: Ruhepuls 50, morgens 56 bei leichter Bewegung, Fensterbeginn ohne Puls. Früher Basispuls 50 → 52.5:
        // Die Bewegung allein weckte. Jetzt 55 → 57.75: kein Anstieg.
        let detector = WakeDetector(config: try DetectorConfig(restingHeartRate: 50.0))
        let decisions = run(detector, noPulse + Array(repeating: Epoch(motion: 0.003, heartRate: 56.0), count: 20))
        XCTAssertTrue(decisions.allSatisfy { $0 == .wait })
    }

    func testBaselineIsTheMedianOfTheFirstEpochs() {
        // Vollaudit K-3: Aufwärmphase erhöht (64), danach 60. Früher Basispuls 64 → 67.2; jetzt Median der ersten 6 Epochen
        // (64, 64, 60, 60, 60, 60) = 60 → 63.
        let detector = WakeDetector()
        let calm = Epoch(motion: 0.0, heartRate: 60.0)
        _ = feed(detector, Epoch(motion: 0.0, heartRate: 64.0), Epoch(motion: 0.0, heartRate: 64.0), calm, calm, calm, calm, calm, calm)
        XCTAssertEqual(detector.onEpoch(Epoch(motion: 0.003, heartRate: 63.0)), .wait)
        XCTAssertEqual(detector.onEpoch(Epoch(motion: 0.003, heartRate: 64.0)), .wake(.lightSleep))
    }

    func testLearnedRestingHeartRateBoundsTheBaseline() throws {
        // Basispuls des Fensters 45 unter dem Ruhepuls 50: gilt 50 → 52.5; 52 ist kein Anstieg.
        let low = WakeDetector(config: try DetectorConfig(restingHeartRate: 50.0))
        _ = run(low, Array(repeating: Epoch(motion: 0.0, heartRate: 45.0), count: 6) + [Epoch(motion: 0.003, heartRate: 52.0)])
        XCTAssertEqual(low.onEpoch(Epoch(motion: 0.003, heartRate: 52.0)), .wait)
        // Basispuls 80 über Ruhepuls × 1.3 = 65: gilt 65 → 68.25; 70 ist ein Anstieg.
        let high = WakeDetector(config: try DetectorConfig(restingHeartRate: 50.0))
        _ = run(high, Array(repeating: Epoch(motion: 0.0, heartRate: 80.0), count: 6) + [Epoch(motion: 0.003, heartRate: 70.0)])
        XCTAssertEqual(high.onEpoch(Epoch(motion: 0.003, heartRate: 70.0)), .wake(.lightSleep))
    }

    func testRestlessStartIsCappedByTheLearnedFloor() throws {
        // Vollaudit K-1: Gelernt 0.018, das Fenster beginnt unruhig (0.07–0.09). Ohne Obergrenze Wach-Schwelle 0.2; mit
        // 1.5 × 0.018 = 0.027 → 0.135: zweimal 0.14 weckt.
        let restless = [0.07, 0.09, 0.08, 0.075, 0.085, 0.07, 0.09, 0.08].map { Epoch(motion: $0, heartRate: 60.0) }
        let learned = WakeDetector(config: try DetectorConfig(personalFloor: 0.018))
        _ = run(learned, restless + [Epoch(motion: 0.14, heartRate: 60.0)])
        XCTAssertEqual(learned.onEpoch(Epoch(motion: 0.14, heartRate: 60.0)), .wake(.awake))
        // Ohne gelernten Wert wie bisher (Obergrenze 0.04).
        let plain = WakeDetector()
        _ = run(plain, restless + [Epoch(motion: 0.14, heartRate: 60.0)])
        XCTAssertEqual(plain.onEpoch(Epoch(motion: 0.14, heartRate: 60.0)), .wait)
    }

    func testOnsetFloorOfTheNightComesBeforeTheLearnedOne() throws {
        // Einschlaf-Messung dieser Nacht 0.012, gelernt 0.03: Obergrenze 1.5 × 0.012 = 0.018 → Wach 0.1 (Startwert).
        let restless = Array(repeating: Epoch(motion: 0.08, heartRate: 60.0), count: 8)
        let config = try DetectorConfig(personalFloor: 0.03).withOnsetFloor(0.012)
        XCTAssertEqual(try XCTUnwrap(NoiseFloor.windowFloor(restless.map(\.motion), config: config)), 0.018, accuracy: 1e-12)
        let detector = WakeDetector(config: config)
        _ = run(detector, restless + [Epoch(motion: 0.12, heartRate: 60.0)])
        XCTAssertEqual(detector.onEpoch(Epoch(motion: 0.12, heartRate: 60.0)), .wake(.awake))
        // Nur gelernt 0.03: Obergrenze 0.04 (maxFloor).
        XCTAssertEqual(try XCTUnwrap(NoiseFloor.windowFloor(restless.map(\.motion), config: try DetectorConfig(personalFloor: 0.03))),
                       0.04, accuracy: 1e-12)
        // Zu wenige Werte: das der Einschlaf-Messung gilt; ruhiger als das bekannte: das eigene des Fensters.
        XCTAssertEqual(try XCTUnwrap(NoiseFloor.windowFloor([nil, 0.5], config: config)), 0.012, accuracy: 1e-12)
        XCTAssertEqual(try XCTUnwrap(NoiseFloor.windowFloor(Array(repeating: 0.01, count: 8), config: config)), 0.01, accuracy: 1e-12)
        XCTAssertNil(DetectorConfig.standard.withOnsetFloor(.nan).onsetFloor)
    }

    func testOnsetFloorKnownAfterTheWindowBegan() throws {
        // Apple Watch: Das Grundrauschen der Einschlaf-Messung kommt erst nach Fensterbeginn (`WatchOnset`); ab dann gilt
        // es (Vollaudit K-1). Wie Kotlin.
        let restless = Array(repeating: Epoch(motion: 0.08, heartRate: 60.0), count: 8)
        let detector = WakeDetector(config: try DetectorConfig(personalFloor: 0.03))
        _ = run(detector, restless + [Epoch(motion: 0.12, heartRate: 60.0)])
        XCTAssertEqual(detector.onEpoch(Epoch(motion: 0.12, heartRate: 60.0)), .wait)
        detector.useOnsetFloor(0.012)
        XCTAssertEqual(detector.config.onsetFloor, 0.012)
        // Die beiden 0.12 davor liegen noch im Fenster der Wach-Regel: Ab jetzt zählen sie (Schwelle 0.1).
        XCTAssertEqual(detector.onEpoch(still), .wake(.awake))
        detector.useOnsetFloor(.nan)
        XCTAssertNil(detector.config.onsetFloor)
    }

    func testRearmNeedsNewHitsAfterTheBoundary() {
        // Vollaudit K-5: Der übergangene Moment liegt nach rearm() noch im Fenster der Wach-Regel; früher weckte er wieder,
        // sobald die Sperre endete. Jetzt zählen nur neue Treffer.
        let detector = WakeDetector()
        _ = feed(detector, still, still, Epoch(motion: 0.5, heartRate: 52.0))
        XCTAssertEqual(detector.onEpoch(Epoch(motion: 0.5, heartRate: 52.0)), .wake(.awake))
        detector.rearm()
        XCTAssertEqual(detector.onEpoch(still), .wait)
        XCTAssertEqual(detector.onEpoch(Epoch(motion: 0.5, heartRate: 52.0)), .wait)
        XCTAssertEqual(detector.onEpoch(Epoch(motion: 0.5, heartRate: 52.0)), .wake(.awake))
    }

    func testInvalidLearnedValuesCountAsNone() {
        // Vollaudit K-6: wie Kotlin; kaputte Werte gelten als keine.
        for floor in [Double.nan, -0.01, .infinity, 5.0] {
            XCTAssertNil(DetectorConfig.standard.withLearned(floor: floor, restingHeartRate: nil).personalFloor, "\(floor)")
        }
        for pulse in [Double.nan, 0.0, -50.0, 10.0, 400.0, .infinity] {
            XCTAssertNil(DetectorConfig.standard.withLearned(floor: nil, restingHeartRate: pulse).restingHeartRate, "\(pulse)")
        }
        let ok = DetectorConfig.standard.withLearned(floor: 0.02, restingHeartRate: 52.0)
        XCTAssertEqual(ok.personalFloor, 0.02)
        XCTAssertEqual(ok.restingHeartRate, 52.0)
        XCTAssertEqual(DetectorConfig.standard.withLearned(floor: 0.0, restingHeartRate: nil).personalFloor, 0.0)
        XCTAssertEqual(Learning.withLearned(.standard, Learning.Values(floor: .nan, restingHeartRate: -1, latencyMinutes: nil, nights: 7)),
                       .standard)
        XCTAssertEqual(DetectorConfig.standard.withOnsetFloor(0.01).withLearned(floor: 0.02, restingHeartRate: 52.0).onsetFloor, 0.01)
        let snapshot = LearningSnapshot(context: [SyncKeys.learnUpdatedAt: Int64(5), SyncKeys.learnFloor: Double.nan,
                                                  SyncKeys.learnRestingHr: 1e9])
        XCTAssertNil(snapshot?.floor)
        XCTAssertNil(snapshot?.restingHeartRate)
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
