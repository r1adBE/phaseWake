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
