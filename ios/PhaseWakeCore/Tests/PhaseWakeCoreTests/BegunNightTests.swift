import XCTest
import PhaseWakeCore

/// Begonnene Nacht über einen Zeitzonenwechsel (`AlarmSchedule.begunNight`); gleiche Fälle wie `BegunNightTest` (Kotlin).
final class BegunNightTests: XCTestCase {

    private func calendar(_ zone: String) -> Calendar {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: zone)!
        return c
    }

    private lazy var zurich: Calendar = calendar("Europe/Zurich")
    private lazy var tokyo: Calendar = calendar("Asia/Tokyo")
    private let grace: TimeInterval = 120
    private lazy var planned: Date = at(7, 0)

    private func at(_ h: Int, _ mi: Int) -> Date {
        zurich.date(from: DateComponents(year: 2026, month: 11, day: 4, hour: h, minute: mi))!
    }

    private func alarm(hour: Int = 7, minute: Int = 0, window: Int = 30) -> AlarmSpec {
        try! AlarmSpec(id: "a", hour: hour, minute: minute, windowMinutes: window)
    }

    private func begun(_ spec: AlarmSpec? = nil, now: Date, withCalendar: Bool = true, suppressedUntil: Date? = nil) -> WakePlan? {
        AlarmSchedule.begunNight(spec ?? alarm(), plannedTarget: planned, plannedCalendar: withCalendar ? zurich : nil, now: now,
                                 grace: grace, suppressedUntil: suppressedUntil)
    }

    func testZoneChangeInWindowKeepsPlannedTarget() {
        let now = at(6, 40)
        // In Tokio (14:40) wäre die nächste 07:00 erst morgen.
        XCTAssertNotEqual(AlarmSchedule.nextTarget(alarm(), now: now, calendar: tokyo), planned)
        let plan = begun(now: now)
        XCTAssertEqual(plan?.target, planned)
        XCTAssertEqual(plan?.windowStart, at(6, 30))
        XCTAssertEqual(plan?.alarmId, "a")
        // Fensterbeginn zählt schon; in der Nachfrist bleibt die Nacht.
        XCTAssertEqual(begun(now: at(6, 30)), plan)
        XCTAssertEqual(begun(now: at(7, 1)), plan)
    }

    func testBeforeWindowOrAfterGraceNil() {
        XCTAssertNil(begun(now: at(6, 20)), "vor dem Fenster")
        XCTAssertNil(begun(now: at(7, 2)), "Ende der Nachfrist")
        XCTAssertNil(begun(now: at(8, 0)), "danach")
    }

    func testChangedAlarmNil() {
        let now = at(6, 40)
        XCTAssertNil(begun(alarm(minute: 30), now: now), "Zeit geändert")
        XCTAssertNil(begun(alarm().with(enabled: false), now: now), "ausgeschaltet")
        XCTAssertNil(begun(alarm(window: 10), now: now), "Fenster verkürzt, noch nicht begonnen")
    }

    func testSuppressedOrWithoutCalendarNil() {
        let now = at(6, 40)
        XCTAssertNil(begun(now: now, suppressedUntil: planned), "ausgesetzt")
        XCTAssertNil(begun(now: now, withCalendar: false), "ohne Kalender")
        XCTAssertNil(AlarmSchedule.begunNight(alarm(), plannedTarget: nil, plannedCalendar: zurich, now: now, grace: grace), "ohne Zielzeit")
    }

    func testSameZoneHarmless() {
        let now = at(6, 40)
        XCTAssertEqual(begun(now: now), AlarmSchedule.nextPlan(alarm(), now: now, calendar: zurich))
    }
}
