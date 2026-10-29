import XCTest
import PhaseWakeCore

/// Gleiche Fälle wie `NightHistoryTest` (Kotlin), dazu das Swift-eigene Verwerfen eines ungültigen Befindens.
final class NightHistoryTests: XCTestCase {
    /// 2026-10-01T04:30:00Z.
    private let start = Date(timeIntervalSince1970: 1_790_829_000)

    private func night(_ day: Int, _ epochs: [Epoch] = [Epoch(motion: 0, heartRate: 52)], wokeMinutes: Double = 18, reason: WakeReason = .lightSleep) -> NightRecord {
        let ws = start.addingTimeInterval(Double(day) * 86_400)
        return NightRecord(alarmId: "geheim-123", windowStart: ws, target: ws.addingTimeInterval(1800), wokeAt: ws.addingTimeInterval(wokeMinutes * 60),
                           reason: reason, snoozes: 1, epochs: epochs)
    }

    func testKeepsNewestNinety() {
        let kept = NightHistory.trim((0..<100).reversed().map { night($0) })
        XCTAssertEqual(kept.count, 90)
        XCTAssertEqual(kept.first?.windowStart, start.addingTimeInterval(10 * 86_400))
        XCTAssertEqual(kept.last?.windowStart, start.addingTimeInterval(99 * 86_400))
    }

    func testMinutesBeforeTarget() {
        XCTAssertEqual(night(0).minutesBeforeTarget, 12)
        XCTAssertEqual(night(0, wokeMinutes: 35).minutesBeforeTarget, 0)
    }

    func testMinutesAfterTarget() {
        XCTAssertEqual(night(0).minutesAfterTarget, 0)
        XCTAssertEqual(night(0, wokeMinutes: 30).minutesAfterTarget, 0)
        XCTAssertEqual(night(0, wokeMinutes: 35).minutesAfterTarget, 5)
        // Angefangene Minuten zählen nicht: 30 s nach der Zielzeit gilt als pünktlich.
        let halfMinute = night(0, wokeMinutes: 30.5)
        XCTAssertEqual(halfMinute.minutesAfterTarget, 0)
        XCTAssertEqual(halfMinute.minutesBeforeTarget, 0)
    }

    func testCsvIsAnonymous() {
        let csv = NightHistory.exportCsv([night(1, [Epoch(motion: 0.0021, heartRate: 52.34), Epoch(motion: nil, heartRate: nil)]).with(feeling: 4), night(0)])
        let lines = csv.split(separator: "\n").map(String.init)
        XCTAssertEqual(lines[0], "night,window_minutes,reason,woke_minutes_before_target,snoozes,feeling,epoch,motion,hr,notes")
        XCTAssertEqual(lines[1], "1,30,LIGHT_SLEEP,12,1,,0,0.000000,52.0,")
        XCTAssertEqual(lines[2], "2,30,LIGHT_SLEEP,12,1,4,0,0.002100,52.3,")
        XCTAssertEqual(lines[3], "2,30,LIGHT_SLEEP,12,1,4,1,,,")
        XCTAssertFalse(csv.contains("geheim"))
        XCTAssertFalse(csv.contains("2026"))
    }

    func testCalibrationInputDropsNothingButOrders() {
        let motions = NightHistory.motionsForCalibration([night(2, [Epoch(motion: 0.3, heartRate: nil)]), night(1)])
        XCTAssertEqual(motions, [[0.0], [0.3]])
    }

    /// Swift verwirft ein Befinden ausserhalb 1–5 (Kotlin lehnt es ab; der Codec prüft vorher).
    func testFeelingOutsideRangeIsDropped() {
        XCTAssertNil(night(0).with(feeling: 0).feeling)
        XCTAssertNil(night(0).with(feeling: 6).feeling)
        XCTAssertEqual(night(0).with(feeling: 5).feeling, 5)
        XCTAssertNil(night(0).with(feeling: 5).with(feeling: nil).feeling)
    }

    func testLightShareSkipsNightsWithoutEarlyWake() {
        let reasons: [WakeReason] = [.lightSleep, .deadline, .awake, .exact, .shortNight]
        let nights = reasons.enumerated().map { night($0.offset, reason: $0.element) }
        // 1 von 3 (ohne Genau und kurze Nacht) = 33 %, nicht 1 von 5.
        XCTAssertEqual(NightHistory.lightShare(nights), 33)
        XCTAssertEqual(NightHistory.lightShare(nights + [night(9), night(10)]), 60)
        XCTAssertNil(NightHistory.lightShare(nights.filter { $0.reason == .exact || $0.reason == .shortNight }))
        XCTAssertNil(NightHistory.lightShare([]))
    }
}
