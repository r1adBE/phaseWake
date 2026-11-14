import XCTest
import PhaseWakeCore

/// Audit c3b8b01 BE-3: Dokumentierte Grenzen der Erkennung mit festen Zahlen (Wert − 1 / Wert / Wert + 1), nicht nur über
/// die Konstante selbst. Gleiche Fälle wie `DetectionLimitsTest` (Kotlin).
final class DetectionLimitsTests: XCTestCase {
    private let start = Date(timeIntervalSince1970: 1_793_570_400)

    func testLearningBatteryWarnsBelowForty() {
        let record = start.addingTimeInterval(3600)
        let at = LearnNights.warnAt(record)
        XCTAssertTrue(LearnNights.shouldWarn(active: true, battery: 39, charging: false, now: at, recordStart: record))
        XCTAssertFalse(LearnNights.shouldWarn(active: true, battery: 40, charging: false, now: at, recordStart: record))
    }

    func testLatencyIsCappedAtSixtyMinutes() {
        func latency(_ minutes: Double) -> Int? {
            Learning.nightLatencyMinutes(Learning.Night(start: start, epochs: [], bedAt: start, asleepAt: start.addingTimeInterval(minutes * 60)))
        }
        XCTAssertEqual(latency(59), 59)
        XCTAssertEqual(latency(60), 60)
        XCTAssertEqual(latency(61), 60)
    }

    func testLearnedValuesAreValidOnlyInTheirRanges() {
        // Grundrauschen 0 bis 1 m/s², Ruhepuls 25 bis 150 (K-6).
        XCTAssertNil(Learning.validFloor(-0.0001))
        XCTAssertEqual(Learning.validFloor(0.0), 0.0)
        XCTAssertEqual(Learning.validFloor(1.0), 1.0)
        XCTAssertNil(Learning.validFloor(1.0001))
        XCTAssertNil(Learning.validRestingHeartRate(24.99))
        XCTAssertEqual(Learning.validRestingHeartRate(25.0), 25.0)
        XCTAssertEqual(Learning.validRestingHeartRate(150.0), 150.0)
        XCTAssertNil(Learning.validRestingHeartRate(150.01))
    }

    func testExactWindowIsZeroAndSleepAtLeastSixtyMinutes() throws {
        XCTAssertTrue(try AlarmSpec(id: "a", hour: 7, minute: 0, windowMinutes: 0).isExact)
        XCTAssertFalse(try AlarmSpec(id: "a", hour: 7, minute: 0, windowMinutes: 10).isExact)
        XCTAssertThrowsError(try AlarmSpec(id: "a", hour: 7, minute: 0, sleepDurationMinutes: 59))
        XCTAssertEqual(try AlarmSpec(id: "a", hour: 7, minute: 0, sleepDurationMinutes: 60).sleepDurationMinutes, 60)
    }

    func testPulseUpTo250IsPlausible() {
        let a = HeartRateAccumulator()
        a.add(bpm: 250.0)
        XCTAssertEqual(a.closeEpoch(), 250.0)
        a.add(bpm: 250.0001)
        XCTAssertNil(a.closeEpoch())
    }

    /// `count` Differenzen von je 1 m/s² im Abstand von 40 ms.
    private func diffs(_ a: MotionEnergyAccumulator, _ count: Int) {
        for i in 0...count { a.add(timestampNanos: Int64(i) * 40_000_000, x: i % 2 == 0 ? 0 : 1, y: 0, z: 0) }
    }

    func testMotionNeedsThreeHundredDifferences() {
        let few = MotionEnergyAccumulator()
        diffs(few, 299)
        XCTAssertNil(few.closeEpoch())
        let enough = MotionEnergyAccumulator()
        diffs(enough, 300)
        XCTAssertEqual(enough.closeEpoch() ?? -1, 0.95, accuracy: 1e-12)
    }

    func testMotionIntervalIsThirtyFiveMillisecondsAndGapOneSecond() {
        func one(_ dtNanos: Int64) -> Double? {
            let a = MotionEnergyAccumulator(minDiffs: 1)
            a.add(timestampNanos: 0, x: 0, y: 0, z: 0)
            a.add(timestampNanos: dtNanos, x: 1, y: 0, z: 0)
            return a.closeEpoch()
        }
        XCTAssertNil(one(34_999_999))
        XCTAssertEqual(one(35_000_000) ?? -1, 0.95, accuracy: 1e-12)
        XCTAssertEqual(one(1_000_000_000) ?? -1, 0.95, accuracy: 1e-12)
        XCTAssertNil(one(1_000_000_001))
    }
}
