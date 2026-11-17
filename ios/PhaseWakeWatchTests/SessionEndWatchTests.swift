import PhaseWakeCore
import WatchKit
import XCTest
@testable import PhaseWakeWatch

/// Ende der Sitzung auf der Apple Watch: Ende durch das System beim Klingeln, Beenden geplanter Sitzungen und Vibration,
/// Abbruch beim Messen, alter Klingel-Zustand nach einem Neustart, «stop» vom iPhone ohne Klingeln (Herkunft: Vollaudit
/// 11.11.2026 AP-1, AP-3, AP-4; Vollaudit 071597f AP2-5, AP2-9).
final class SessionEndWatchTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_793_000_000)

    private func plan(_ id: String = "d", targetIn seconds: TimeInterval) -> WakePlan {
        WakePlan(alarmId: id, windowStart: now.addingTimeInterval(seconds - 1800), target: now.addingTimeInterval(seconds))
    }

    func testSystemStopWhileRingingCountsAsDismiss() {
        XCTAssertEqual(SessionRules.ending(.none, ringing: true), .dismissed)
        for reason: WKExtendedRuntimeSessionInvalidationReason in [.expired, .error, .sessionInProgress, .resignedFrontmost] {
            XCTAssertEqual(SessionRules.ending(reason, ringing: true), .interrupted, "\(reason.rawValue)")
        }
        // Unbekannter oder künftiger Grund (z. B. vom System unterdrückt): Abbruch, nie Stopp.
        if let suppressed = WKExtendedRuntimeSessionInvalidationReason(rawValue: 4) {
            XCTAssertEqual(SessionRules.ending(suppressed, ringing: true), .interrupted)
        }
    }

    func testRegularEndOutsideRingingStaysInterruption() {
        XCTAssertEqual(SessionRules.ending(.none, ringing: false), .interrupted)
        XCTAssertEqual(SessionRules.ending(.expired, ringing: false), .interrupted)
    }

    func testReasonNameCarriesRawValue() {
        XCTAssertEqual(SessionRules.reasonName(.none), "none/\(WKExtendedRuntimeSessionInvalidationReason.none.rawValue)")
        XCTAssertTrue(SessionRules.reasonName(.expired).hasPrefix("expired/"))
        XCTAssertTrue(SessionRules.reasonName(.error).hasPrefix("error/"))
    }

    func testScheduledSessionIsEndedOnlyWhenActive() {
        XCTAssertEqual(SessionRules.retire(.scheduled, active: true), .now)
        XCTAssertEqual(SessionRules.retire(.scheduled, active: false), .whenActive)
        XCTAssertEqual(SessionRules.retire(.running, active: true), .now)
        XCTAssertEqual(SessionRules.retire(.running, active: false), .tryNowAndKeep)
        XCTAssertEqual(SessionRules.retire(.notStarted, active: false), .now)
        XCTAssertEqual(SessionRules.retire(.invalid, active: false), .drop)
    }

    func testHapticsStopWhenSessionNoLongerRings() {
        XCTAssertEqual(SessionRules.hapticStep(elapsed: 500, ringing: false).interval, 0)
        let first = SessionRules.hapticStep(elapsed: 10, ringing: true)
        XCTAssertEqual(first.type, .notification)
        XCTAssertEqual(first.interval, 3)
        let second = SessionRules.hapticStep(elapsed: 90, ringing: true)
        XCTAssertEqual(second.type, .retry)
        XCTAssertEqual(second.interval, 2)
        let third = SessionRules.hapticStep(elapsed: 200, ringing: true)
        XCTAssertEqual(third.type, .failure)
        XCTAssertEqual(third.interval, 1.5)
    }

    func testInterruptedMeasuringWakesOnlyFromTarget() {
        let target = now.addingTimeInterval(600)
        // Vor der Zielzeit nicht sofort (und nicht zusätzlich 60 s später): Es weckt der feste Rückfall.
        XCTAssertNil(SessionRules.interruptedWake(now: now, target: target))
        XCTAssertEqual(SessionRules.interruptedWake(now: target, target: target), target)
        XCTAssertEqual(SessionRules.interruptedWake(now: target.addingTimeInterval(5), target: target), target.addingTimeInterval(5))
    }

    func testPhoneStopEndsTheMatchingNightOnly() {
        let night = plan(targetIn: 60)
        // Fenster läuft, gleiche Nacht: erledigt.
        XCTAssertEqual(NightRules.phoneStoppedNight([night], alarmId: "d", target: night.target, now: now, tolerance: 120), night)
        // Ausstehende Nacht als zweiter Kandidat.
        XCTAssertEqual(NightRules.phoneStoppedNight([nil, night], alarmId: "d", target: night.target.addingTimeInterval(0.4),
                                                    now: now, tolerance: 120), night)
        // Andere Nacht, anderer Wecker, ohne Angaben (ältere iPhone-App) oder Fenster noch nicht begonnen: nichts.
        XCTAssertNil(NightRules.phoneStoppedNight([night], alarmId: "d", target: night.target.addingTimeInterval(86400), now: now, tolerance: 120))
        XCTAssertNil(NightRules.phoneStoppedNight([night], alarmId: "x", target: night.target, now: now, tolerance: 120))
        XCTAssertNil(NightRules.phoneStoppedNight([night], alarmId: nil, target: night.target, now: now, tolerance: 120))
        XCTAssertNil(NightRules.phoneStoppedNight([night], alarmId: "d", target: nil, now: now, tolerance: 120))
        let tomorrow = plan(targetIn: 86400)
        XCTAssertNil(NightRules.phoneStoppedNight([tomorrow], alarmId: "d", target: tomorrow.target, now: now, tolerance: 120))
    }

    func testOldRingStateFinishesWithoutWaking() {
        let target = now.addingTimeInterval(-3 * 3600)
        XCTAssertEqual(NightRules.restored(target: target, snoozedUntil: nil, now: now, staleAfter: 7200, tolerance: 120), .finishSilently)
        XCTAssertEqual(NightRules.restored(target: target, snoozedUntil: now.addingTimeInterval(60), now: now, staleAfter: 7200,
                                           tolerance: 120), .finishSilently)
        let recent = now.addingTimeInterval(-600)
        XCTAssertEqual(NightRules.restored(target: recent, snoozedUntil: nil, now: now, staleAfter: 7200, tolerance: 120), .finishWaking)
        XCTAssertEqual(NightRules.restored(target: recent, snoozedUntil: now.addingTimeInterval(60), now: now, staleAfter: 7200,
                                           tolerance: 120), .resume)
        XCTAssertEqual(NightRules.restored(target: recent, snoozedUntil: now.addingTimeInterval(-300), now: now, staleAfter: 7200,
                                           tolerance: 120), .finishWaking)
    }
}
