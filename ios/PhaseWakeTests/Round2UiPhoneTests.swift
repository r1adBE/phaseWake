import PhaseWakeCore
import XCTest
@testable import PhaseWake

/// Prüfrunde 2 (Bedienung), iPhone: Editor nach Änderungen anderswo (B-3, B-11), Doppeltipp in der Einrichtung (B-2),
/// Schlummern der Sicherung (B-9), Probewecker über einem Powernap (B-12). Gegenstücke auf Android:
/// `EditsElsewhereTest`, `DoubleTapTest`.
@MainActor
final class Round2UiPhoneTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_793_000_000)

    /// B-3: Ein beim Öffnen gespeicherter Wecker, der fehlt, wurde anderswo gelöscht; Speichern legte ihn sonst neu an.
    /// B-11: Neuer Wecker bei acht gespeicherten: Hinweis statt still verwerfen.
    func testEditorSaveAfterChangesElsewhere() {
        let eight = (1...8).map { "a\($0)" }
        XCTAssertEqual(EditorSave.check(existed: true, storedIds: eight, draftId: "a3", maxAlarms: 8), .save)
        XCTAssertEqual(EditorSave.check(existed: true, storedIds: eight.filter { $0 != "a3" }, draftId: "a3", maxAlarms: 8), .gone)
        XCTAssertEqual(EditorSave.check(existed: false, storedIds: eight, draftId: "new", maxAlarms: 8), .full)
        XCTAssertEqual(EditorSave.check(existed: false, storedIds: Array(eight.prefix(7)), draftId: "new", maxAlarms: 8), .save)
        XCTAssertEqual(EditorSave.check(existed: false, storedIds: Array(eight.prefix(7)) + ["new"], draftId: "new", maxAlarms: 8), .save)
    }

    /// B-2: Ein zweiter Tipp innerhalb der Sperre nach einem Seitenwechsel zählt nicht (sonst traf er «Später»).
    func testOnboardingIgnoresSecondTapRightAfterPageChange() {
        let tapped: TimeInterval = 100
        let locked = tapped + OnboardingView.tapLock
        XCTAssertTrue(OnboardingView.tapAllowed(now: tapped, lockedUntil: 0))
        XCTAssertFalse(OnboardingView.tapAllowed(now: tapped + 0.15, lockedUntil: locked))
        XCTAssertTrue(OnboardingView.tapAllowed(now: tapped + OnboardingView.tapLock, lockedUntil: locked))
        XCTAssertGreaterThanOrEqual(OnboardingView.tapLock, 0.3)
    }

    /// B-9: Die App zeigt das Schlummern der Sicherung nur, solange es bevorsteht und eine Sicherung schlummert.
    func testBackupSnoozeShownUntilItRingsAgain() {
        let until = now.addingTimeInterval(300)
        XCTAssertEqual(BackupAlarm.snoozedUntil(id: UUID(), until: until.timeIntervalSince1970, now: now), until)
        XCTAssertNil(BackupAlarm.snoozedUntil(id: nil, until: until.timeIntervalSince1970, now: now))
        XCTAssertNil(BackupAlarm.snoozedUntil(id: UUID(), until: 0, now: now))
        XCTAssertNil(BackupAlarm.snoozedUntil(id: UUID(), until: until.timeIntervalSince1970, now: until))
    }

    /// B-12: Rückfrage nur, solange ein Powernap wartet.
    func testTestAlarmAsksOnlyOverWaitingNap() {
        let nap = Transient.nap(base: nil, now: now, calendar: .gregorianLocal, minutes: 20, uuid: "n1")
        XCTAssertTrue(TestAlarmButton.napWaiting(nap, now: now.addingTimeInterval(60)))
        XCTAssertFalse(TestAlarmButton.napWaiting(nap, now: nap.plan.target.addingTimeInterval(Transient.activeAfterTarget + 60)))
        XCTAssertFalse(TestAlarmButton.napWaiting(nil, now: now))
    }
}
