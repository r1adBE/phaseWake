import CoreMotion
import PhaseWakeCore
import WatchKit
import XCTest
@testable import PhaseWakeWatch

/// Vollaudit 11.11.2026, Apple Watch: Ende der Sitzung (AP-1), verwaiste Sitzungen und Vibration (AP-3), Abbruch beim
/// Messen (AP-4), Rückfälle der nächsten Nächte (AP-2), «Verlauf löschen» mit Sendezeit (AP-6), Rekorder nur mit
/// Erlaubnis gemerkt (AP-7), Plan der Komplikation (AP-9).
final class SessionAuditTests: XCTestCase {
    private let utc = Calendar.gregorian(in: TimeZone(identifier: "UTC")!)
    private let now = Date(timeIntervalSince1970: 1_793_000_000)

    // MARK: AP-1

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

    // MARK: AP-3

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

    // MARK: AP-4

    func testInterruptedMeasuringWakesOnlyFromTarget() {
        let target = now.addingTimeInterval(600)
        // Vor der Zielzeit nicht sofort (und nicht zusätzlich 60 s später): Es weckt der feste Rückfall.
        XCTAssertNil(SessionRules.interruptedWake(now: now, target: target))
        XCTAssertEqual(SessionRules.interruptedWake(now: target, target: target), target)
        XCTAssertEqual(SessionRules.interruptedWake(now: target.addingTimeInterval(5), target: target), target.addingTimeInterval(5))
    }

    // MARK: AP-2

    private func noon() -> Date {
        utc.date(from: DateComponents(year: 2026, month: 11, day: 2, hour: 12))!
    }

    func testUpcomingNightsCoverTwoWeeksAfterTheNext() throws {
        let daily = try AlarmSpec(id: "d", hour: 7, minute: 0, days: Set(Weekday.allCases))
        let start = noon()
        let first = try XCTUnwrap(AlarmSchedule.nextPlan(daily, now: start, calendar: utc))
        let nights = UpcomingFallbacks.nights(after: first, specs: [daily], now: start, doneUntil: [:], nominalFirst: nil,
                                              calendar: utc, holidays: [])
        // Nächste Nacht hat ihren eigenen Rückfall; danach jede bis 14 Tage ab jetzt.
        XCTAssertEqual(nights.count, 13)
        XCTAssertEqual(nights.first?.target, first.target.addingTimeInterval(86400))
        XCTAssertTrue(nights.allSatisfy { $0.target > first.target && $0.target <= start.addingTimeInterval(UpcomingFallbacks.horizon) })
    }

    func testGoodNightDoesNotRingAgainAtTheSetTime() throws {
        let daily = try AlarmSpec(id: "d", hour: 7, minute: 0, days: Set(Weekday.allCases))
        let start = noon()
        let nominal = try XCTUnwrap(AlarmSchedule.nextPlan(daily, now: start, calendar: utc))
        let earlier = WakePlan(alarmId: "d", windowStart: nominal.target.addingTimeInterval(-3600), target: nominal.target.addingTimeInterval(-1800))
        let withNominal = UpcomingFallbacks.nights(after: earlier, specs: [daily], now: start, doneUntil: [:], nominalFirst: nominal.target,
                                                   calendar: utc, holidays: [])
        XCTAssertEqual(withNominal.first?.target, nominal.target.addingTimeInterval(86400))
        let without = UpcomingFallbacks.nights(after: earlier, specs: [daily], now: start, doneUntil: [:], nominalFirst: nil,
                                               calendar: utc, holidays: [])
        XCTAssertEqual(without.first?.target, nominal.target)
    }

    func testOneShotOnlyOnceAndNeverForTransient() throws {
        let daily = try AlarmSpec(id: "d", hour: 7, minute: 0, days: Set(Weekday.allCases))
        let once = try AlarmSpec(id: "o", hour: 9, minute: 0)
        let start = noon()
        let first = try XCTUnwrap(AlarmSchedule.nextPlan(daily, now: start, calendar: utc))
        let nights = UpcomingFallbacks.nights(after: first, specs: [daily, once], now: start, doneUntil: [:], nominalFirst: nil,
                                              calendar: utc, holidays: [])
        XCTAssertEqual(nights.filter { $0.alarmId == "o" }.count, 1)
        // Der einmalige Wecker ist selbst die nächste Nacht: danach nichts mehr von ihm.
        let onlyOnce = try XCTUnwrap(AlarmSchedule.nextPlan(once, now: start, calendar: utc))
        XCTAssertTrue(UpcomingFallbacks.nights(after: onlyOnce, specs: [once], now: start, doneUntil: [:], nominalFirst: nil,
                                               calendar: utc, holidays: []).isEmpty)
        let nap = WakePlan(alarmId: Transient.napPrefix + "1", windowStart: start, target: start.addingTimeInterval(1200))
        XCTAssertTrue(UpcomingFallbacks.nights(after: nap, specs: [daily], now: start, doneUntil: [:], nominalFirst: nil,
                                               calendar: utc, holidays: []).isEmpty)
        XCTAssertTrue(UpcomingFallbacks.nights(after: nil, specs: [daily], now: start, doneUntil: [:], nominalFirst: nil,
                                               calendar: utc, holidays: []).isEmpty)
    }

    private func nights(hoursAhead: [Double]) -> [WakePlan] {
        hoursAhead.map { WakePlan(alarmId: "d", windowStart: now.addingTimeInterval($0 * 3600 - 1800), target: now.addingTimeInterval($0 * 3600)) }
    }

    func testUpcomingChainsWithin48HoursThenSingle() {
        let entries = UpcomingFallbacks.plan(nights(hoursAhead: [10, 34, 58, 82]), now: now, otherPending: 0, delay: 60)
        XCTAssertEqual(entries.map(\.ids.count), [NotificationChain.count, NotificationChain.count, 1, 1])
        XCTAssertEqual(entries[0].ids, NotificationChain.ids(UpcomingFallbacks.base(1)))
        XCTAssertEqual(entries[0].dates.first, now.addingTimeInterval(10 * 3600 + 60))
        XCTAssertEqual(entries[0].dates[1], now.addingTimeInterval(10 * 3600 + 60 + NotificationChain.spacing))
        XCTAssertEqual(entries[3].ids, [UpcomingFallbacks.base(4)])
    }

    func testUpcomingStaysWithinTheNotificationBudget() {
        // 64 − 4 Reserve − 55 andere = 5 frei: eine ganze Kette, dann eine einzelne, dann Schluss.
        let tight = UpcomingFallbacks.plan(nights(hoursAhead: [10, 34, 58]), now: now, otherPending: 55, delay: 60)
        XCTAssertEqual(tight.map(\.ids.count), [NotificationChain.count, 1])
        // Kein Platz: keine (die nächste Nacht hat ihren eigenen Rückfall).
        XCTAssertTrue(UpcomingFallbacks.plan(nights(hoursAhead: [10]), now: now, otherPending: 60, delay: 60).isEmpty)
    }

    func testUpcomingIdsAreRecognisedAndCounted() {
        XCTAssertTrue(UpcomingFallbacks.isUpcoming(UpcomingFallbacks.base(3)))
        XCTAssertTrue(UpcomingFallbacks.isUpcoming(UpcomingFallbacks.base(3) + ".2"))
        XCTAssertTrue(UpcomingFallbacks.isUpcoming(UpcomingFallbacks.base(14)))
        XCTAssertFalse(UpcomingFallbacks.isUpcoming(UpcomingFallbacks.base(15)))
        XCTAssertFalse(UpcomingFallbacks.isUpcoming(SmartAlarm.fallbackId))
        XCTAssertFalse(UpcomingFallbacks.isUpcoming(SmartAlarm.fallbackId + ".1"))
        // Bei offener App gelten sie als Rückfall.
        XCTAssertTrue(WatchNotificationRouter.isFallback(UpcomingFallbacks.base(1) + ".1"))
        XCTAssertEqual(UpcomingFallbacks.allIds.count, UpcomingFallbacks.maxNights * NotificationChain.count)
        let pending = ["a", UpcomingFallbacks.base(1), UpcomingFallbacks.base(1) + ".1", SmartAlarm.fallbackId]
        XCTAssertEqual(UpcomingFallbacks.otherPending(pending, reserved: [SmartAlarm.fallbackId, "x"]), 3)
    }

    // MARK: AP-6

    func testClearHistoryNeedsSendTimeAndAppliesOnce() {
        let sent = now
        XCTAssertNil(HistoryClear.clearUntil(sentAt: nil, applied: nil))
        XCTAssertEqual(HistoryClear.clearUntil(sentAt: sent, applied: nil), sent)
        XCTAssertNil(HistoryClear.clearUntil(sentAt: sent, applied: sent))
        XCTAssertNil(HistoryClear.clearUntil(sentAt: sent, applied: sent.addingTimeInterval(1)))
        XCTAssertEqual(HistoryClear.clearUntil(sentAt: sent.addingTimeInterval(1), applied: sent), sent.addingTimeInterval(1))
    }

    func testClearHistoryKeepsOnlyWhatEndedLater() {
        XCTAssertFalse(HistoryClear.keeps(end: now.addingTimeInterval(-60), clearedAt: now))
        XCTAssertFalse(HistoryClear.keeps(end: now, clearedAt: now))
        XCTAssertTrue(HistoryClear.keeps(end: now.addingTimeInterval(1), clearedAt: now))
        XCTAssertTrue(HistoryClear.keeps(end: .distantPast, clearedAt: nil))
    }

    // MARK: AP-7

    func testRecorderSpanRememberedOnlyWithPermission() {
        XCTAssertTrue(LearnRecorder.remembersSpan(.authorized))
        XCTAssertFalse(LearnRecorder.remembersSpan(.notDetermined))
        XCTAssertFalse(LearnRecorder.remembersSpan(.denied))
        XCTAssertFalse(LearnRecorder.remembersSpan(.restricted))
    }

    // MARK: AP-9

    func testComplicationShowsThePlanTheWatchWakesBy() throws {
        let stored = WakePlan(alarmId: "d", windowStart: now.addingTimeInterval(3000), target: now.addingTimeInterval(3600), exact: true)
        let computed = WakePlan(alarmId: "d", windowStart: now.addingTimeInterval(4800), target: now.addingTimeInterval(5400))
        let decoded = try XCTUnwrap(EffectivePlan.decode(EffectivePlan.encode(stored)))
        XCTAssertEqual(decoded, stored)
        XCTAssertEqual(EffectivePlan.choose(stored: decoded, computed: computed, now: now), stored)
        // Zielzeit (plus eine Minute) vorbei: wieder aus den Weckern gerechnet.
        XCTAssertEqual(EffectivePlan.choose(stored: decoded, computed: computed, now: stored.target.addingTimeInterval(60)), computed)
        XCTAssertEqual(EffectivePlan.choose(stored: nil, computed: computed, now: now), computed)
        XCTAssertNil(EffectivePlan.encode(nil))
    }

    func testComplicationIgnoresImplausibleStoredPlans() {
        XCTAssertNil(EffectivePlan.decode(nil))
        XCTAssertNil(EffectivePlan.decode("x"))
        XCTAssertNil(EffectivePlan.decode(["alarm": "d", "window": 2_000_000_000.0, "target": 1_900_000_000.0]))
        XCTAssertNil(EffectivePlan.decode(["alarm": "d", "window": 0.0, "target": 9.2e18]))
        XCTAssertNil(EffectivePlan.decode(["window": 1_800_000_000.0, "target": 1_800_000_600.0]))
    }
}
