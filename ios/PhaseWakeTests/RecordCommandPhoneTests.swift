import PhaseWakeCore
import XCTest
@testable import PhaseWake

/// Befehl «Aufzeichnen» an die Uhr, iPhone: Hinweis ohne Antwort der Uhr, nachgereichter Start.
final class RecordCommandPhoneTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_793_000_000)

    func testRecordCommandWithoutAnswerShowsHint() {
        XCTAssertTrue(WatchNight.unanswered(pending: true, request: 2, current: 2))
        // Antwort kam (nicht mehr wartend) oder ein neuerer Befehl wartet selbst.
        XCTAssertFalse(WatchNight.unanswered(pending: false, request: 2, current: 2))
        XCTAssertFalse(WatchNight.unanswered(pending: true, request: 1, current: 2))
    }

    func testUnansweredRecordStartIsResentWithinTenMinutes() {
        let tap = now.addingTimeInterval(-300)
        XCTAssertTrue(RecordRetry.resend(unanswered: true, tapAt: tap, now: now))
        XCTAssertFalse(RecordRetry.resend(unanswered: false, tapAt: tap, now: now))
        XCTAssertFalse(RecordRetry.resend(unanswered: true, tapAt: nil, now: now))
        XCTAssertFalse(RecordRetry.resend(unanswered: true, tapAt: now.addingTimeInterval(-RecordRetry.window - 1),
                                          now: now))
    }
}
