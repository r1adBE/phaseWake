import XCTest
import PhaseWakeCore

final class AlarmScheduleTests: XCTestCase {

    private var zurich: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "Europe/Zurich")!
        return c
    }()

    private func at(_ y: Int, _ mo: Int, _ d: Int, _ h: Int, _ mi: Int) -> Date {
        zurich.date(from: DateComponents(year: y, month: mo, day: d, hour: h, minute: mi))!
    }

    private func alarm(hour: Int = 7, minute: Int = 0, days: Set<Weekday> = [], id: String = "a") -> AlarmSpec {
        try! AlarmSpec(id: id, hour: hour, minute: minute, days: days)
    }

    func testOneShot() {
        XCTAssertEqual(AlarmSchedule.nextTarget(alarm(), now: at(2026, 10, 28, 1, 0), calendar: zurich), at(2026, 10, 28, 7, 0))
        XCTAssertEqual(AlarmSchedule.nextTarget(alarm(), now: at(2026, 10, 28, 7, 0), calendar: zurich), at(2026, 10, 29, 7, 0))
    }

    func testRunningWindow() {
        let plan = AlarmSchedule.nextPlan(alarm(), now: at(2026, 10, 28, 6, 45), calendar: zurich)!
        XCTAssertEqual(plan.target, at(2026, 10, 28, 7, 0))
        XCTAssertEqual(plan.windowStart, at(2026, 10, 28, 6, 30))
    }

    func testWeekdays() {
        let weekdays: Set<Weekday> = [.monday, .tuesday, .wednesday, .thursday, .friday]
        XCTAssertEqual(AlarmSchedule.nextTarget(alarm(days: weekdays), now: at(2026, 10, 30, 8, 0), calendar: zurich), at(2026, 11, 2, 7, 0))
        XCTAssertEqual(AlarmSchedule.nextTarget(alarm(days: [.wednesday]), now: at(2026, 10, 28, 7, 30), calendar: zurich), at(2026, 11, 4, 7, 0))
    }

    func testDisabled() {
        XCTAssertNil(AlarmSchedule.nextTarget(alarm().with(enabled: false), now: at(2026, 10, 28, 1, 0), calendar: zurich))
    }

    func testSpringForwardGap() {
        // 29.3.2026: 02:00 → 03:00; 02:30 wird 03:30 Sommerzeit = 01:30 UTC.
        let target = AlarmSchedule.nextTarget(alarm(hour: 2, minute: 30), now: at(2026, 3, 29, 0, 0), calendar: zurich)!
        XCTAssertEqual(target, Date(timeIntervalSince1970: 1_774_747_800))
    }

    func testFallBackOverlap() {
        // 25.10.2026: 02:30 gibt es zweimal; es gilt die frühere = 00:30 UTC.
        let target = AlarmSchedule.nextTarget(alarm(hour: 2, minute: 30), now: at(2026, 10, 25, 0, 0), calendar: zurich)!
        XCTAssertEqual(target, ISO8601DateFormatter().date(from: "2026-10-25T00:30:00Z"))
    }

    func testEarliestAndSleepTarget() {
        let now = at(2026, 10, 28, 1, 0)
        XCTAssertEqual(AlarmSchedule.earliestPlan([alarm(hour: 8, id: "b"), alarm(hour: 6, id: "c")], now: now, calendar: zurich)?.alarmId, "c")
        XCTAssertNil(AlarmSchedule.earliestPlan([], now: now, calendar: zurich))
        let onset = at(2026, 10, 27, 23, 10)
        let latest = at(2026, 10, 28, 7, 0)
        XCTAssertEqual(AlarmSchedule.sleepTarget(onset: onset, sleepDurationMinutes: 450, latest: latest), at(2026, 10, 28, 6, 40))
        XCTAssertEqual(AlarmSchedule.sleepTarget(onset: onset, sleepDurationMinutes: 600, latest: latest), latest)
    }

    func testInvalidSpec() {
        XCTAssertThrowsError(try AlarmSpec(id: "a", hour: 24, minute: 0))
        XCTAssertThrowsError(try AlarmSpec(id: "a", hour: 7, minute: 60))
        XCTAssertThrowsError(try AlarmSpec(id: "a", hour: 7, minute: 0, windowMinutes: 31))
        XCTAssertThrowsError(try AlarmSpec(id: "a", hour: 7, minute: 0, windowMinutes: 9))
        XCTAssertThrowsError(try AlarmSpec(id: "a", hour: 7, minute: 0, sleepDurationMinutes: 30))
        XCTAssertThrowsError(try AlarmSpec(id: " ", hour: 7, minute: 0))
    }

    func testSnoozeAndOnset() {
        XCTAssertTrue(Snooze.canSnooze(2))
        XCTAssertFalse(Snooze.canSnooze(3))
        let restless = [Double?](repeating: 0.05, count: 10)
        let shortRest = [Double?](repeating: 0.0, count: 20)
        let still = [Double?](repeating: 0.0, count: 30)
        XCTAssertEqual(SleepOnset.find(restless + shortRest + [nil] + still), 31)
        XCTAssertNil(SleepOnset.find([Double?](repeating: 0.002, count: 60)))
    }
}
