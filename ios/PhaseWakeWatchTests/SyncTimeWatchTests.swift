import PhaseWakeCore
import XCTest
@testable import PhaseWakeWatch

/// Zeiten in Nachrichten zwischen iPhone und Uhr, Apple Watch (Herkunft: Vollaudit 071597f AP2-8).
final class SyncTimeWatchTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_793_000_000)

    func testMessageTimesNeverTrap() {
        XCTAssertNil(SyncTime.date(Int64.max))
        XCTAssertNil(SyncTime.date(Int64.min))
        XCTAssertNil(SyncTime.date("1"))
        XCTAssertEqual(SyncTime.date(Int64(1_793_000_000_000)), now)
        XCTAssertTrue(SyncTime.stale(Int64.min, now: now))
        XCTAssertTrue(SyncTime.stale(Int64.max, now: now))
        XCTAssertTrue(SyncTime.stale(nil, now: now))
        XCTAssertFalse(SyncTime.stale(TimeBounds.millis(now) - 1000, now: now))
        XCTAssertTrue(SyncTime.stale(TimeBounds.millis(now) - SyncKeys.maxRingAgeMs - 1, now: now))
        XCTAssertNil(RingNight(alarmId: "d", target: Date(timeIntervalSince1970: 1e300)).targetMs.flatMap(TimeBounds.checked))
    }
}
