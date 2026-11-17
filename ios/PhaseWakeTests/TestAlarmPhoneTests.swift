import PhaseWakeCore
import XCTest
@testable import PhaseWake

/// Probewecker, iPhone: Rückfrage nur, solange ein Powernap wartet (Herkunft: Prüfrunde 2 B-12).
@MainActor
final class TestAlarmPhoneTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_793_000_000)

    /// B-12: Rückfrage nur, solange ein Powernap wartet.
    func testTestAlarmAsksOnlyOverWaitingNap() {
        let nap = Transient.nap(base: nil, now: now, calendar: .gregorianLocal, minutes: 20, uuid: "n1")
        XCTAssertTrue(TestAlarmButton.napWaiting(nap, now: now.addingTimeInterval(60)))
        XCTAssertFalse(TestAlarmButton.napWaiting(nap, now: nap.plan.target.addingTimeInterval(Transient.activeAfterTarget + 60)))
        XCTAssertFalse(TestAlarmButton.napWaiting(nil, now: now))
    }
}
