import PhaseWakeCore
import XCTest
@testable import PhaseWakeWatch

/// Oberfläche der Apple Watch: «Gute Nacht» mit «Tippen zum Abbrechen» und Grund, wenn eine Nacht läuft, übersprungener
/// Termin in der Wecker-Zeile, Stopp-Knopf 44 pt, Räder 12/24 Std.
/// Gegenstücke auf der Galaxy Watch: `WatchSurfacesTest`, `GoodNightButton`.
@MainActor
final class SurfacesWatchTests: XCTestCase {
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

    func testStopButtonMeetsMinimumTarget() {
        XCTAssertGreaterThanOrEqual(RingView.stopMinHeight, 44)
    }

    func testWheelsConvertBetweenTwelveAndTwentyFourHours() {
        XCTAssertEqual(WheelClock.hour12(0), 12)
        XCTAssertEqual(WheelClock.hour12(7), 7)
        XCTAssertEqual(WheelClock.hour12(12), 12)
        XCTAssertEqual(WheelClock.hour12(23), 11)
        XCTAssertEqual(WheelClock.hour24(hour12: 12, pm: false), 0)
        XCTAssertEqual(WheelClock.hour24(hour12: 12, pm: true), 12)
        XCTAssertEqual(WheelClock.hour24(hour12: 7, pm: true), 19)
        for hour in 0..<24 {
            XCTAssertEqual(WheelClock.hour24(hour12: WheelClock.hour12(hour), pm: hour >= 12), hour)
        }
        XCTAssertTrue(WheelClock.usesTwelveHours(Locale(identifier: "en_US")))
        XCTAssertFalse(WheelClock.usesTwelveHours(Locale(identifier: "de_CH")))
        XCTAssertNotEqual(WheelClock.unitName(.hours), WheelClock.unitName(.minutes))
    }
}
