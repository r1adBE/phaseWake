import XCTest
@testable import PhaseWakeCore

/// «Nächstes Mal überspringen» und «An Feiertagen nicht wecken»; Gegenstück zu `SkipTest` (Kotlin).
final class SkipTests: XCTestCase {
    private var zurich: Calendar {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "Europe/Zurich")!
        return c
    }

    private let weekdays: Set<Weekday> = [.monday, .tuesday, .wednesday, .thursday, .friday]

    /// Ortszeit Zürich «yyyy-MM-ddTHH:mm».
    private func at(_ text: String) -> Date {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "Europe/Zurich")!
        f.dateFormat = "yyyy-MM-dd'T'HH:mm"
        return f.date(from: text)!
    }

    /// Wandzeit wie `AlarmSchedule.wallMillis`: Ortszeit, gezählt als UTC.
    private func wall(_ text: String) -> Int64 {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")!
        f.dateFormat = "yyyy-MM-dd'T'HH:mm"
        return AlarmSchedule.millis(f.date(from: text)!)
    }

    private func alarm(id: String = "a", hour: Int = 7, days: Set<Weekday>? = nil, enabled: Bool = true,
                       skipUntil: Int64 = 0, skipHolidays: Bool = false) -> AlarmSpec {
        try! AlarmSpec(id: id, hour: hour, minute: 0, days: days ?? weekdays, enabled: enabled, skipUntil: skipUntil, skipHolidays: skipHolidays)
    }

    /// Mittwoch, 28. Oktober 2026, 22:00.
    private var evening: Date { at("2026-10-28T22:00") }

    func testSkipNextSkipsExactlyOneOccurrence() {
        let skipped = AlarmSchedule.skipNext(alarm(), now: evening, calendar: zurich)
        XCTAssertEqual(skipped.skipUntil, wall("2026-10-29T07:00"))
        XCTAssertEqual(AlarmSchedule.nextTarget(skipped, now: evening, calendar: zurich), at("2026-10-30T07:00"))
        XCTAssertEqual(AlarmSchedule.skippedDate(skipped, now: evening, calendar: zurich), "2026-10-29")
        // Nach dem übersprungenen Termin gilt alles wieder normal.
        XCTAssertNil(AlarmSchedule.skippedDate(skipped, now: at("2026-10-29T07:00"), calendar: zurich))
        XCTAssertEqual(AlarmSchedule.nextTarget(skipped, now: at("2026-10-29T08:00"), calendar: zurich), at("2026-10-30T07:00"))
        XCTAssertTrue(skipped.enabled)
    }

    func testSkipDuringRunningWindowSkipsThisMorning() {
        let skipped = AlarmSchedule.skipNext(alarm(), now: at("2026-10-29T06:45"), calendar: zurich)
        XCTAssertEqual(AlarmSchedule.nextPlan(skipped, now: at("2026-10-29T06:45"), calendar: zurich)?.target, at("2026-10-30T07:00"))
    }

    func testSkipOverWeekend() {
        let skipped = AlarmSchedule.skipNext(alarm(), now: at("2026-10-30T07:30"), calendar: zurich)
        // Nächster Termin wäre Montag 2.11.; übersprungen → Dienstag.
        XCTAssertEqual(skipped.skipUntil, wall("2026-11-02T07:00"))
        XCTAssertEqual(AlarmSchedule.nextTarget(skipped, now: at("2026-10-30T20:00"), calendar: zurich), at("2026-11-03T07:00"))
    }

    func testUndoSkipRestoresNextOccurrence() {
        let undone = AlarmSchedule.undoSkip(AlarmSchedule.skipNext(alarm(), now: evening, calendar: zurich))
        XCTAssertEqual(undone.skipUntil, 0)
        XCTAssertEqual(AlarmSchedule.nextTarget(undone, now: evening, calendar: zurich), at("2026-10-29T07:00"))
        XCTAssertNil(AlarmSchedule.skippedDate(undone, now: evening, calendar: zurich))
    }

    func testOneShotIgnoresSkipAndHolidays() {
        let once = alarm(days: [])
        XCTAssertEqual(AlarmSchedule.skipNext(once, now: evening, calendar: zurich), once)
        let forced = alarm(days: [], skipUntil: wall("2026-10-29T07:00"), skipHolidays: true)
        let holidays: Set<String> = ["2026-10-29"]
        XCTAssertEqual(AlarmSchedule.nextTarget(forced, now: evening, calendar: zurich, holidays: holidays), at("2026-10-29T07:00"))
        XCTAssertNil(AlarmSchedule.skippedDate(forced, now: evening, calendar: zurich))
        XCTAssertNil(AlarmSchedule.skippedHoliday(forced, now: evening, calendar: zurich, holidays: holidays))
    }

    func testDisabledAlarmIsNotSkipped() {
        let off = alarm(enabled: false)
        XCTAssertEqual(AlarmSchedule.skipNext(off, now: evening, calendar: zurich), off)
    }

    func testHolidaysAreSkippedOnlyWhenWanted() {
        let holidays: Set<String> = ["2026-10-29", "2026-10-30"]
        XCTAssertEqual(AlarmSchedule.nextTarget(alarm(), now: evening, calendar: zurich, holidays: holidays), at("2026-10-29T07:00"))
        let wanted = alarm(skipHolidays: true)
        XCTAssertEqual(AlarmSchedule.nextTarget(wanted, now: evening, calendar: zurich, holidays: holidays), at("2026-11-02T07:00"))
        XCTAssertEqual(AlarmSchedule.skippedHoliday(wanted, now: evening, calendar: zurich, holidays: holidays), "2026-10-29")
        XCTAssertNil(AlarmSchedule.skippedHoliday(alarm(), now: evening, calendar: zurich, holidays: holidays))
        XCTAssertNil(AlarmSchedule.skippedHoliday(wanted, now: evening, calendar: zurich, holidays: []))
        // Kein Feiertag als nächster Termin: nichts anzuzeigen.
        XCTAssertNil(AlarmSchedule.skippedHoliday(wanted, now: at("2026-10-30T08:00"), calendar: zurich, holidays: holidays))
    }

    func testSkipNextJumpsOverHolidays() {
        let holidays: Set<String> = ["2026-10-29"]
        let skipped = AlarmSchedule.skipNext(alarm(skipHolidays: true), now: evening, calendar: zurich, holidays: holidays)
        XCTAssertEqual(skipped.skipUntil, wall("2026-10-30T07:00"))
        XCTAssertEqual(AlarmSchedule.nextTarget(skipped, now: evening, calendar: zurich, holidays: holidays), at("2026-11-02T07:00"))
    }

    func testEveryDayHolidayEndsWithoutTarget() {
        let all = Set((0...(AlarmSchedule.maxSearchDays + 1)).map { NightDate.adding($0, to: "2026-10-28") })
        XCTAssertNil(AlarmSchedule.nextTarget(alarm(skipHolidays: true), now: evening, calendar: zurich, holidays: all))
    }

    func testEarliestPlanAndBedtimeUseHolidays() {
        let holidays: Set<String> = ["2026-10-29"]
        let a = alarm(id: "a", skipHolidays: true)
        let b = alarm(id: "b", hour: 9)
        XCTAssertEqual(AlarmSchedule.earliestPlan([a, b], now: evening, calendar: zurich, holidays: holidays)?.alarmId, "b")
        let reminder = Bedtime.next([a], from: evening, calendar: zurich, goalMinutes: 480, holidays: holidays)
        XCTAssertEqual(reminder?.target, at("2026-10-30T07:00"))
    }

    func testCodecKeepsSkipFields() throws {
        let a = alarm(skipUntil: 1_793_000_000_000, skipHolidays: true)
        let text = Codec.encodeAlarms([a])
        XCTAssertTrue(text.hasSuffix("|1793000000000|1\n"))
        XCTAssertEqual(try Codec.decodeAlarms(text), [a])
        // 14 Felder (vor dieser Version): nichts überspringen.
        let old = try XCTUnwrap(Codec.decodeAlarms("phasewake-alarms 1\na|7|0|MO|30|1|SOUND|0||||OFF|NONE|PHONE\n").first)
        XCTAssertEqual(old.skipUntil, 0)
        XCTAssertFalse(old.skipHolidays)
        // Unlesbare oder negative Werte: tolerant.
        let bad = try XCTUnwrap(Codec.decodeAlarms("phasewake-alarms 1\na|7|0|MO|30|1|SOUND|0||||OFF|NONE|PHONE|x|ja|neu\n").first)
        XCTAssertEqual(bad.skipUntil, 0)
        XCTAssertFalse(bad.skipHolidays)
        XCTAssertEqual(try Codec.decodeAlarms("phasewake-alarms 1\na|7|0|MO|30|1|SOUND|0||||OFF|NONE|PHONE|-5|0\n").first?.skipUntil, 0)
        // Kopien behalten die Felder.
        XCTAssertEqual(a.with(enabled: false).skipUntil, 1_793_000_000_000)
        XCTAssertFalse(a.with(skipHolidays: false).skipHolidays)
    }

    func testWatchFormatCapability() {
        // Eine Weckerzeile hat so viele Felder, wie die Uhr im Status meldet.
        let line = Codec.encodeAlarms([alarm()]).components(separatedBy: "\n")[1]
        XCTAssertEqual(line.components(separatedBy: "|").count, Codec.alarmFields)
        XCTAssertTrue(Codec.watchKnowsSkip(nil))
        XCTAssertTrue(Codec.watchKnowsSkip(Codec.alarmFields))
        XCTAssertFalse(Codec.watchKnowsSkip(0))
        XCTAssertFalse(Codec.watchKnowsSkip(14))
    }
}
