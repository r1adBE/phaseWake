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

    func testSkippedWeeklyAlarmFindsTheTermAfterNextWeek() {
        // Audit BE-4 (mayDrop): Wecker nur montags, Montag 2.11. übersprungen, heute Dienstag 27.10. → Montag 9.11.,
        // 13 Tage voraus, hinter der gewohnten Suche von 8 Tagen.
        let monday = alarm(days: [.monday])
        let now = at(2026, 10, 27, 8, 0)
        let skipped = AlarmSchedule.skipNext(monday, now: now, calendar: zurich)
        XCTAssertEqual(skipped.skipUntil, AlarmSchedule.wallMillis(at(2026, 11, 2, 7, 0), calendar: zurich))
        XCTAssertEqual(AlarmSchedule.nextTarget(skipped, now: now, calendar: zurich), at(2026, 11, 9, 7, 0))
        // Feiertag am 2.11. mit skipHolidays: ebenso 9.11.; dazu der 9.11. übersprungen: 16.11. (20 Tage).
        let holidays: Set<String> = ["2026-11-02"]
        XCTAssertEqual(AlarmSchedule.nextTarget(monday.with(skipHolidays: true), now: now, calendar: zurich, holidays: holidays), at(2026, 11, 9, 7, 0))
        let both = monday.with(skipHolidays: true).with(skipUntil: AlarmSchedule.wallMillis(at(2026, 11, 9, 7, 0), calendar: zurich))
        XCTAssertEqual(AlarmSchedule.nextTarget(both, now: now, calendar: zurich, holidays: holidays), at(2026, 11, 16, 7, 0))
        // Grenze der Suche: ein Jahr Feiertage → nil statt endloser Suche; 370 Tage.
        XCTAssertEqual(AlarmSchedule.maxSearchDays, 370)
        let everyMonday = Set((0...60).map { NightDate.adding($0 * 7, to: "2026-11-02") })
        XCTAssertNil(AlarmSchedule.nextTarget(monday.with(skipHolidays: true), now: now, calendar: zurich, holidays: everyMonday))
    }

    func testOneShot() {
        XCTAssertEqual(AlarmSchedule.nextTarget(alarm(), now: at(2026, 10, 28, 1, 0), calendar: zurich), at(2026, 10, 28, 7, 0))
        XCTAssertEqual(AlarmSchedule.nextTarget(alarm(), now: at(2026, 10, 28, 7, 0), calendar: zurich), at(2026, 10, 29, 7, 0))
    }

    func testRunningWindow() {
        let plan = AlarmSchedule.nextPlan(alarm(), now: at(2026, 10, 28, 6, 45), calendar: zurich)!
        XCTAssertEqual(plan.target, at(2026, 10, 28, 7, 0))
        XCTAssertEqual(plan.windowStart, at(2026, 10, 28, 6, 40))
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

    /// Wie `SnoozeAndOnsetTest` (Kotlin).
    func testSnoozeAndOnset() {
        XCTAssertTrue(Snooze.canSnooze(0))
        XCTAssertTrue(Snooze.canSnooze(2))
        XCTAssertFalse(Snooze.canSnooze(3))
        let six = Date(timeIntervalSince1970: 1_793_167_200)
        XCTAssertEqual(Snooze.nextRing(six), six.addingTimeInterval(300))
        XCTAssertNil(SleepOnset.find([Double?](repeating: 0.0, count: 29)))
        let restless = [Double?](repeating: 0.05, count: 10)
        let shortRest = [Double?](repeating: 0.0, count: 20)
        let still = [Double?](repeating: 0.0, count: 30)
        XCTAssertEqual(SleepOnset.find(restless + shortRest + [nil] + still), 31)
        XCTAssertNil(SleepOnset.find([Double?](repeating: 0.002, count: 60)))
    }

    /// Audit c3b8b01 E-4: Zucker im Schlaf (eine Epoche, Nachbarn ruhig) brechen die Strecke nicht, höchstens zwei (wie Kotlin).
    func testSingleTwitchesDoNotBreakTheStretch() {
        func night(_ moved: Int...) -> [Double?] { (0..<45).map { moved.contains($0) ? 0.2 : 0.001 } }
        XCTAssertEqual(SleepOnset.find(night(10), threshold: 0.002), 0)
        XCTAssertEqual(SleepOnset.find(night(10, 20), threshold: 0.002), 0)
        // Ein dritter in denselben 30 Epochen: erst nach dem ersten.
        XCTAssertEqual(SleepOnset.find(night(10, 20, 25), threshold: 0.002), 11)
        // Zwei nacheinander (1 Min Bewegung) sind kein Zucker: die Strecke beginnt danach.
        XCTAssertEqual(SleepOnset.find(night(10, 11), threshold: 0.002), 12)
        // Erste und letzte Epoche der Strecke müssen ruhig sein.
        XCTAssertNil(SleepOnset.find([Double?](repeating: 0.001, count: 29) + [0.2], threshold: 0.002))
        XCTAssertEqual(SleepOnset.maxTwitches, 2)
        XCTAssertEqual(SleepOnset.stillEpochs, 30)
    }

    /// Abgeleitete Eigenschaften des Weckers (wie `AlarmSpec` in Kotlin).
    func testAlarmFlags() throws {
        XCTAssertTrue(alarm().isOneShot)
        XCTAssertFalse(alarm(days: [.monday]).isOneShot)
        XCTAssertTrue(alarm().phoneBackup)
        XCTAssertFalse(try AlarmSpec(id: "a", hour: 7, minute: 0, phone: .off).phoneBackup)
        XCTAssertTrue(try AlarmSpec(id: "a", hour: 7, minute: 0, phone: .vibrate).phoneBackup)
        XCTAssertEqual(SunriseDevice.allCases.map(\.onPhone), [true, false, true])
        XCTAssertEqual(SunriseDevice.allCases.map(\.onWatch), [false, true, true])
        let both = try AlarmSpec(id: "a", hour: 7, minute: 0, sunrise: .window, sunriseDevice: .both)
        XCTAssertEqual(both.phoneSunrise, .window)
        XCTAssertEqual(both.watchSunrise, .window)
        XCTAssertEqual(try AlarmSpec(id: "a", hour: 7, minute: 0, sunrise: .ring).watchSunrise, .off)
        // Mittwoch, 28.10.2026; Sonntag, 1.11.2026.
        XCTAssertEqual(Weekday(date: at(2026, 10, 28, 7, 0), calendar: zurich), .wednesday)
        XCTAssertEqual(Weekday(date: at(2026, 11, 1, 23, 59), calendar: zurich), .sunday)
        XCTAssertLessThan(Weekday.monday, Weekday.sunday)
        XCTAssertEqual(AlarmSchedule.windowStart(at(2026, 10, 28, 7, 0), windowMinutes: 20), at(2026, 10, 28, 6, 40))
    }
}
