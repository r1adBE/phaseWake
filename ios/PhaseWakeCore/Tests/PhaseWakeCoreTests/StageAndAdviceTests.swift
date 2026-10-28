import XCTest
import PhaseWakeCore

final class StageAndAdviceTests: XCTestCase {
    private let t0 = Date(timeIntervalSince1970: 1_793_163_600)
    private func at(_ min: Double) -> Date { t0.addingTimeInterval(min * 60) }

    func testStageCheck() {
        let spans = [StageSpan(start: at(0), end: at(20), stage: .deep), StageSpan(start: at(20), end: at(35), stage: .light), StageSpan(start: at(35), end: at(50), stage: .rem)]
        XCTAssertEqual(StageCheck.wokeWell(spans, wokeAt: at(10)), false)
        XCTAssertEqual(StageCheck.wokeWell(spans, wokeAt: at(25)), true)
        XCTAssertEqual(StageCheck.wokeWell(spans, wokeAt: at(51)), true)
        XCTAssertNil(StageCheck.wokeWell(spans, wokeAt: at(60)))
        let clipped = StageCheck.clip(spans.reversed(), from: at(15), to: at(40))
        XCTAssertEqual(clipped.map(\.stage), [.deep, .light, .rem])
        XCTAssertEqual(clipped.first?.start, at(15))
    }

    func testWindowAdvice() throws {
        let spec = try AlarmSpec(id: "a", hour: 7, minute: 0, windowMinutes: 15)
        func night(_ day: Double, _ reason: WakeReason, _ id: String = "a") -> NightRecord {
            let s = t0.addingTimeInterval(day * 86_400)
            return NightRecord(alarmId: id, windowStart: s, target: s.addingTimeInterval(900), wokeAt: s.addingTimeInterval(900), reason: reason, snoozes: 0, epochs: [])
        }
        let history = [night(0, .deadline), night(1, .lightSleep), night(2, .deadline)]
        XCTAssertEqual(WindowAdvice.suggest(spec, history: history)?.windowMinutes, 25)
        XCTAssertNil(WindowAdvice.suggest(spec, history: Array(history.prefix(2))))
        XCTAssertNil(WindowAdvice.suggest(spec, history: history.map { night(0, .deadline, "b") }))
    }
}
