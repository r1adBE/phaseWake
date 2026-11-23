import PhaseWakeCore
import WatchKit
import XCTest
@testable import PhaseWakeWatch

/// Entscheide beim Klingeln, Schlummern, Beenden und beim Rückfall der Apple Watch (``RingingRules``):
/// Schlummer-Runden, Zeitgrenze, Stopp vom iPhone, Weiterwecken nach dem Ende der Sitzung, Abschluss der Nacht,
/// Rückfall bei offener App.
final class RingingRulesWatchTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_793_000_000)

    private func plan(_ id: String = "d", targetIn seconds: TimeInterval) -> WakePlan {
        WakePlan(alarmId: id, windowStart: now.addingTimeInterval(seconds - 1800),
                 target: now.addingTimeInterval(seconds))
    }

    func testSnoozesLeftNeverForProbeAlarm() {
        XCTAssertEqual(RingingRules.snoozesLeft(alarmId: "d", snoozes: 0), Snooze.maxCount)
        XCTAssertEqual(RingingRules.snoozesLeft(alarmId: "d", snoozes: Snooze.maxCount), 0)
        XCTAssertEqual(RingingRules.snoozesLeft(alarmId: Transient.napPrefix + "1", snoozes: 1), Snooze.maxCount - 1)
        XCTAssertEqual(RingingRules.snoozesLeft(alarmId: Transient.testPrefix + "1", snoozes: 0), 0)
        // Nach einem Neustart ohne Plan: wie ein echter Wecker.
        XCTAssertEqual(RingingRules.snoozesLeft(alarmId: nil, snoozes: 0), Snooze.maxCount)
    }

    /// Hängengeblieben: Klingeln oder Schlummern länger als die Zeitgrenze nach der Zielzeit, oder ohne Nacht.
    func testRingExpiresAfterLimitOrWithoutNight() {
        let target = now.addingTimeInterval(-3600)
        XCTAssertFalse(RingingRules.ringExpired(target: target, now: now, limit: 3600))
        XCTAssertTrue(RingingRules.ringExpired(target: target, now: now.addingTimeInterval(1), limit: 3600))
        XCTAssertFalse(RingingRules.ringExpired(target: now.addingTimeInterval(60), now: now, limit: 3600))
        XCTAssertTrue(RingingRules.ringExpired(target: nil, now: now, limit: 3600))
    }

    /// Harte Grenze der Nacht wie auf der Galaxy Watch (`NightLimit`, 2 h): Klingeln 1,5 h nach der Zielzeit ist nicht
    /// hängengeblieben, nach 2 h schon. Der gesicherte Zustand gilt gleich lang; die Nachfrist einer nicht geweckten
    /// Nacht bleibt 1 h.
    @MainActor
    func testHardLimitOfTheNightIsShared() {
        XCTAssertEqual(SmartAlarm.nightLimit, NightLimit.afterTarget)
        XCTAssertEqual(SmartAlarm.ringStateMaxAge, SmartAlarm.nightLimit)
        XCTAssertEqual(SmartAlarm.ringLimit, 3600)
        let target = now.addingTimeInterval(-5400)
        XCTAssertFalse(RingingRules.ringExpired(target: target, now: now, limit: SmartAlarm.nightLimit))
        XCTAssertTrue(RingingRules.ringExpired(target: target, now: now.addingTimeInterval(1801),
                                               limit: SmartAlarm.nightLimit))
    }

    /// «stop» oder «schlummern» vom iPhone gilt nur für dieselbe Nacht (Zielzeit auf 1 s); fehlende Angaben
    /// widersprechen nicht.
    func testPhoneCommandBelongsToThisNightOnly() {
        let target = now.addingTimeInterval(600)
        func same(_ id: String?, _ time: Date?) -> Bool {
            RingingRules.isThisNight(alarmId: id, target: time, ownAlarmId: "d", ownTarget: target)
        }
        XCTAssertTrue(same("d", target))
        XCTAssertTrue(same("d", target.addingTimeInterval(0.9)))
        XCTAssertFalse(same("d", target.addingTimeInterval(1)))
        XCTAssertFalse(same("x", target))
        XCTAssertFalse(same("d", target.addingTimeInterval(86400)))
        XCTAssertTrue(same(nil, nil))
        XCTAssertTrue(RingingRules.isThisNight(alarmId: "x", target: now, ownAlarmId: nil, ownTarget: nil))
    }

    func testSnoozeOnlyWhileRingingWithRoundsLeft() {
        XCTAssertEqual(RingingRules.snoozeChoice(ringing: true, snoozesLeft: 1), .snooze)
        // Keine Runde mehr: weiterklingeln (das iPhone bekommt «klingeln» zurück).
        XCTAssertEqual(RingingRules.snoozeChoice(ringing: true, snoozesLeft: 0), .keepRinging)
        XCTAssertEqual(RingingRules.snoozeChoice(ringing: false, snoozesLeft: 3), .ignore)
    }

    func testSnoozeRoundPlansNewSessionAndFallback() {
        let round = RingingRules.snoozeRound(alarmId: "d", now: now, delay: 60)
        XCTAssertEqual(round.until, Snooze.nextRing(now))
        XCTAssertEqual(round.plan, WakePlan(alarmId: "d", windowStart: round.until, target: round.until))
        XCTAssertEqual(round.fallbackAt, round.until.addingTimeInterval(60))
    }

    /// Stopp beim Klingeln oder Schlummern beendet die Nacht ganz (samt zugestellter Rückfälle späterer Nächte, ausser
    /// bei Probewecker und Powernap); sonst nur die Kette nach einer abgebrochenen Sitzung.
    func testStopEndsTheWholeNight() {
        XCTAssertEqual(RingingRules.dismissal(ringing: true, snoozed: false, alarmId: "d"),
                       .night(clearsUpcoming: true))
        XCTAssertEqual(RingingRules.dismissal(ringing: false, snoozed: true, alarmId: "d"),
                       .night(clearsUpcoming: true))
        XCTAssertEqual(RingingRules.dismissal(ringing: true, snoozed: false, alarmId: Transient.napPrefix + "1"),
                       .night(clearsUpcoming: false))
        XCTAssertEqual(RingingRules.dismissal(ringing: false, snoozed: false, alarmId: "d"), .chainOnly)
    }

    /// «stop» ans iPhone wie auf der Galaxy Watch: nach einem Stopp auf der Uhr und am iPhone (Klingeln, Schlummern,
    /// Messen); nicht nach einem Abbruch (das iPhone klingelt weiter) und nicht für einen alten Zustand.
    func testStopIsReportedToThePhoneLikeOnGalaxy() {
        XCTAssertTrue(RingingRules.notifiesPhone(.watch))
        XCTAssertTrue(RingingRules.notifiesPhone(.phone))
        XCTAssertFalse(RingingRules.notifiesPhone(.interrupted))
        XCTAssertFalse(RingingRules.notifiesPhone(.stale))
    }

    /// Sitzung endet beim Klingeln oder Schlummern: sofort weiterwecken, beim Schlummern erst kurz nach dessen Ende,
    /// nach der Zeitgrenze nicht mehr.
    func testInterruptedRingingWakesAgain() {
        XCTAssertEqual(RingingRules.interruptedRingWake(expired: false, snoozedUntil: nil, now: now, delay: 60), now)
        let until = now.addingTimeInterval(240)
        XCTAssertEqual(RingingRules.interruptedRingWake(expired: false, snoozedUntil: until, now: now, delay: 60),
                       until.addingTimeInterval(60))
        XCTAssertEqual(RingingRules.interruptedRingWake(expired: false, snoozedUntil: now, now: now, delay: 60), now)
        XCTAssertEqual(RingingRules.interruptedRingWake(expired: false, snoozedUntil: now.addingTimeInterval(-60),
                                                        now: now, delay: 60), now)
        XCTAssertNil(RingingRules.interruptedRingWake(expired: true, snoozedUntil: until, now: now, delay: 60))
    }

    /// Abschluss: Verlauf nur nach dem Klingeln eines echten Weckers; Akku ohne Klingeln verwerfen (Probewecker und
    /// Powernap haben keinen).
    func testFinishRecordsOnlyRungRealNights() {
        let night = plan(targetIn: -60)
        let probe = plan(Transient.testPrefix + "1", targetIn: -60)
        XCTAssertEqual(RingingRules.finish(night: night, firstRing: now),
                       RingingRules.Finish(transient: false, records: true))
        XCTAssertEqual(RingingRules.finish(night: night, firstRing: nil),
                       RingingRules.Finish(transient: false, records: false))
        XCTAssertEqual(RingingRules.finish(night: probe, firstRing: now),
                       RingingRules.Finish(transient: true, records: false))
        XCTAssertEqual(RingingRules.finish(night: nil, firstRing: now),
                       RingingRules.Finish(transient: false, records: false))
        XCTAssertTrue(RingingRules.dropsBattery(firstRing: nil, transient: false))
        XCTAssertFalse(RingingRules.dropsBattery(firstRing: now, transient: false))
        XCTAssertFalse(RingingRules.dropsBattery(firstRing: nil, transient: true))
    }

    func testOneShotAlarmIsDisabledAfterRinging() throws {
        let once = try AlarmSpec(id: "o", hour: 7, minute: 0)
        let daily = try AlarmSpec(id: "d", hour: 7, minute: 0, days: Set(Weekday.allCases))
        let off = try AlarmSpec(id: "o", hour: 7, minute: 0, enabled: false)
        XCTAssertTrue(RingingRules.disablesAfterRing(once, transient: false))
        XCTAssertFalse(RingingRules.disablesAfterRing(once, transient: true))
        XCTAssertFalse(RingingRules.disablesAfterRing(daily, transient: false))
        XCTAssertFalse(RingingRules.disablesAfterRing(off, transient: false))
    }

    func testGoodNightShiftedTheNightToTheMillisecond() {
        let night = plan(targetIn: -60)
        XCTAssertTrue(RingingRules.shiftedByGoodNight(night, goodNightAlarmId: "d", goodNightTarget: night.target))
        XCTAssertTrue(RingingRules.shiftedByGoodNight(night, goodNightAlarmId: "d",
                                                      goodNightTarget: night.target.addingTimeInterval(0.0004)))
        XCTAssertFalse(RingingRules.shiftedByGoodNight(night, goodNightAlarmId: "d",
                                                       goodNightTarget: night.target.addingTimeInterval(0.002)))
        XCTAssertFalse(RingingRules.shiftedByGoodNight(night, goodNightAlarmId: "x", goodNightTarget: night.target))
        XCTAssertFalse(RingingRules.shiftedByGoodNight(night, goodNightAlarmId: nil, goodNightTarget: nil))
        XCTAssertFalse(RingingRules.shiftedByGoodNight(nil, goodNightAlarmId: "d", goodNightTarget: night.target))
    }

    @MainActor
    func testArrivedFallbackIdOfChainAndLaterNights() {
        XCTAssertEqual(RingingRules.arrivedId(SmartAlarm.fallbackId), SmartAlarm.fallbackId)
        XCTAssertEqual(RingingRules.arrivedId(SmartAlarm.fallbackId + ".2"), SmartAlarm.fallbackId)
        XCTAssertEqual(RingingRules.arrivedId(SmartAlarm.interruptedId + ".3"), SmartAlarm.interruptedId)
        XCTAssertEqual(RingingRules.arrivedId(UpcomingFallbacks.base(4) + ".1"), SmartAlarm.fallbackId)
        XCTAssertEqual(RingingRules.arrivedId(SmartAlarm.transientFallbackId), SmartAlarm.transientFallbackId)
    }

    /// Rückfall bei offener App: weckt die Nacht ab der Zielzeit bis zur Zeitgrenze, nur mit ihrer Kennung; beendet
    /// das Schlummern, wenn dessen Ende vorbei ist.
    func testFallbackWakesItsNightOnlyWithinLimit() {
        let night = plan(targetIn: -60)
        func due(_ time: Date, id: String = "k") -> Bool {
            RingingRules.fallbackDue(night, key: "k", id: id, now: time, limit: 3600)
        }
        XCTAssertTrue(due(now))
        XCTAssertTrue(due(night.target))
        XCTAssertFalse(due(night.target.addingTimeInterval(-1)))
        XCTAssertFalse(due(night.target.addingTimeInterval(3600)))
        XCTAssertFalse(due(now, id: "other"))
        XCTAssertTrue(RingingRules.endsSnooze(until: now, key: "k", id: "k", now: now))
        XCTAssertFalse(RingingRules.endsSnooze(until: now.addingTimeInterval(1), key: "k", id: "k", now: now))
        XCTAssertFalse(RingingRules.endsSnooze(until: now, key: "k", id: "other", now: now))
    }

    /// Ohne laufende Sitzung vibriert die App beim Klingeln selbst; klingelt nichts, einmal (die Sitzung wird dann
    /// nicht gefragt); klingelt die Sitzung, nichts.
    func testFallbackHapticWithoutRunningSession() {
        XCTAssertEqual(RingingRules.fallbackHaptic(ringing: true, session: .running), .silent)
        XCTAssertEqual(RingingRules.fallbackHaptic(ringing: true, session: .scheduled), .repeating)
        XCTAssertEqual(RingingRules.fallbackHaptic(ringing: true, session: nil), .repeating)
        var asked = false
        func state() -> WKExtendedRuntimeSessionState? {
            asked = true
            return .running
        }
        XCTAssertEqual(RingingRules.fallbackHaptic(ringing: false, session: state()), .once)
        XCTAssertFalse(asked)
    }

    @MainActor
    func testFallbackChainForRealAndInterruptedOnly() {
        let real = RingingRules.fallbackChain(SmartAlarm.fallbackId, at: now)
        XCTAssertEqual(real.ids, NotificationChain.ids(SmartAlarm.fallbackId))
        XCTAssertEqual(real.dates, NotificationChain.dates(now))
        XCTAssertEqual(RingingRules.fallbackChain(SmartAlarm.interruptedId, at: now).ids.count,
                       NotificationChain.count)
        let transient = RingingRules.fallbackChain(SmartAlarm.transientFallbackId, at: now)
        XCTAssertEqual(transient.ids, [SmartAlarm.transientFallbackId])
        XCTAssertEqual(transient.dates, [now])
    }
}
