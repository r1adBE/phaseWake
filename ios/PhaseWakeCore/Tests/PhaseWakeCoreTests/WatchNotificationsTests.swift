import XCTest
import PhaseWakeCore

/// Stand der Mitteilungen auf der Uhr im Nacht-Check (`WatchNotifications`): Warnung bei fehlender Erlaubnis, Hinweis,
/// wenn die Uhr ihn seit über 3 Tagen nicht mehr geprüft hat. Nur Apple.
final class WatchNotificationsTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)
    private let day: TimeInterval = 24 * 3600

    func testProblem() {
        XCTAssertFalse(WatchNotifications.allowed.isProblem)
        XCTAssertTrue(WatchNotifications.blocked.isProblem)
        XCTAssertTrue(WatchNotifications.notTimeSensitive.isProblem)
    }

    func testOutdatedAfterThreeDays() {
        XCTAssertFalse(WatchNotifications.isOutdated(checkedAt: now.addingTimeInterval(-day), now: now))
        XCTAssertFalse(WatchNotifications.isOutdated(checkedAt: now.addingTimeInterval(-3 * day), now: now), "genau 3 Tage: noch nicht")
        XCTAssertTrue(WatchNotifications.isOutdated(checkedAt: now.addingTimeInterval(-3 * day - 1), now: now))
        XCTAssertTrue(WatchNotifications.isOutdated(checkedAt: now.addingTimeInterval(-10 * day), now: now))
    }

    func testOlderWatchWithoutTimeGivesNoHint() {
        XCTAssertFalse(WatchNotifications.isOutdated(checkedAt: nil, now: now))
    }

    func testFutureTimeGivesNoHint() {
        XCTAssertFalse(WatchNotifications.isOutdated(checkedAt: now.addingTimeInterval(3600), now: now))
    }
}

/// Gespeicherter Stand der Uhr (`WatchNotificationsCheck`): übersteht einen Neustart und geht in jeden Kontext.
final class WatchNotificationsCheckTests: XCTestCase {
    func testStoredRoundTrip() {
        let check = WatchNotificationsCheck(state: .notTimeSensitive, checkedAtMs: 1_800_000_000_000)
        XCTAssertEqual(WatchNotificationsCheck(stored: check.stored), check)
        // So kommt es aus der Property-List-Datei zurück.
        XCTAssertEqual(WatchNotificationsCheck(stored: check.stored.map { NSNumber(value: $0) } as NSArray), check)
    }

    func testUnreadableStoredGivesNil() {
        XCTAssertNil(WatchNotificationsCheck(stored: nil))
        XCTAssertNil(WatchNotificationsCheck(stored: "x"))
        XCTAssertNil(WatchNotificationsCheck(stored: [Int64(1)]))
        XCTAssertNil(WatchNotificationsCheck(stored: [Int64(9), 1]), "unbekannter Stand")
    }

    func testApplyWritesBothKeys() {
        var context: [String: Any] = [SyncKeys.updatedAt: Int64(5)]
        WatchNotificationsCheck.apply(WatchNotificationsCheck(state: .blocked, checkedAtMs: 42), to: &context)
        XCTAssertEqual(context[SyncKeys.watchNotifications] as? Int64, WatchNotifications.blocked.rawValue)
        XCTAssertEqual(context[SyncKeys.watchNotificationsAt] as? Int64, 42)
        XCTAssertEqual(context[SyncKeys.updatedAt] as? Int64, 5, "übrige Schlüssel bleiben")
    }

    func testApplyWithoutCheckRemovesBothKeys() {
        var context: [String: Any] = [SyncKeys.watchNotifications: Int64(1), SyncKeys.watchNotificationsAt: Int64(42)]
        WatchNotificationsCheck.apply(nil, to: &context)
        XCTAssertNil(context[SyncKeys.watchNotifications])
        XCTAssertNil(context[SyncKeys.watchNotificationsAt])
    }

    func testCheckedAtInMilliseconds() {
        let check = WatchNotificationsCheck(state: .allowed, checkedAt: Date(timeIntervalSince1970: 1_800_000_000.5))
        XCTAssertEqual(check.checkedAtMs, 1_800_000_000_500)
    }
}
