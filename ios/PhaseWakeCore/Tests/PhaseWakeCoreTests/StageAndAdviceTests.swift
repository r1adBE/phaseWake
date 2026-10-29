import XCTest
import PhaseWakeCore

/// Gleiche Fälle wie `StageCheckTest` und `WindowAdviceTest` (Kotlin).
final class StageAndAdviceTests: XCTestCase {
    /// 2026-10-28T05:00:00Z.
    private let t0 = Date(timeIntervalSince1970: 1_793_163_600)
    private func at(_ min: Double) -> Date { t0.addingTimeInterval(min * 60) }
    private var spans: [StageSpan] {
        [StageSpan(start: at(0), end: at(20), stage: .deep), StageSpan(start: at(20), end: at(35), stage: .light), StageSpan(start: at(35), end: at(50), stage: .rem)]
    }

    func testStageAtBoundaries() {
        XCTAssertEqual(StageCheck.stageAt(spans, at(0)), .deep)
        XCTAssertEqual(StageCheck.stageAt(spans, at(20)), .light)
        XCTAssertNil(StageCheck.stageAt(spans, at(50)))
    }

    func testVerdictWithTolerance() {
        XCTAssertEqual(StageCheck.wokeWell(spans, wokeAt: at(10)), false)
        XCTAssertEqual(StageCheck.wokeWell(spans, wokeAt: at(25)), true)
        // Sitzung endete 1 Min vorher.
        XCTAssertEqual(StageCheck.wokeWell(spans, wokeAt: at(51)), true)
        XCTAssertNil(StageCheck.wokeWell(spans, wokeAt: at(60)))
        XCTAssertNil(StageCheck.wokeWell([StageSpan(start: at(0), end: at(10), stage: .unknown)], wokeAt: at(5)))
    }

    func testClipToWindow() {
        let clipped = StageCheck.clip(spans.reversed(), from: at(15), to: at(40))
        XCTAssertEqual(clipped.map(\.stage), [.deep, .light, .rem])
        XCTAssertEqual(clipped.first?.start, at(15))
        XCTAssertEqual(clipped.last?.end, at(40))
    }

    // MARK: Fenster-Vorschlag

    private func spec(window: Int = 15) -> AlarmSpec { try! AlarmSpec(id: "a", hour: 7, minute: 0, windowMinutes: window) }

    private func night(_ day: Double, _ reason: WakeReason, _ id: String = "a") -> NightRecord {
        let s = t0.addingTimeInterval(day * 86_400)
        return NightRecord(alarmId: id, windowStart: s, target: s.addingTimeInterval(900), wokeAt: s.addingTimeInterval(900), reason: reason, snoozes: 0, epochs: [])
    }

    func testSuggestsWiderWindowWhenMostlyDeadline() {
        let suggestion = WindowAdvice.suggest(spec(), history: [night(0, .deadline), night(1, .lightSleep), night(2, .deadline)])
        XCTAssertEqual(suggestion?.windowMinutes, 25)
        XCTAssertEqual(suggestion?.deadlineNights, 2)
        XCTAssertEqual(suggestion?.nights, 3)
    }

    func testNoAdviceWhenFewNightsOrMostlyGood() {
        XCTAssertNil(WindowAdvice.suggest(spec(), history: [night(0, .deadline), night(1, .deadline)]))
        let mixed = [night(0, .deadline), night(1, .lightSleep), night(2, .awake), night(3, .deadline)]
        XCTAssertNil(WindowAdvice.suggest(spec(), history: mixed))
    }

    func testOnlyThisAlarmAndCappedAtMaximum() {
        XCTAssertNil(WindowAdvice.suggest(spec(), history: (0..<5).map { night(Double($0), .deadline, "b") }))
        let deadlines = (0..<3).map { night(Double($0), .deadline) }
        XCTAssertEqual(WindowAdvice.suggest(spec(window: 25), history: deadlines)?.windowMinutes, 30)
        XCTAssertNil(WindowAdvice.suggest(spec(window: 30), history: deadlines))
    }
}
