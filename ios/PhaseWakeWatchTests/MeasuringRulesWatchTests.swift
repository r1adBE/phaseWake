import PhaseWakeCore
import WatchKit
import XCTest
@testable import PhaseWakeWatch

/// Entscheide beim Messen der Apple Watch (``MeasuringRules``): passende Nacht beim Start der Sitzung, ungemessene
/// Epochen, frühe Momente samt kurzer Nacht und Einschlafzeit, Grund zur Zielzeit, geänderter Plan, Abbruch.
final class MeasuringRulesWatchTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_793_000_000)

    private func plan(_ id: String = "d", targetIn seconds: TimeInterval, exact: Bool = false) -> WakePlan {
        WakePlan(alarmId: id, windowStart: now.addingTimeInterval(seconds - 1800),
                 target: now.addingTimeInterval(seconds), exact: exact)
    }

    func testSessionStartsAtWindowButAtMostLeadBeforeTarget() {
        let wide = plan(targetIn: 3600)
        XCTAssertEqual(MeasuringRules.sessionStart(wide, lead: 1500), wide.target.addingTimeInterval(-1500))
        let narrow = WakePlan(alarmId: "d", windowStart: now, target: now.addingTimeInterval(600))
        XCTAssertEqual(MeasuringRules.sessionStart(narrow, lead: 1500), now)
    }

    /// Die gestartete Sitzung misst ihren Plan, wenn er der nächste ist oder der echte Wecker neben einem Probewecker;
    /// nach einem Neustart der App (ohne eigenen Plan) den nächsten; sonst ist sie verwaist.
    func testStartedSessionMeasuresItsOwnOrTheRealPlan() {
        let next = plan(targetIn: 600)
        let real = plan("r", targetIn: 900)
        let probe = plan(Transient.testPrefix + "1", targetIn: 120)
        XCTAssertEqual(MeasuringRules.matchingNight(own: next, next: next, real: nil), next)
        XCTAssertEqual(MeasuringRules.matchingNight(own: real, next: probe, real: real), real)
        XCTAssertEqual(MeasuringRules.matchingNight(own: nil, next: next, real: real), next)
        // Wecker verschoben oder ausgeschaltet: verwaist.
        XCTAssertNil(MeasuringRules.matchingNight(own: plan("x", targetIn: 600), next: next, real: real))
        XCTAssertNil(MeasuringRules.matchingNight(own: next, next: nil, real: nil))
        XCTAssertNil(MeasuringRules.matchingNight(own: nil, next: nil, real: real))
    }

    func testSessionMeasuresOnlyAroundItsWindowUnlessCaughtUp() {
        let night = plan(targetIn: 600)
        let start = night.target.addingTimeInterval(-1500)
        func measures(_ time: Date, catchUp: Bool = false) -> Bool {
            MeasuringRules.measures(night, catchUp: catchUp, now: time, sessionStart: start, tolerance: 120, delay: 60)
        }
        XCTAssertTrue(measures(start))
        // Spielraum des Systems vor dem Start, Frist nach der Zielzeit.
        XCTAssertTrue(measures(start.addingTimeInterval(-120)))
        XCTAssertFalse(measures(start.addingTimeInterval(-121)))
        XCTAssertTrue(measures(night.target.addingTimeInterval(59)))
        XCTAssertFalse(measures(night.target.addingTimeInterval(60)))
        // Nachgeholt (verpasster Wecker, ausstehende Nacht): auch lange nach der Zielzeit, aber nie vor dem Start.
        XCTAssertTrue(measures(night.target.addingTimeInterval(3600), catchUp: true))
        XCTAssertFalse(measures(start.addingTimeInterval(-600), catchUp: true))
        let missed = plan(targetIn: -300)
        XCTAssertTrue(MeasuringRules.isCatchUp(missed, missed: missed, pending: nil))
        XCTAssertTrue(MeasuringRules.isCatchUp(missed, missed: nil, pending: missed))
        XCTAssertFalse(MeasuringRules.isCatchUp(missed, missed: night, pending: nil))
        XCTAssertFalse(MeasuringRules.isCatchUp(missed, missed: nil, pending: nil))
    }

    /// Ganze Epochen seit Fensterbeginn, nie negativ (Sitzung startet im Spielraum vor dem Fenster).
    func testUnmeasuredEpochsAreWholeAndNeverNegative() {
        XCTAssertEqual(MeasuringRules.unmeasuredEpochs(windowStart: now, now: now), 0)
        XCTAssertEqual(MeasuringRules.unmeasuredEpochs(windowStart: now, now: now.addingTimeInterval(29.9)), 0)
        XCTAssertEqual(MeasuringRules.unmeasuredEpochs(windowStart: now, now: now.addingTimeInterval(300)), 10)
        XCTAssertEqual(MeasuringRules.unmeasuredEpochs(windowStart: now, now: now.addingTimeInterval(329)), 10)
        XCTAssertEqual(MeasuringRules.unmeasuredEpochs(windowStart: now, now: now.addingTimeInterval(-90)), 0)
    }

    func testExactFromPlanOrAlarm() throws {
        let exact = try AlarmSpec(id: "d", hour: 7, minute: 0, windowMinutes: AlarmSpec.exactWindow)
        let smart = try AlarmSpec(id: "d", hour: 7, minute: 0)
        XCTAssertTrue(MeasuringRules.isExact(plan(targetIn: 600, exact: true), spec: nil))
        XCTAssertTrue(MeasuringRules.isExact(plan(targetIn: 600), spec: exact))
        XCTAssertFalse(MeasuringRules.isExact(plan(targetIn: 600), spec: smart))
        XCTAssertFalse(MeasuringRules.isExact(nil, spec: nil))
    }

    /// Ohne Sperre weckt der Moment mit seinem Grund (ohne die Erkennung nach «wach» zu fragen); mit Sperre nur, wenn
    /// die Person wach ist, sonst wird er übergangen.
    func testEarlyMomentWakesUnlessShortNightBlocks() {
        var asked = 0
        let free = MeasuringRules.early(.lightSleep, blocked: false) {
            asked += 1
            return .awake
        }
        XCTAssertEqual(free, .ring(.lightSleep, duringBlock: false))
        XCTAssertEqual(asked, 0)
        let awake = MeasuringRules.early(.lightSleep, blocked: true) {
            asked += 1
            return .awake
        }
        XCTAssertEqual(awake, .ring(.awake, duringBlock: true))
        XCTAssertEqual(asked, 1)
        XCTAssertEqual(MeasuringRules.early(.lightSleep, blocked: true) { nil }, .blocked)
    }

    /// Einschlafzeit noch in Rechnung: der erste Moment wird gemerkt, ein späterer nur, wenn die Person jetzt wach ist
    /// und beim gemerkten nicht war.
    func testMomentWhileOnsetPendingIsHeldAwakeWins() {
        let first = MeasuringRules.hold(.lightSleep, awake: false, held: nil, heldAwake: false)
        XCTAssertEqual(first?.reason, .lightSleep)
        XCTAssertEqual(first?.awake, false)
        XCTAssertNil(MeasuringRules.hold(.awake, awake: false, held: .lightSleep, heldAwake: false))
        let awake = MeasuringRules.hold(.lightSleep, awake: true, held: .lightSleep, heldAwake: false)
        XCTAssertEqual(awake?.reason, .lightSleep)
        XCTAssertEqual(awake?.awake, true)
        XCTAssertNil(MeasuringRules.hold(.awake, awake: true, held: .lightSleep, heldAwake: true))
    }

    func testHeldMomentDecidedOnceOnsetIsKnown() {
        XCTAssertEqual(MeasuringRules.heldMoment(.lightSleep, awake: false, exact: false, blocked: false),
                       .ring(.lightSleep, duringBlock: false))
        XCTAssertEqual(MeasuringRules.heldMoment(.lightSleep, awake: false, exact: false, blocked: true), .blocked)
        XCTAssertEqual(MeasuringRules.heldMoment(.lightSleep, awake: true, exact: false, blocked: true),
                       .ring(.awake, duringBlock: true))
        // «Genau»: weckt nie früh, die Sperre wird nicht gefragt.
        var asked = false
        func blocked() -> Bool {
            asked = true
            return true
        }
        XCTAssertEqual(MeasuringRules.heldMoment(.awake, awake: true, exact: true, blocked: blocked()), .ignore)
        XCTAssertFalse(asked)
    }

    func testLinesOfMomentsLikeAndroid() {
        XCTAssertEqual(MeasuringRules.blockedWhy(onsetKnown: true), "short night")
        XCTAssertEqual(MeasuringRules.blockedWhy(onsetKnown: false), "short night unknown")
        XCTAssertEqual(MeasuringRules.momentLine(.lightSleep, duringBlock: false, why: "short night"), "LIGHT_SLEEP")
        XCTAssertEqual(MeasuringRules.momentLine(.awake, duringBlock: true, why: "short night unknown"),
                       "AWAKE during short night unknown")
    }

    /// Ergebnis der Einschlafzeit: eingeschlafen setzt Zeit und Beginn, unbekannt ändert nichts, gescheitert gilt als
    /// gemessen ohne Ergebnis (fail-closed). Ein überholtes, abgelaufenes oder fremdes Ergebnis gilt nicht.
    func testOnsetResultUpdatesStateFailClosed() {
        let empty = MeasuringRules.Onset(asleepAt: nil, bedAt: nil, measured: false)
        let at = now.addingTimeInterval(-6 * 3600)
        let bed = at.addingTimeInterval(-900)
        XCTAssertEqual(MeasuringRules.onset(.asleep(at, bedAt: bed), current: empty),
                       MeasuringRules.Onset(asleepAt: at, bedAt: bed, measured: false))
        XCTAssertEqual(MeasuringRules.onset(.notMeasured, current: empty), empty)
        XCTAssertEqual(MeasuringRules.onset(.failed, current: empty),
                       MeasuringRules.Onset(asleepAt: nil, bedAt: nil, measured: true))
        XCTAssertTrue(MeasuringRules.onsetCounts(pending: true, run: 2, latest: 2, measuring: true))
        XCTAssertFalse(MeasuringRules.onsetCounts(pending: false, run: 2, latest: 2, measuring: true))
        XCTAssertFalse(MeasuringRules.onsetCounts(pending: true, run: 1, latest: 2, measuring: true))
        XCTAssertFalse(MeasuringRules.onsetCounts(pending: true, run: 2, latest: 2, measuring: false))
    }

    func testTargetReasonExactThenShortNightThenDetector() {
        var asked = 0
        func detected() -> WakeReason {
            asked += 1
            return .lightSleep
        }
        XCTAssertEqual(MeasuringRules.targetReason(exact: true, earlyBlocked: true, detected: detected), .exact)
        XCTAssertEqual(MeasuringRules.targetReason(exact: false, earlyBlocked: true, detected: detected), .shortNight)
        XCTAssertEqual(asked, 0)
        XCTAssertEqual(MeasuringRules.targetReason(exact: false, earlyBlocked: false, detected: detected), .lightSleep)
        XCTAssertEqual(asked, 1)
    }

    /// `schedule` bringt während der Messung einen Plan: derselbe misst weiter; ein Probewecker oder Powernap weckt per
    /// Mitteilung neben einem echten Wecker; nach einer Reise behalten misst weiter; sonst endet die Messung.
    func testChangedPlanDuringMeasuring() {
        let running = plan(targetIn: 600)
        let probe = plan(Transient.testPrefix + "1", targetIn: 120)
        let nap = plan(Transient.napPrefix + "1", targetIn: 900)
        var asked = 0
        func kept(_ value: Bool) -> () -> Bool {
            { asked += 1; return value }
        }
        XCTAssertEqual(MeasuringRules.change(running: running, plan: running, kept: kept(false)), .same)
        XCTAssertEqual(MeasuringRules.change(running: running, plan: probe, kept: kept(false)),
                       .transientByNotification(probe))
        XCTAssertEqual(asked, 0)
        XCTAssertEqual(MeasuringRules.change(running: running, plan: plan(targetIn: 700), kept: kept(true)),
                       .keptOverZone)
        XCTAssertEqual(MeasuringRules.change(running: running, plan: plan(targetIn: 700), kept: kept(false)), .cancel)
        // Wecker ausgeschaltet (kein Plan mehr).
        XCTAssertEqual(MeasuringRules.change(running: running, plan: nil, kept: kept(false)), .cancel)
        // Misst ein vorübergehender Plan, gilt die Ausnahme nicht.
        XCTAssertEqual(MeasuringRules.change(running: nap, plan: probe, kept: kept(false)), .cancel)
        XCTAssertEqual(asked, 4)
    }

    /// Abbruch vor der Zielzeit: Die gemessenen Epochen bleiben als Nacht (Weckzeit = Zielzeit, `deadline`); ohne
    /// Epochen, ohne Nacht oder bei Probewecker und Powernap keine.
    func testInterruptedMeasuringKeepsEpochsOfRealNights() throws {
        let night = plan(targetIn: 600)
        let epochs = [Epoch(motion: 0.1, heartRate: 58), Epoch(motion: nil, heartRate: nil)]
        let kept = try XCTUnwrap(MeasuringRules.interruptedNight(night, epochs: epochs))
        XCTAssertEqual(kept.alarmId, "d")
        XCTAssertEqual(kept.windowStart, night.windowStart)
        XCTAssertEqual(kept.wokeAt, night.target)
        XCTAssertEqual(kept.reason, .deadline)
        XCTAssertEqual(kept.snoozes, 0)
        XCTAssertEqual(kept.epochs, epochs)
        XCTAssertNil(MeasuringRules.interruptedNight(night, epochs: []))
        XCTAssertNil(MeasuringRules.interruptedNight(nil, epochs: epochs))
        XCTAssertNil(MeasuringRules.interruptedNight(plan(Transient.napPrefix + "1", targetIn: 600), epochs: epochs))
    }

    /// Ende der Messung ohne Wecken: kurze Vibration nur bei laufender Sitzung und offener App; ohne laufende Sitzung
    /// wird die App nicht gefragt.
    func testCancelledMeasuringVibratesOnlyInForeground() {
        XCTAssertTrue(MeasuringRules.confirmsCancel(.running, active: true))
        XCTAssertFalse(MeasuringRules.confirmsCancel(.running, active: false))
        var asked = false
        func active() -> Bool {
            asked = true
            return true
        }
        XCTAssertFalse(MeasuringRules.confirmsCancel(.scheduled, active: active()))
        XCTAssertFalse(MeasuringRules.confirmsCancel(.invalid, active: active()))
        XCTAssertFalse(MeasuringRules.confirmsCancel(nil, active: active()))
        XCTAssertFalse(asked)
    }

    /// Gleiches Format und dieselben Fälle wie `measureEndLineCountsHeartRateEpochs` der Galaxy Watch.
    func testMeasureEndLineCountsHeartRateEpochs() {
        let epochs = [Epoch(motion: 0.1, heartRate: 58), Epoch(motion: 0.2, heartRate: nil)]
        XCTAssertEqual(MeasuringRules.measureEndLine(epochs, summary: "floor 0.01", note: ", cancelled"),
                       "watch 2 epochs hr=1, floor 0.01, cancelled")
        XCTAssertEqual(MeasuringRules.measureEndLine([], summary: "s", note: ""), "watch 0 epochs hr=0, s")
    }
}
