import PhaseWakeCore
import WatchKit
import XCTest
@testable import PhaseWakeWatch

/// Sitzung je nach Zustand der Nacht, Apple Watch (``SessionRules``): ankommende Sitzung, Start, Neuplanen nach einer
/// Reise und was ein Sitzungsende heisst – auch «Stopp» im Weckhinweis des Systems (docs/OFFEN.md 11u).
final class SessionPhaseWatchTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_793_000_000)

    private var night: WakePlan {
        WakePlan(alarmId: "d", windowStart: now.addingTimeInterval(-1200), target: now.addingTimeInterval(600))
    }

    /// Alle Zustände der Nacht, je einmal.
    private var phases: [SmartAlarm.Phase] {
        [.idle, .waiting(night), .scheduled(night), .measuring(night), .ringing(.deadline, snoozesLeft: 3),
         .snoozed(until: now, snoozesLeft: 2)]
    }

    /// Eine gemerkte Sitzung übernimmt nichts (die eigene wird nicht gefragt); die eigene geplante oder laufende
    /// bleibt; eine beendete eigene wird ersetzt; ohne eigene wird die ankommende übernommen.
    func testIncomingSessionKeepsOwnScheduledOrRunning() {
        var asked = false
        func own() -> WKExtendedRuntimeSessionState? {
            asked = true
            return .running
        }
        XCTAssertEqual(SessionRules.adoption(stale: true, own: own()), .stale)
        XCTAssertFalse(asked)
        XCTAssertEqual(SessionRules.adoption(stale: false, own: nil), .take)
        XCTAssertEqual(SessionRules.adoption(stale: false, own: .scheduled), .keepOwn)
        XCTAssertEqual(SessionRules.adoption(stale: false, own: .running), .keepOwn)
        XCTAssertEqual(SessionRules.adoption(stale: false, own: .invalid), .replaceOwn)
        XCTAssertEqual(SessionRules.adoption(stale: false, own: .notStarted), .replaceOwn)
    }

    /// Start der eigenen Sitzung: nach dem Schlummern gleich wecken, beim Messen oder Klingeln nichts, sonst die Nacht
    /// beginnen.
    @MainActor
    func testStartedSessionRingsAfterSnoozeOtherwiseStartsNight() {
        let expected: [SessionRules.Started] = [.startNight, .startNight, .startNight, .alreadyRunning,
                                                .alreadyRunning, .ringAgain]
        XCTAssertEqual(phases.map { SessionRules.started($0) }, expected)
        XCTAssertEqual(phases.map { SessionRules.busy($0) }, [false, false, false, true, true, true])
    }

    /// Nach einer Reise hält die Uhr die geplante oder wartende Nacht; nach einem Neustart (`idle`) die gemerkte.
    @MainActor
    func testPlannedNightOnlyWhenScheduledOrWaiting() {
        XCTAssertEqual(phases.map { SessionRules.plannedNight($0) }, [nil, night, night, nil, nil, nil])
    }

    func testAfterReplanEndsSessionThatNoLongerFits() {
        XCTAssertEqual(SessionRules.replanned(scheduled: true, sameSession: true), .proceed)
        XCTAssertEqual(SessionRules.replanned(scheduled: false, sameSession: true), .endSession)
        XCTAssertEqual(SessionRules.replanned(scheduled: true, sameSession: false), .done)
        XCTAssertEqual(SessionRules.replanned(scheduled: false, sameSession: false), .done)
    }

    /// «Stopp» im Weckhinweis des Systems (Ende ohne Grund beim Klingeln) beendet die Nacht ganz wie Stopp auf der Uhr;
    /// jeder andere Grund weckt weiter.
    @MainActor
    func testSystemStopWhileRingingEndsTheNight() {
        let ringing = SmartAlarm.Phase.ringing(.lightSleep, snoozesLeft: 2)
        let stopped = SessionRules.ending(.none, ringing: ringing.isRinging)
        XCTAssertEqual(SessionRules.afterEnd(ringing, ending: stopped), .dismiss)
        let expired = SessionRules.ending(.expired, ringing: ringing.isRinging)
        XCTAssertEqual(SessionRules.afterEnd(ringing, ending: expired), .finishInterrupted)
        let error = SessionRules.ending(.error, ringing: ringing.isRinging)
        XCTAssertEqual(SessionRules.afterEnd(ringing, ending: error), .finishInterrupted)
    }

    /// Ende in den übrigen Zuständen: Schlummern weckt weiter, eine geplante Sitzung wartet auf den Rückfall, eine
    /// Messung gilt als abgebrochen, ohne Sitzung im Einsatz nichts – auch ein reguläres Ende ist dort nie ein Stopp.
    @MainActor
    func testEndOutsideRingingNeverDismisses() {
        for reason in [WKExtendedRuntimeSessionInvalidationReason.none, .expired, .error] {
            let ends = phases.map { phase in
                SessionRules.afterEnd(phase, ending: SessionRules.ending(reason, ringing: phase.isRinging))
            }
            let expected: [SessionRules.AfterEnd] = [
                .nothing, .nothing, .wait(night), .measuringInterrupted(night),
                reason == .none ? .dismiss : .finishInterrupted, .finishInterrupted,
            ]
            XCTAssertEqual(ends, expected, "\(reason.rawValue)")
        }
        // Auch wenn das Ende fälschlich als Stopp gälte: beim Schlummern weiterwecken.
        XCTAssertEqual(SessionRules.afterEnd(.snoozed(until: now, snoozesLeft: 1), ending: .dismissed),
                       .finishInterrupted)
    }
}
