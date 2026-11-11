import PhaseWakeCore
import XCTest
@testable import PhaseWake

/// Vollaudit 11.11.2026, iPhone: Schrift auf dem Sonnenaufgang (UI-6), «Weiter» beim ersten Wecker (UI-5), Hinweis
/// ohne Antwort der Uhr (AP-8) und «Verlauf löschen» für spät zugestellte Nächte (D-02/AP-6).
final class AuditPhoneTests: XCTestCase {
    func testSunriseTextKeepsContrastOverTheWholeProgress() {
        for step in 0...1000 {
            let p = Double(step) / 1000
            let c = SunriseSky.palette(p)
            XCTAssertGreaterThanOrEqual(SunriseSky.contrast(c.content, c.bottom), SunriseSky.minContrast, "content at \(p)")
            XCTAssertGreaterThanOrEqual(SunriseSky.contrast(c.muted, c.bottom), SunriseSky.minContrast, "muted at \(p)")
        }
    }

    func testSunriseSwitchesToDarkTextBeforeTheOldThreshold() {
        // Früher erst ab 0.635 dunkel (dort nur rund 2.5:1 für die gedämpfte Zeile): jetzt deutlich früher.
        XCTAssertEqual(SunriseSky.palette(0).content, SunriseSky.light)
        XCTAssertEqual(SunriseSky.palette(0.6).content, SunriseSky.dark)
        XCTAssertEqual(SunriseSky.palette(1).content, SunriseSky.dark)
        let first = (0...1000).first { SunriseSky.palette(Double($0) / 1000).content == SunriseSky.dark }
        XCTAssertNotNil(first)
        XCTAssertLessThan(Double(first ?? 1000) / 1000, 0.6)
    }

    func testContrastFormula() {
        XCTAssertEqual(SunriseSky.contrast(SunriseSky.RGB(hex: 0xFFFFFF), SunriseSky.RGB(hex: 0x000000)), 21, accuracy: 0.01)
        XCTAssertEqual(SunriseSky.contrast(SunriseSky.RGB(hex: 0x777777), SunriseSky.RGB(hex: 0x777777)), 1, accuracy: 0.0001)
    }

    @MainActor
    func testOnboardingNextCreatesTheShownAlarmOnce() {
        XCTAssertTrue(OnboardingView.createsOnNext(step: OnboardingView.alarmStep, created: false, hasAlarms: false, canAdd: true))
        // Schon angelegt, kein Platz mehr oder ein anderer Schritt: nichts.
        XCTAssertFalse(OnboardingView.createsOnNext(step: OnboardingView.alarmStep, created: true, hasAlarms: false, canAdd: true))
        XCTAssertFalse(OnboardingView.createsOnNext(step: OnboardingView.alarmStep, created: false, hasAlarms: false, canAdd: false))
        XCTAssertFalse(OnboardingView.createsOnNext(step: OnboardingView.alarmStep + 1, created: false, hasAlarms: false, canAdd: true))
        XCTAssertFalse(OnboardingView.createsOnNext(step: OnboardingView.alarmStep - 1, created: false, hasAlarms: false, canAdd: true))
    }

    func testRecordCommandWithoutAnswerShowsHint() {
        XCTAssertTrue(WatchNight.unanswered(pending: true, request: 2, current: 2))
        // Antwort kam (nicht mehr wartend) oder ein neuerer Befehl wartet selbst.
        XCTAssertFalse(WatchNight.unanswered(pending: false, request: 2, current: 2))
        XCTAssertFalse(WatchNight.unanswered(pending: true, request: 1, current: 2))
    }

    func testLateNightBeforeHistoryClearIsDropped() {
        let cleared = Date(timeIntervalSince1970: 1_793_000_000)
        XCTAssertFalse(HistoryClear.keeps(end: cleared.addingTimeInterval(-3600), clearedAt: cleared))
        XCTAssertTrue(HistoryClear.keeps(end: cleared.addingTimeInterval(60), clearedAt: cleared))
        XCTAssertTrue(HistoryClear.keeps(end: cleared.addingTimeInterval(-3600), clearedAt: nil))
    }
}
