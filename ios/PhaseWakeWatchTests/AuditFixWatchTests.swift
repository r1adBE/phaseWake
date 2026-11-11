import PhaseWakeCore
import XCTest
@testable import PhaseWakeWatch

/// Vollaudit 071597f, Apple Watch: gemerkte Sitzung nach einem Neustart (AP2-1), Rückfälle nur in der Zukunft (AP2-2),
/// Pausen beim Lesen des Rekorders (AP2-3), «stop» vom iPhone ohne Klingeln (AP2-5), Plan der Komplikation mit Zone
/// (AP2-7), Zeiten in Nachrichten (AP2-8), alter Klingel-Zustand (AP2-9), nachgereichter Start (AP2-10), Räder 12/24 Std.
/// (N-10).
final class AuditFixWatchTests: XCTestCase {
    private let utc = Calendar.gregorian(in: TimeZone(identifier: "UTC")!)
    private let now = Date(timeIntervalSince1970: 1_793_000_000)

    private func plan(_ id: String = "d", targetIn seconds: TimeInterval) -> WakePlan {
        WakePlan(alarmId: id, windowStart: now.addingTimeInterval(seconds - 1800), target: now.addingTimeInterval(seconds))
    }

    // MARK: AP2-1

    func testRestartedAppKeepsTheScheduledSession() throws {
        let night = plan(targetIn: 8 * 3600)
        let start = night.target.addingTimeInterval(-1500)
        let saved = NightRules.ScheduledSession(alarmId: "d", target: night.target, start: start)
        let decoded = try XCTUnwrap(NightRules.ScheduledSession.decode(saved.encoded))
        XCTAssertEqual(decoded, saved)
        XCTAssertTrue(NightRules.keepsScheduled(decoded, plan: night, start: start, now: now, tolerance: 120))
        // Bis kurz nach dem Start (Spielraum des Systems), danach nicht mehr.
        XCTAssertTrue(NightRules.keepsScheduled(decoded, plan: night, start: start, now: start.addingTimeInterval(119), tolerance: 120))
        XCTAssertFalse(NightRules.keepsScheduled(decoded, plan: night, start: start, now: start.addingTimeInterval(120), tolerance: 120))
    }

    func testOtherPlanOrNoSavedSessionIsNotKept() {
        let night = plan(targetIn: 8 * 3600)
        let start = night.target.addingTimeInterval(-1500)
        let saved = NightRules.ScheduledSession(alarmId: "d", target: night.target, start: start)
        XCTAssertFalse(NightRules.keepsScheduled(nil, plan: night, start: start, now: now, tolerance: 120))
        XCTAssertFalse(NightRules.keepsScheduled(saved, plan: plan("x", targetIn: 8 * 3600), start: start, now: now, tolerance: 120))
        XCTAssertFalse(NightRules.keepsScheduled(saved, plan: plan(targetIn: 8 * 3600 + 60), start: start, now: now, tolerance: 120))
        XCTAssertFalse(NightRules.keepsScheduled(saved, plan: night, start: start.addingTimeInterval(60), now: now, tolerance: 120))
    }

    func testBrokenSavedSessionIsIgnored() {
        XCTAssertNil(NightRules.ScheduledSession.decode(nil))
        XCTAssertNil(NightRules.ScheduledSession.decode(["alarm": "d", "target": 9.2e18, "start": 1.0] as [String: Any]))
        XCTAssertNil(NightRules.ScheduledSession.decode(["alarm": "d", "target": Double.nan, "start": 1.0] as [String: Any]))
        XCTAssertNil(NightRules.ScheduledSession.decode(["target": 1_800_000_000.0, "start": 1_800_000_000.0] as [String: Any]))
    }

    // MARK: AP2-2

    func testPastNightGetsNoUpcomingFallbacks() throws {
        let daily = try AlarmSpec(id: "d", hour: 7, minute: 0, days: Set(Weekday.allCases))
        let start = utc.date(from: DateComponents(year: 2026, month: 11, day: 5, hour: 12))!
        // Erste Nacht liegt drei Tage zurück (ausstehend, nachgeholt): keine Nacht vor jetzt bekommt einen Rückfall.
        let past = try XCTUnwrap(AlarmSchedule.nextPlan(daily, now: start.addingTimeInterval(-3 * 86400), calendar: utc))
        let nights = UpcomingFallbacks.nights(after: past, specs: [daily], now: start, doneUntil: [:], nominalFirst: nil,
                                              calendar: utc, holidays: [])
        XCTAssertFalse(nights.isEmpty)
        XCTAssertTrue(nights.allSatisfy { $0.target > start })
        // Auch eine übergebene vergangene Nacht fällt beim Planen der Mitteilungen weg.
        let entries = UpcomingFallbacks.plan([plan(targetIn: -600), plan(targetIn: 3600)], now: now, otherPending: 0, delay: 60)
        XCTAssertEqual(entries.count, 1)
        XCTAssertTrue(entries.allSatisfy { ($0.dates.first ?? .distantPast) > now })
    }

    // MARK: AP2-3

    func testRecorderPauseNeverPassesTheDeadline() {
        XCTAssertEqual(LearnRecorder.pauseLength(0.2, now: now, deadline: now.addingTimeInterval(10)), 0.2)
        XCTAssertEqual(LearnRecorder.pauseLength(0.2, now: now, deadline: now.addingTimeInterval(0.05)), 0.05, accuracy: 1e-9)
        XCTAssertEqual(LearnRecorder.pauseLength(0.2, now: now, deadline: now.addingTimeInterval(-1)), 0)
        XCTAssertEqual(LearnRecorder.pauseLength(0, now: now, deadline: now.addingTimeInterval(10)), 0)
    }

    // MARK: AP2-5

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

    // MARK: AP2-7

    func testComplicationIgnoresPlanOfAnotherZone() throws {
        let stored = plan(targetIn: 3600)
        let zurich = try XCTUnwrap(TimeZone(identifier: "Europe/Zurich"))
        let tokyo = try XCTUnwrap(TimeZone(identifier: "Asia/Tokyo"))
        let value = EffectivePlan.encode(stored, zone: zurich)
        XCTAssertEqual(EffectivePlan.decode(value, zone: zurich), stored)
        XCTAssertNil(EffectivePlan.decode(value, zone: tokyo))
        // Ältere Version ohne Versatz: gilt weiter.
        var legacy = try XCTUnwrap(value)
        legacy["offset"] = nil
        XCTAssertEqual(EffectivePlan.decode(legacy, zone: tokyo), stored)
    }

    // MARK: AP2-8

    func testMessageTimesNeverTrap() {
        XCTAssertNil(SyncTime.date(Int64.max))
        XCTAssertNil(SyncTime.date(Int64.min))
        XCTAssertNil(SyncTime.date("1"))
        XCTAssertEqual(SyncTime.date(Int64(1_793_000_000_000)), now)
        XCTAssertTrue(SyncTime.stale(Int64.min, now: now))
        XCTAssertTrue(SyncTime.stale(Int64.max, now: now))
        XCTAssertTrue(SyncTime.stale(nil, now: now))
        XCTAssertFalse(SyncTime.stale(TimeBounds.millis(now) - 1000, now: now))
        XCTAssertTrue(SyncTime.stale(TimeBounds.millis(now) - SyncKeys.maxRingAgeMs - 1, now: now))
        XCTAssertNil(RingNight(alarmId: "d", target: Date(timeIntervalSince1970: 1e300)).targetMs.flatMap(TimeBounds.checked))
    }

    // MARK: AP2-9

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

    // MARK: AP2-10

    func testRetriedRecordStartIsAcceptedUpToTenMinutes() {
        let nowMs = TimeBounds.millis(now)
        // Frisch wie bisher.
        XCTAssertTrue(RecordRetry.accepts(sentAtMs: nowMs - 60_000, retryTapMs: nil, now: now))
        // Spät ohne Kennzeichen: nein; nachgereicht mit Tippen vor 9 Min: ja, vor 11 Min: nein.
        XCTAssertFalse(RecordRetry.accepts(sentAtMs: nowMs - 300_000, retryTapMs: nil, now: now))
        XCTAssertTrue(RecordRetry.accepts(sentAtMs: nowMs - 300_000, retryTapMs: nowMs - 540_000, now: now))
        XCTAssertFalse(RecordRetry.accepts(sentAtMs: nowMs - 300_000, retryTapMs: nowMs - 660_000, now: now))
        // Kaputte Zeiten: nein.
        XCTAssertFalse(RecordRetry.accepts(sentAtMs: Int64.min, retryTapMs: nowMs, now: now))
        XCTAssertFalse(RecordRetry.accepts(sentAtMs: nowMs - 300_000, retryTapMs: Int64.max, now: now))
    }

    // MARK: N-10

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
