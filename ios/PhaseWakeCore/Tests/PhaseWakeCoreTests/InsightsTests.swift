import XCTest
@testable import PhaseWakeCore

/// Gleiche Fälle wie `InsightsTest` (Kotlin).
final class InsightsTests: XCTestCase {
    private var utc: Calendar {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "UTC")!
        return c
    }

    private let start = ISO8601DateFormatter().date(from: "2026-10-28T05:30:00Z")!

    private func night(_ epochs: [Epoch], feeling: Int? = nil, daysAgo: Double = 0) -> NightRecord {
        let ws = start.addingTimeInterval(-daysAgo * 86_400)
        return NightRecord(alarmId: "a", windowStart: ws, target: ws.addingTimeInterval(1800), wokeAt: ws.addingTimeInterval(1200), reason: .lightSleep, snoozes: 0, epochs: epochs, feeling: feeling)
    }

    private func epochs(_ n: Int, _ motion: (Int) -> Double?, _ hr: (Int) -> Double?) -> [Epoch] {
        (0..<n).map { Epoch(motion: motion($0), heartRate: hr($0)) }
    }

    func testQualityFollowsSignalShares() {
        XCTAssertEqual(NightQuality.of(night(epochs(12, { _ in 0.001 }, { _ in 55 }))), .full)
        XCTAssertEqual(NightQuality.of(night(epochs(12, { _ in 0.001 }, { $0 < 5 ? 55 : nil }))), .motionOnly)
        XCTAssertEqual(NightQuality.of(night(epochs(12, { $0 < 8 ? 0.001 : nil }, { _ in 55 }))), .partial)
        XCTAssertEqual(NightQuality.of(night(epochs(12, { _ in nil }, { _ in 55 }))), .none)
        XCTAssertEqual(NightQuality.of(night(epochs(2, { _ in 0.001 }, { _ in 55 }))), .none)
        XCTAssertEqual(NightQuality.of(night(epochs(12, { $0 < 2 ? nil : 0.001 }, { _ in 55 }))), .full)
    }

    private func at(_ text: String) -> Date { ISO8601DateFormatter().date(from: text + ":00Z")! }
    private func span(_ from: String, _ to: String, _ stage: SleepStage) -> StageSpan { StageSpan(start: at(from), end: at(to), stage: stage) }

    func testSleepIsGroupedIntoNights() {
        let nights = SleepSummary.group([
            span("2026-10-26T23:10", "2026-10-27T03:00", .light),
            span("2026-10-27T03:00", "2026-10-27T03:20", .awake),
            span("2026-10-27T03:20", "2026-10-27T06:40", .deep),
            span("2026-10-27T14:00", "2026-10-27T14:40", .light),
            span("2026-10-28T00:30", "2026-10-28T06:30", .rem),
        ])
        XCTAssertEqual(nights.count, 2)
        XCTAssertEqual(nights[0], SleepSummary(start: at("2026-10-26T23:10"), end: at("2026-10-27T06:40"), asleepMinutes: 430))
        XCTAssertEqual(nights[1].asleepMinutes, 360)
        XCTAssertEqual(SleepSummary.group([span("2026-10-27T01:00", "2026-10-27T07:00", .awake)]), [])
    }

    func testTrendsCompareWeeksAndNeedEnoughNights() {
        let now = ISO8601DateFormatter().date(from: "2026-10-28T08:00:00Z")!
        func sleep(_ daysAgo: Double, _ minutes: Int, _ bedHour: Int, _ bedMinute: Int) -> SleepSummary {
            let end = now.addingTimeInterval(-daysAgo * 86_400 - 3600)
            let day = utc.startOfDay(for: end).addingTimeInterval(-86_400)
            let bed = day.addingTimeInterval(Double(bedHour * 3600 + bedMinute * 60))
            return SleepSummary(start: bed, end: end, asleepMinutes: minutes)
        }
        let sleeps = [sleep(0, 420, 23, 0), sleep(1, 400, 23, 30), sleep(2, 440, 23, 0), sleep(8, 460, 22, 30), sleep(9, 440, 23, 0), sleep(10, 450, 23, 30)]
        let nights = [night([], feeling: 4, daysAgo: 0), night([], feeling: 3, daysAgo: 1), night([], feeling: 5, daysAgo: 2), night([], feeling: 2, daysAgo: 8)]
        let trends = Trends.compute(nights: nights, sleeps: sleeps, now: now, calendar: utc)
        XCTAssertEqual(trends.asleepThisWeek, 420)
        XCTAssertEqual(trends.asleepLastWeek, 450)
        XCTAssertEqual(trends.asleepChange, -30)
        XCTAssertEqual(trends.feelingThisWeek, 4.0)
        XCTAssertNil(trends.feelingLastWeek)
        XCTAssertEqual(trends.bedtimeSpreadMinutes, 16)

        let few = Trends.compute(nights: Array(nights.prefix(2)), sleeps: Array(sleeps.prefix(2)), now: now, calendar: utc)
        XCTAssertNil(few.asleepThisWeek)
        XCTAssertNil(few.asleepChange)
        XCTAssertNil(few.bedtimeSpreadMinutes)
    }

    func testBedtimeAroundMidnightIsNotSplit() {
        let now = ISO8601DateFormatter().date(from: "2026-10-28T08:00:00Z")!
        let base = ISO8601DateFormatter().date(from: "2026-10-27T23:50:00Z")!
        let sleeps = (0..<5).map { d -> SleepSummary in
            let bed = base.addingTimeInterval(-Double(d) * 86_400 + (d % 2 == 0 ? 0 : 1200))
            return SleepSummary(start: bed, end: bed.addingTimeInterval(7 * 3600), asleepMinutes: 400)
        }
        XCTAssertEqual(Trends.compute(nights: [], sleeps: sleeps, now: now, calendar: utc).bedtimeSpreadMinutes, 9)
    }
}

/// Gleiche Fälle wie `MissHintTest` (Kotlin).
final class MissHintTests: XCTestCase {
    private let spec = try! AlarmSpec(id: "a", hour: 7, minute: 0, windowMinutes: 30)
    private let start = ISO8601DateFormatter().date(from: "2026-10-20T05:30:00Z")!

    private func night(_ day: Double, _ reason: WakeReason = .deadline, hr: Double? = 55, motion: Double? = 0.0005, id: String = "a") -> NightRecord {
        let ws = start.addingTimeInterval(day * 86_400)
        return NightRecord(alarmId: id, windowStart: ws, target: ws.addingTimeInterval(1800), wokeAt: ws.addingTimeInterval(1800), reason: reason, snoozes: 0,
                           epochs: Array(repeating: Epoch(motion: motion, heartRate: hr), count: 60))
    }

    func testNeedsThreeDeadlineNightsWithFullWindow() throws {
        let three = [night(0), night(1), night(2)]
        XCTAssertEqual(MissHint.of(spec, nights: three), .calm)
        XCTAssertNil(MissHint.of(spec, nights: Array(three.prefix(2))))
        XCTAssertNil(MissHint.of(try AlarmSpec(id: "a", hour: 7, minute: 0, windowMinutes: 20), nights: three))
        XCTAssertNil(MissHint.of(spec, nights: three + [night(3, .lightSleep)]))
        XCTAssertNil(MissHint.of(nil, nights: three))
        XCTAssertNil(MissHint.of(spec, nights: [night(0), night(1), night(2, id: "b")]))
    }

    func testCauseFollowsDataQuality() {
        XCTAssertEqual(MissHint.of(spec, nights: [night(0, hr: nil), night(1, hr: nil), night(2)]), .heartMissing)
        XCTAssertEqual(MissHint.of(spec, nights: [night(0, motion: nil), night(1, motion: nil), night(2, hr: nil)]), .gaps)
    }
}

/// Gleiche Fälle wie `RobustnessTest` (Kotlin).
final class RobustnessTests: XCTestCase {
    func testInvalidSamplesAreIgnored() {
        let motion = MotionEnergyAccumulator(minDiffs: 1)
        motion.add(timestampNanos: 0, x: 0, y: 0, z: 9.81)
        motion.add(timestampNanos: 40_000_000, x: .nan, y: 0, z: 9.81)
        motion.add(timestampNanos: 80_000_000, x: 0, y: .infinity, z: 9.81)
        motion.add(timestampNanos: 120_000_000, x: 0.2, y: 0, z: 9.81)
        XCTAssertEqual(motion.closeEpoch()!, 0.15, accuracy: 1e-9)

        let hr = HeartRateAccumulator()
        hr.add(bpm: .nan)
        hr.add(bpm: .infinity)
        hr.add(bpm: 60)
        XCTAssertEqual(hr.closeEpoch(), 60)
    }

    func testTimeJumpDoesNotProduceEndlessEpochs() {
        let a = EpochAssembler(startNanos: 0)
        let tooLate = (EpochAssembler.maxEpochs + 1) * 30_000_000_000
        XCTAssertEqual(a.addMotion(timestampNanos: tooLate, x: 0, y: 0, z: 9.81), [])
        XCTAssertEqual(a.advance(nowNanos: tooLate, graceNanos: 0), [])
        XCTAssertEqual(a.currentIndex, 0)
        XCTAssertEqual(a.advance(nowNanos: 3 * 30_000_000_000, graceNanos: 0).count, 3)
    }
}
