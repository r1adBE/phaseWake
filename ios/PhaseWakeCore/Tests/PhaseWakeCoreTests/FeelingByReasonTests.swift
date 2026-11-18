import XCTest
@testable import PhaseWakeCore

/// Befinden je Weckgrund für die Diagnose. Gleiche Fälle wie `FeelingByReasonTest` (Kotlin).
final class FeelingByReasonTests: XCTestCase {
    /// 2026-11-01T05:30:00Z.
    private let t0 = Date(timeIntervalSince1970: 1_793_511_000)

    private func night(_ day: Int, _ reason: WakeReason, _ feeling: Int?) -> NightRecord {
        let start = t0.addingTimeInterval(Double(day * 86_400))
        return NightRecord(alarmId: "a", windowStart: start, target: start.addingTimeInterval(1800),
                           wokeAt: start.addingTimeInterval(900), reason: reason, snoozes: 0, epochs: [],
                           feeling: feeling)
    }

    func testCountsAndAveragesPerReasonInFixedOrder() {
        let nights = [
            night(0, .lightSleep, 4), night(1, .lightSleep, 5), night(2, .deadline, 2), night(3, .awake, 3),
            night(4, .lightSleep, nil), night(5, .shortNight, 1),
        ]
        let rows = FeelingByReason.of(nights)
        XCTAssertEqual(rows.map(\.reason), FeelingByReason.order)
        XCTAssertEqual(rows.map(\.nights), [2, 1, 1, 0, 1])
        XCTAssertEqual(rows[0].average, 4.5)
        XCTAssertNil(rows[3].average)
        XCTAssertEqual(rows.map { $0.averageText() }, ["4.5", "3.0", "2.0", "-", "1.0"])
    }

    func testAverageRoundsHalfUpWithoutFloatingPoint() {
        // 77 / 20 = 3.85 → 3.9 (als Gleitkomma knapp darunter); 11 / 3 = 3.67 → 3.7; 10 / 3 = 3.33 → 3.3.
        let nights = (0..<20).map { night($0, .lightSleep, $0 < 17 ? 4 : 3) }
        XCTAssertEqual(FeelingByReason.of(nights)[0].averageText(), "3.9")
        let three = [4, 4, 3].enumerated().map { night($0.offset, .deadline, $0.element) }
        XCTAssertEqual(FeelingByReason.of(three)[2].averageText(), "3.7")
        let low = [4, 3, 3].enumerated().map { night($0.offset, .exact, $0.element) }
        XCTAssertEqual(FeelingByReason.of(low)[3].averageText(), "3.3")
    }

    func testLinesNameTheReviewThreshold() {
        let nights = [night(0, .lightSleep, 4), night(1, .deadline, 3), night(2, .exact, nil)]
        XCTAssertEqual(FeelingByReason.lines(nights), [
            FeelingByReason.note,
            "LIGHT_SLEEP 1 nights avg 4.0",
            "AWAKE 0 nights avg -",
            "DEADLINE 1 nights avg 3.0",
            "EXACT 0 nights avg -",
            "SHORT_NIGHT 0 nights avg -",
            "all: 2 nights with feeling avg 3.5 · review thresholds from 20 (18 to go)",
        ])
        let enough = (0..<FeelingByReason.reviewNights).map { night($0, .awake, 5) }
        XCTAssertEqual(FeelingByReason.lines(enough).last,
                       "all: 20 nights with feeling avg 5.0 · 20 reached, review thresholds")
    }

    func testNoFeelingNoSection() {
        XCTAssertEqual(FeelingByReason.lines([]), [])
        XCTAssertEqual(FeelingByReason.lines([night(0, .lightSleep, nil)]), [])
    }
}
