import PhaseWakeCore
import WatchKit
import XCTest
@testable import PhaseWakeWatch

/// Welche Sitzung die Nacht übernimmt, Apple Watch: gemerkte Sitzung nach einem Neustart der App, Wecker am iPhone
/// geändert, fremde und verwaiste Sitzungen.
final class SessionTakeoverWatchTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_793_000_000)

    private func minutes(_ value: Double) -> Date { now.addingTimeInterval(value * 60) }

    private func plan(_ id: String = "d", targetIn seconds: TimeInterval) -> WakePlan {
        WakePlan(alarmId: id, windowStart: now.addingTimeInterval(seconds - 1800),
                 target: now.addingTimeInterval(seconds))
    }

    func testRestartedAppKeepsTheScheduledSession() throws {
        let night = plan(targetIn: 8 * 3600)
        let start = night.target.addingTimeInterval(-1500)
        let saved = NightRules.ScheduledSession(alarmId: "d", target: night.target, start: start)
        let decoded = try XCTUnwrap(NightRules.ScheduledSession.decode(saved.encoded))
        XCTAssertEqual(decoded, saved)
        XCTAssertTrue(NightRules.keepsScheduled(decoded, plan: night, start: start, now: now, tolerance: 120))
        // Bis kurz nach dem Start (Spielraum des Systems), danach nicht mehr.
        XCTAssertTrue(NightRules.keepsScheduled(decoded, plan: night, start: start,
                                                now: start.addingTimeInterval(119), tolerance: 120))
        XCTAssertFalse(NightRules.keepsScheduled(decoded, plan: night, start: start,
                                                 now: start.addingTimeInterval(120), tolerance: 120))
    }

    func testOtherPlanOrNoSavedSessionIsNotKept() {
        let night = plan(targetIn: 8 * 3600)
        let start = night.target.addingTimeInterval(-1500)
        let saved = NightRules.ScheduledSession(alarmId: "d", target: night.target, start: start)
        XCTAssertFalse(NightRules.keepsScheduled(nil, plan: night, start: start, now: now, tolerance: 120))
        XCTAssertFalse(NightRules.keepsScheduled(saved, plan: plan("x", targetIn: 8 * 3600), start: start, now: now,
                                                 tolerance: 120))
        XCTAssertFalse(NightRules.keepsScheduled(saved, plan: plan(targetIn: 8 * 3600 + 60), start: start, now: now,
                                                 tolerance: 120))
        XCTAssertFalse(NightRules.keepsScheduled(saved, plan: night, start: start.addingTimeInterval(60), now: now,
                                                 tolerance: 120))
    }

    func testBrokenSavedSessionIsIgnored() {
        XCTAssertNil(NightRules.ScheduledSession.decode(nil))
        XCTAssertNil(NightRules.ScheduledSession.decode(
            ["alarm": "d", "target": 9.2e18, "start": 1.0] as [String: Any]
        ))
        XCTAssertNil(NightRules.ScheduledSession.decode(
            ["alarm": "d", "target": Double.nan, "start": 1.0] as [String: Any]
        ))
        XCTAssertNil(NightRules.ScheduledSession.decode(
            ["target": 1_800_000_000.0, "start": 1_800_000_000.0] as [String: Any]
        ))
    }

    /// Wecker 07:00 (Fenster 30 Min, Sitzung ab 06:35) am iPhone auf 06:50 verschoben: Die geplante Sitzung übernimmt
    /// die Nacht (wie `startNight` prüft) statt verworfen zu werden. Eine spätere Zielzeit deckt sie nicht.
    func testScheduledSessionServesEarlierChangedTarget() {
        func plan(target: Date) -> WakePlan {
            WakePlan(alarmId: "d", windowStart: target.addingTimeInterval(-1800), target: target)
        }
        func sessionStart(_ plan: WakePlan) -> Date { max(plan.windowStart, plan.target.addingTimeInterval(-1500)) }
        let old = plan(target: minutes(7 * 60))
        let start = sessionStart(old)
        XCTAssertEqual(start, minutes(7 * 60 - 25))
        func serves(_ new: WakePlan) -> Bool {
            NightRules.sessionServes(start: start, plan: new, sessionStart: sessionStart(new), tolerance: 120,
                                     delay: 60)
        }
        XCTAssertTrue(serves(plan(target: minutes(6 * 60 + 50))))
        XCTAssertTrue(serves(old))
        // Später (07:30): Sitzung endete vor der Zielzeit; viel früher (05:00): Zielzeit schon vorbei.
        XCTAssertFalse(serves(plan(target: minutes(7 * 60 + 30))))
        XCTAssertFalse(serves(plan(target: minutes(5 * 60))))
        // Grenze: Start bis 2 Min vor dem Sitzungsstart der neuen Nacht.
        XCTAssertTrue(serves(plan(target: minutes(7 * 60 + 2))))
        XCTAssertFalse(serves(plan(target: minutes(7 * 60 + 3))))
    }

    /// Startet eine fremde Sitzung (aus der Zeit vor einem Neustart der App), bleibt die eigene geplante oder laufende.
    func testOwnSessionIsKeptAgainstIncomingOne() {
        XCTAssertTrue(SessionRules.keepsOwn(.scheduled))
        XCTAssertTrue(SessionRules.keepsOwn(.running))
        XCTAssertFalse(SessionRules.keepsOwn(.invalid))
        XCTAssertFalse(SessionRules.keepsOwn(.notStarted))
        XCTAssertFalse(SessionRules.keepsOwn(nil))
    }

    /// Startet eine verwaiste Sitzung, die die Nacht bedienen kann, und läuft keine eigene: Sie übernimmt (misst und
    /// weckt mit Vibration), statt ohne Vibration zu enden. Läuft die eigene schon, misst oder klingelt eine Nacht oder
    /// gibt es keine passende: wie bisher beenden.
    func testOrphanSessionTakesOverTheNight() {
        XCTAssertTrue(SessionRules.usesOrphan(own: nil, busy: false, serves: true))
        XCTAssertTrue(SessionRules.usesOrphan(own: .scheduled, busy: false, serves: true))
        XCTAssertTrue(SessionRules.usesOrphan(own: .invalid, busy: false, serves: true))
        XCTAssertFalse(SessionRules.usesOrphan(own: .running, busy: false, serves: true))
        XCTAssertFalse(SessionRules.usesOrphan(own: nil, busy: true, serves: true))
        XCTAssertFalse(SessionRules.usesOrphan(own: nil, busy: false, serves: false))
    }
}
