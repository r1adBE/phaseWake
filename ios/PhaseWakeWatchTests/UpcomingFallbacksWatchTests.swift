import PhaseWakeCore
import XCTest
@testable import PhaseWakeWatch

/// Rückfälle der nächsten Nächte auf der Apple Watch: zwei Wochen nach der nächsten Nacht, Ketten und Budget der
/// Mitteilungen, Kennungen, nur in der Zukunft.
final class UpcomingFallbacksWatchTests: XCTestCase {
    private let utc = Calendar.gregorian(in: TimeZone(identifier: "UTC")!)
    private let now = Date(timeIntervalSince1970: 1_793_000_000)

    private func plan(_ id: String = "d", targetIn seconds: TimeInterval) -> WakePlan {
        WakePlan(alarmId: id, windowStart: now.addingTimeInterval(seconds - 1800), target: now.addingTimeInterval(seconds))
    }

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
}
