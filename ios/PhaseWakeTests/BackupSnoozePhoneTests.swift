import PhaseWakeCore
import XCTest
@testable import PhaseWake

/// Schlummern der Sicherung in der App, iPhone (Herkunft: Prüfrunde 2 B-9).
@MainActor
final class BackupSnoozePhoneTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_793_000_000)

    /// B-9: Die App zeigt das Schlummern der Sicherung nur, solange es bevorsteht und eine Sicherung schlummert.
    func testBackupSnoozeShownUntilItRingsAgain() {
        let until = now.addingTimeInterval(300)
        XCTAssertEqual(BackupAlarm.snoozedUntil(id: UUID(), until: until.timeIntervalSince1970, now: now), until)
        XCTAssertNil(BackupAlarm.snoozedUntil(id: nil, until: until.timeIntervalSince1970, now: now))
        XCTAssertNil(BackupAlarm.snoozedUntil(id: UUID(), until: 0, now: now))
        XCTAssertNil(BackupAlarm.snoozedUntil(id: UUID(), until: until.timeIntervalSince1970, now: until))
    }
}
