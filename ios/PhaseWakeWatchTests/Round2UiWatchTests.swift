import PhaseWakeCore
import XCTest
@testable import PhaseWakeWatch

/// Prüfrunde 2 (Bedienung), Apple Watch: «Gute Nacht» mit «Tippen zum Abbrechen» und Grund, wenn eine Nacht läuft (B-7),
/// übersprungener Termin in der Wecker-Zeile (B-8), Editor nach Änderungen am iPhone (B-3, B-11), Stopp-Knopf 44 pt
/// (B-16). Gegenstücke auf der Galaxy Watch: `WatchSurfacesTest`, `GoodNightButton`.
@MainActor
final class Round2UiWatchTests: XCTestCase {
    private var zurich: Calendar {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "Europe/Zurich")!
        return c
    }

    /// Ortszeit Zürich «yyyy-MM-ddTHH:mm».
    private func at(_ text: String) -> Date {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "Europe/Zurich")!
        f.dateFormat = "yyyy-MM-dd'T'HH:mm"
        return f.date(from: text)!
    }

    func testGoodNightLineSaysTapToCancelAndWhyItIsOff() {
        let target = at("2026-10-29T06:45")
        let active = GoodNightRow.secondary(target: target, busy: false, tooFar: false, minutes: 450)
        XCTAssertTrue(active.hasPrefix(L("notification_window_text", Format.time(target))), active)
        XCTAssertTrue(active.hasSuffix(L("home_good_night_tap_cancel")), active)
        XCTAssertEqual(GoodNightRow.secondary(target: nil, busy: true, tooFar: false, minutes: 450), L("transient_alarm_running"))
        XCTAssertEqual(GoodNightRow.secondary(target: nil, busy: false, tooFar: true, minutes: 450), L("home_good_night_too_far"))
    }

    func testAlarmRowShowsSkippedOccurrence() throws {
        let evening = at("2026-10-28T22:00")
        let alarm = try AlarmSpec(id: "a", hour: 7, minute: 0, days: [.monday, .tuesday, .wednesday, .thursday, .friday], label: "Arbeit")
        let skipped = AlarmSchedule.skipNext(alarm, now: evening, calendar: zurich)
        let line = alarmDetails(skipped, now: evening, calendar: zurich)
        XCTAssertTrue(line.hasSuffix(" · " + L("alarm_skipped", Format.shortDay("2026-10-29"))), line)
        XCTAssertTrue(line.contains("Arbeit"), line)
        XCTAssertFalse(alarmDetails(alarm, now: evening, calendar: zurich).contains(L("alarm_skipped", Format.shortDay("2026-10-29"))))
    }

    func testEditorSaveAfterChangesOnPhone() {
        let eight = (1...8).map { "a\($0)" }
        XCTAssertEqual(EditorSave.check(existed: true, storedIds: eight.filter { $0 != "a1" }, draftId: "a1", maxAlarms: WatchModel.maxAlarms), .gone)
        XCTAssertEqual(EditorSave.check(existed: false, storedIds: eight, draftId: "new", maxAlarms: WatchModel.maxAlarms), .full)
        XCTAssertEqual(EditorSave.check(existed: true, storedIds: eight, draftId: "a1", maxAlarms: WatchModel.maxAlarms), .save)
    }

    func testStopButtonMeetsMinimumTarget() {
        XCTAssertGreaterThanOrEqual(RingView.stopMinHeight, 44)
    }
}
