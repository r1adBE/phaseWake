import PhaseWakeCore
import XCTest
@testable import PhaseWake

/// «Verlauf löschen», iPhone: spät zugestellte Nächte fallen weg, der Zeitpunkt liegt nie in der Zukunft (Herkunft:
/// Vollaudit 11.11.2026 D-02/AP-6; Vollaudit 071597f DA-1).
final class HistoryClearPhoneTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_793_000_000)

    func testLateNightBeforeHistoryClearIsDropped() {
        let cleared = Date(timeIntervalSince1970: 1_793_000_000)
        XCTAssertFalse(HistoryClear.keeps(end: cleared.addingTimeInterval(-3600), clearedAt: cleared))
        XCTAssertTrue(HistoryClear.keeps(end: cleared.addingTimeInterval(60), clearedAt: cleared))
        XCTAssertTrue(HistoryClear.keeps(end: cleared.addingTimeInterval(-3600), clearedAt: nil))
    }

    func testHistoryClearedAtIsNeverInTheFuture() {
        let nowMs = TimeBounds.millis(now)
        XCTAssertEqual(HistoryClear.clamped(ms: nowMs - 1000, now: now), nowMs - 1000)
        XCTAssertEqual(HistoryClear.clamped(ms: nowMs + 86_400_000, now: now), nowMs)
        XCTAssertNil(HistoryClear.clamped(ms: Int64.max, now: now))
        XCTAssertNil(HistoryClear.clamped(ms: -1, now: now))
        XCTAssertNil(HistoryClear.clamped(ms: nil, now: now))
    }
}
