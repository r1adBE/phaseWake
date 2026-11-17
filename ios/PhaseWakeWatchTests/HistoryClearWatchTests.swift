import PhaseWakeCore
import XCTest
@testable import PhaseWakeWatch

/// «Verlauf löschen» auf der Apple Watch: nur mit Sendezeit, einmal angewandt, behält nur später Beendetes (Herkunft:
/// Vollaudit 11.11.2026 AP-6).
final class HistoryClearWatchTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_793_000_000)

    func testClearHistoryNeedsSendTimeAndAppliesOnce() {
        let sent = now
        XCTAssertNil(HistoryClear.clearUntil(sentAt: nil, applied: nil))
        XCTAssertEqual(HistoryClear.clearUntil(sentAt: sent, applied: nil), sent)
        XCTAssertNil(HistoryClear.clearUntil(sentAt: sent, applied: sent))
        XCTAssertNil(HistoryClear.clearUntil(sentAt: sent, applied: sent.addingTimeInterval(1)))
        XCTAssertEqual(HistoryClear.clearUntil(sentAt: sent.addingTimeInterval(1), applied: sent), sent.addingTimeInterval(1))
    }

    func testClearHistoryKeepsOnlyWhatEndedLater() {
        XCTAssertFalse(HistoryClear.keeps(end: now.addingTimeInterval(-60), clearedAt: now))
        XCTAssertFalse(HistoryClear.keeps(end: now, clearedAt: now))
        XCTAssertTrue(HistoryClear.keeps(end: now.addingTimeInterval(1), clearedAt: now))
        XCTAssertTrue(HistoryClear.keeps(end: .distantPast, clearedAt: nil))
    }
}
