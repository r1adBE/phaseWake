import PhaseWakeCore
import XCTest
@testable import PhaseWakeWatch

/// Aufzeichnung auf der Apple Watch ([LearnRecorder], Audit F-03): Ende der Auswertung (von Hand gestoppt, «Nacht
/// aufzeichnen» nach 12 Std., Lernphase beim Wecken, ohne Wecken die Zielzeit; nie nach dem Ende des Rekorders), Grund
/// des Endes und sein Text im Diagnose-Protokoll (wie Android).
final class LearnRecorderTests: XCTestCase {
    private let from = Date(timeIntervalSince1970: 1_793_000_000)
    private var target: Date { from.addingTimeInterval(8 * 3600) }
    private var until: Date { from.addingTimeInterval(9 * 3600) }

    private func mark(manual: Bool? = nil, wokeAt: Date? = nil, stoppedAt: Date? = nil, stopReason: String? = nil) -> LearnRecorder.Mark {
        LearnRecorder.Mark(alarmId: "a", target: target, from: from, until: until, wokeAt: wokeAt, motions: [], manual: manual,
                           stoppedAt: stoppedAt, stopReason: stopReason)
    }

    func testEndOfTheLearningNight() {
        // Ohne Wecken: die Zielzeit; beim Wecken dann, höchstens bis zum Ende des Rekorders.
        XCTAssertEqual(LearnRecorder.end(of: mark()), target)
        XCTAssertEqual(LearnRecorder.end(of: mark(wokeAt: target.addingTimeInterval(300))), target.addingTimeInterval(300))
        XCTAssertEqual(LearnRecorder.end(of: mark(wokeAt: until.addingTimeInterval(600))), until)
    }

    func testEndOfARecordingByHand() {
        // «Nacht aufzeichnen»: Ende des Rekorders (12 Std.); von Hand oder beim Wecken gestoppt: dann.
        XCTAssertEqual(LearnRecorder.end(of: mark(manual: true)), until)
        let stopped = from.addingTimeInterval(3 * 3600)
        XCTAssertEqual(LearnRecorder.end(of: mark(manual: true, stoppedAt: stopped, stopReason: RecordEnd.wake.rawValue)), stopped)
        XCTAssertEqual(LearnRecorder.end(of: mark(manual: true, stoppedAt: until.addingTimeInterval(60))), until)
    }

    func testReasonOfTheEnd() {
        XCTAssertEqual(LearnRecorder.reason(of: mark()), .wake)
        XCTAssertEqual(LearnRecorder.reason(of: mark(manual: true)), .cap)
        XCTAssertEqual(LearnRecorder.reason(of: mark(manual: true, stoppedAt: from, stopReason: "battery")), .battery)
        XCTAssertEqual(LearnRecorder.reason(of: mark(manual: true, stoppedAt: from, stopReason: "wake")), .wake)
    }

    @MainActor
    func testReasonTextLikeAndroid() {
        XCTAssertEqual(LearnRecorder.reasonText(.watch, battery: nil), "manual watch")
        XCTAssertEqual(LearnRecorder.reasonText(.phone, battery: nil), "manual phone")
        XCTAssertEqual(LearnRecorder.reasonText(.cap, battery: nil), "cap 12h")
        XCTAssertEqual(LearnRecorder.reasonText(.battery, battery: 14), "battery 14%")
        XCTAssertEqual(LearnRecorder.reasonText(.battery, battery: nil), "battery -%")
        XCTAssertEqual(LearnRecorder.reasonText(.service, battery: nil), "service ended")
        XCTAssertEqual(LearnRecorder.reasonText(.wake, battery: nil), "wake")
    }
}
