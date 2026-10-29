import XCTest
@testable import PhaseWakeCore

/// Gegenstück zu `SleepGoalTest` (Kotlin): gleiche Eingaben, gleiche Erwartungen.
final class SleepGoalTests: XCTestCase {
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

    private func alarm(_ id: String, _ hour: Int, _ minute: Int, days: Set<Weekday> = Set(Weekday.allCases), sleep: Int? = nil) -> AlarmSpec {
        try! AlarmSpec(id: id, hour: hour, minute: minute, days: days, sleepDurationMinutes: sleep)
    }

    func testGoalIsClampedToRangeAndStep() {
        XCTAssertEqual(SleepGoal.defaultMinutes, 450)
        XCTAssertEqual(SleepGoal.clamp(100), 360)
        XCTAssertEqual(SleepGoal.clamp(700), 600)
        XCTAssertEqual(SleepGoal.clamp(460), 465)
        XCTAssertEqual(SleepGoal.clamp(457), 450)
        XCTAssertTrue(SleepGoal.reached(asleepMinutes: 450, goalMinutes: 450))
        XCTAssertFalse(SleepGoal.reached(asleepMinutes: 449, goalMinutes: 450))
    }

    func testWeekStatusCountsNewestSevenNights() {
        let nights: [(date: String, asleepMinutes: Int)] = (0..<10).map { i in
            (date: NightDate.adding(-i, to: "2026-10-27"), asleepMinutes: i % 2 == 0 ? 480 : 400)
        }
        // Jüngste 7: Tage 0–6 → 0, 2, 4, 6 erreicht.
        let status = SleepGoal.weekStatus(nights, goalMinutes: 450)
        XCTAssertEqual(status.reached, 4)
        XCTAssertEqual(status.total, 7)
        let empty = SleepGoal.weekStatus([], goalMinutes: 450)
        XCTAssertEqual(empty.reached, 0)
        XCTAssertEqual(empty.total, 0)
        // Doppeltes Datum zählt einmal, der längere Eintrag gilt.
        let double = SleepGoal.weekStatus([("2026-10-27", 300), ("2026-10-27", 460), ("2026-10-26", 200)], goalMinutes: 450)
        XCTAssertEqual(double.reached, 1)
        XCTAssertEqual(double.total, 2)
    }

    func testBedtimeIsTargetMinusGoal() {
        let target = at("2026-10-28T06:00")
        XCTAssertEqual(Bedtime.bedtime(nextAlarmTarget: target, goalMinutes: 450), at("2026-10-27T22:30"))
        XCTAssertEqual(Bedtime.reminderAt(nextAlarmTarget: target, goalMinutes: 450), at("2026-10-27T22:00"))
        XCTAssertEqual(Bedtime.reminderAt(nextAlarmTarget: target, goalMinutes: 450, leadMinutes: 10), at("2026-10-27T22:20"))
    }

    func testBedtimeOnDialOnlyWithinTwelveHours() {
        let target = at("2026-10-28T06:00")
        XCTAssertEqual(Bedtime.onDial(nextAlarmTarget: target, goalMinutes: 450), at("2026-10-27T22:30"))
        XCTAssertEqual(Bedtime.onDial(nextAlarmTarget: target, goalMinutes: SleepGoal.maxMinutes), at("2026-10-27T20:00"))
        XCTAssertEqual(Bedtime.onDial(nextAlarmTarget: target, goalMinutes: 719), at("2026-10-27T18:01"))
        XCTAssertNil(Bedtime.onDial(nextAlarmTarget: target, goalMinutes: Bedtime.dialMinutes))
        XCTAssertNil(Bedtime.onDial(nextAlarmTarget: target, goalMinutes: 0))
    }

    func testNextReminderSkipsPastNightsAndSecondAlarms() throws {
        let alarms = [alarm("a", 6, 0), alarm("b", 6, 30)]
        // Vor der Erinnerung: die für 06:00.
        let evening = try XCTUnwrap(Bedtime.next(alarms, from: at("2026-10-27T20:00"), calendar: zurich, goalMinutes: 450))
        XCTAssertEqual(evening.at, at("2026-10-27T22:00"))
        XCTAssertEqual(evening.bedtime, at("2026-10-27T22:30"))
        XCTAssertEqual(evening.target, at("2026-10-28T06:00"))
        // Erinnerung verpasst (22:10): nicht nachträglich für den zweiten Wecker um 06:30, sondern morgen.
        let late = try XCTUnwrap(Bedtime.next(alarms, from: at("2026-10-27T22:10"), calendar: zurich, goalMinutes: 450))
        XCTAssertEqual(late.at, at("2026-10-28T22:00"))
        // Nach dem Zeigen (ab dem Zeitpunkt der Erinnerung) dasselbe.
        XCTAssertEqual(Bedtime.next(alarms, from: at("2026-10-27T22:00"), calendar: zurich, goalMinutes: 450)?.at, at("2026-10-28T22:00"))
        // Einschlaf-Modus: es gilt die späteste Weckzeit.
        let sleepMode = Bedtime.next([alarm("s", 7, 0, sleep: 420)], from: at("2026-10-27T20:00"), calendar: zurich, goalMinutes: 480)
        XCTAssertEqual(sleepMode?.at, at("2026-10-27T22:30"))
        XCTAssertNil(Bedtime.next([alarm("off", 6, 0).with(enabled: false)], from: at("2026-10-27T20:00"), calendar: zurich, goalMinutes: 450))
        XCTAssertNil(Bedtime.next([], from: at("2026-10-27T20:00"), calendar: zurich, goalMinutes: 450))
    }

    func testOneShotAlarmWithPastReminderHasNone() {
        // Einmal-Wecker in 3 Stunden: Erinnerung läge in der Vergangenheit, danach kommt nichts mehr.
        let once = alarm("o", 23, 0, days: [])
        XCTAssertNil(Bedtime.next([once], from: at("2026-10-27T20:00"), calendar: zurich, goalMinutes: 450))
    }

    func testNightsOfHealthAppMapByEndAndEmptyWeek() {
        let sleep = SleepSummary(start: at("2026-10-27T23:00"), end: at("2026-10-28T06:30"), asleepMinutes: 420)
        let nights = SleepGoal.nightsOf([sleep], calendar: zurich)
        XCTAssertEqual(nights.map(\.date), ["2026-10-27"])
        XCTAssertEqual(nights.map(\.asleepMinutes), [420])
        XCTAssertEqual(SleepGoal.weekStatus([], goalMinutes: 450).total, 0)
    }
}
