import XCTest
@testable import PhaseWakeCore

/// Überspringen nach frühem Wecken (erledigt bis), auf Reisen (Wandzeit), beim Bearbeiten und der Feiertagshinweis
/// der Startseite. Gegenstück zu `SkipDoneTest` (Kotlin).
final class SkipDoneTests: XCTestCase {
    private let weekdays: Set<Weekday> = [.monday, .tuesday, .wednesday, .thursday, .friday]

    private func calendar(_ zone: String) -> Calendar {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: zone)!
        return c
    }

    private var zurich: Calendar { calendar("Europe/Zurich") }
    private var newYork: Calendar { calendar("America/New_York") }
    private var tokyo: Calendar { calendar("Asia/Tokyo") }

    /// Ortszeit «yyyy-MM-ddTHH:mm» in `zone`.
    private func at(_ text: String, _ zone: String = "Europe/Zurich") -> Date {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: zone)!
        f.dateFormat = "yyyy-MM-dd'T'HH:mm"
        return f.date(from: text)!
    }

    private func wall(_ text: String) -> Int64 { AlarmSchedule.millis(at(text, "UTC")) }

    private func alarm(id: String = "a", hour: Int = 7, minute: Int = 0, days: Set<Weekday>? = nil, window: Int = 30,
                       enabled: Bool = true, label: String = "", skipUntil: Int64 = 0, skipHolidays: Bool = false) -> AlarmSpec {
        try! AlarmSpec(id: id, hour: hour, minute: minute, days: days ?? weekdays, windowMinutes: window, enabled: enabled,
                       label: label, skipUntil: skipUntil, skipHolidays: skipHolidays)
    }

    func testNightDoneTargetAheadSkipsNextOccurrence() {
        // Do 29.10., früh geweckt um 6:41; um 6:50 «Nächstes Mal überspringen». Die Nacht bis 7:00 ist erledigt.
        let now = at("2026-10-29T06:50")
        let done = at("2026-10-29T07:00")
        let skipped = AlarmSchedule.skipNext(alarm(), now: now, doneUntil: done, calendar: zurich)
        XCTAssertEqual(skipped.skipUntil, wall("2026-10-30T07:00"))
        XCTAssertEqual(AlarmSchedule.nextSkipDate(alarm(), now: now, doneUntil: done, calendar: zurich), "2026-10-30")
        XCTAssertEqual(AlarmSchedule.skippedDate(skipped, now: now, calendar: zurich), "2026-10-30")
        XCTAssertEqual(AlarmSchedule.nextTarget(skipped, now: at("2026-10-29T07:30"), calendar: zurich), at("2026-11-02T07:00"))
        // Sperre des Zweitweckers am Handy: Zielzeit + 2 Min.
        XCTAssertEqual(AlarmSchedule.skipNext(alarm(), now: now, doneUntil: done.addingTimeInterval(120), calendar: zurich).skipUntil,
                       wall("2026-10-30T07:00"))
        // Ohne erledigte Nacht (oder erledigt in der Vergangenheit) gilt der heutige Termin.
        XCTAssertEqual(AlarmSchedule.nextSkipDate(alarm(), now: now, calendar: zurich), "2026-10-29")
        XCTAssertEqual(AlarmSchedule.skipNext(alarm(), now: now, doneUntil: at("2026-10-28T07:00"), calendar: zurich).skipUntil,
                       wall("2026-10-29T07:00"))
    }

    func testNextSkipDateWithoutOccurrence() {
        let now = at("2026-10-28T22:00")
        XCTAssertNil(AlarmSchedule.nextSkipDate(alarm(days: []), now: now, calendar: zurich))
        XCTAssertNil(AlarmSchedule.nextSkipDate(alarm(enabled: false), now: now, calendar: zurich))
    }

    func testEarliestPlanRespectsDoneUntil() {
        let now = at("2026-10-29T06:50")
        let done = ["a": at("2026-10-29T07:00")]
        let a = alarm(id: "a")
        let b = alarm(id: "b", hour: 8)
        XCTAssertEqual(AlarmSchedule.earliestPlan([a, b], now: now, calendar: zurich)?.alarmId, "a")
        // Der zweite Wecker am selben Morgen bleibt.
        XCTAssertEqual(AlarmSchedule.earliestPlan([a, b], now: now, doneUntil: done, calendar: zurich)?.target, at("2026-10-29T08:00"))
        XCTAssertEqual(AlarmSchedule.earliestPlan([a], now: now, doneUntil: done, calendar: zurich)?.target, at("2026-10-30T07:00"))
    }

    func testSkipKeepsCalendarDayWhenTravellingWest() {
        // In Zürich am Mittwochabend Do 7:00 übersprungen, dann nach New York geflogen.
        let skipped = AlarmSchedule.skipNext(alarm(), now: at("2026-10-28T22:00"), calendar: zurich)
        XCTAssertEqual(skipped.skipUntil, wall("2026-10-29T07:00"))
        let nyMorning = at("2026-10-29T05:00", "America/New_York")
        XCTAssertEqual(AlarmSchedule.nextTarget(skipped, now: nyMorning, calendar: newYork), at("2026-10-30T07:00", "America/New_York"))
        XCTAssertEqual(AlarmSchedule.skippedDate(skipped, now: nyMorning, calendar: newYork), "2026-10-29")
        XCTAssertNil(AlarmSchedule.skippedDate(skipped, now: at("2026-10-29T07:00", "America/New_York"), calendar: newYork))
    }

    func testSkipKeepsCalendarDayWhenTravellingEast() {
        let skipped = AlarmSchedule.skipNext(alarm(), now: at("2026-10-28T20:00", "America/New_York"), calendar: newYork)
        let tokyoEvening = at("2026-10-28T23:00", "Asia/Tokyo")
        // Do 7:00 in Tokio fällt aus, Fr 7:00 nicht.
        XCTAssertEqual(AlarmSchedule.nextTarget(skipped, now: tokyoEvening, calendar: tokyo), at("2026-10-30T07:00", "Asia/Tokyo"))
        XCTAssertEqual(AlarmSchedule.skippedDate(skipped, now: tokyoEvening, calendar: tokyo), "2026-10-29")
    }

    func testWallMillisIgnoresZoneAndHandlesGap() {
        XCTAssertEqual(AlarmSchedule.wallMillis(at("2026-10-29T07:00"), calendar: zurich), 1_793_257_200_000)
        XCTAssertEqual(AlarmSchedule.wallMillis(at("2026-10-29T07:00", "America/New_York"), calendar: newYork), 1_793_257_200_000)
        // Zeitumstellung (28.3.2027, 2:30 fällt aus → 3:30): Übersprungen wird genau dieser Termin.
        let night = alarm(hour: 2, minute: 30, days: Set(Weekday.allCases))
        let skipped = AlarmSchedule.skipNext(night, now: at("2027-03-27T22:00"), calendar: zurich)
        XCTAssertEqual(skipped.skipUntil, wall("2027-03-28T03:30"))
        XCTAssertEqual(AlarmSchedule.nextTarget(skipped, now: at("2027-03-27T22:00"), calendar: zurich), at("2027-03-29T02:30"))
    }

    func testKeepSkipOnlyForSameSchedule() {
        let skip = wall("2026-10-29T07:00")
        let old = alarm(skipUntil: skip)
        XCTAssertEqual(AlarmSchedule.keepSkip(old: old, edited: alarm(window: 20, enabled: false, label: "Neu", skipUntil: skip)), skip)
        XCTAssertEqual(AlarmSchedule.keepSkip(old: old, edited: alarm(hour: 8, skipUntil: skip)), 0)
        XCTAssertEqual(AlarmSchedule.keepSkip(old: old, edited: alarm(minute: 15, skipUntil: skip)), 0)
        XCTAssertEqual(AlarmSchedule.keepSkip(old: old, edited: alarm(days: [.monday], skipUntil: skip)), 0)
        XCTAssertEqual(AlarmSchedule.keepSkip(old: old, edited: alarm(days: [], skipUntil: skip)), 0)
    }

    func testHolidayNoteOnlyUpToNextPlan() {
        let holidays: Set<String> = ["2026-10-29"]
        let a = alarm(id: "a", skipHolidays: true)
        let b = alarm(id: "b", hour: 9, days: Set(Weekday.allCases))
        // Dienstag: B weckt am Mittwoch zuerst, der Feiertag am Donnerstag ist noch kein Hinweis.
        XCTAssertNil(AlarmSchedule.holidayNote([a, b], now: at("2026-10-27T22:00"), calendar: zurich, holidays: holidays))
        // Mittwochabend: B weckt am Feiertag selbst (ohne Feiertagsregel) → Hinweis für A.
        XCTAssertEqual(AlarmSchedule.holidayNote([a, b], now: at("2026-10-28T22:00"), calendar: zurich, holidays: holidays), "2026-10-29")
        XCTAssertEqual(AlarmSchedule.holidayNote([a], now: at("2026-10-27T22:00"), calendar: zurich, holidays: holidays), "2026-10-29")
        XCTAssertNil(AlarmSchedule.holidayNote([b], now: at("2026-10-28T22:00"), calendar: zurich, holidays: holidays))
        XCTAssertNil(AlarmSchedule.holidayNote([], now: at("2026-10-28T22:00"), calendar: zurich, holidays: holidays))
        // Jeder Tag Feiertag: kein Plan, der Hinweis gilt trotzdem.
        let all = Set((0...(AlarmSchedule.maxSearchDays + 1)).map { NightDate.adding($0, to: "2026-10-28") })
        XCTAssertEqual(AlarmSchedule.holidayNote([a], now: at("2026-10-28T22:00"), calendar: zurich, holidays: all), "2026-10-29")
    }

    func testHolidayNoteAfterEarlyWake() {
        let holidays: Set<String> = ["2026-10-30"]
        let a = alarm(id: "a", skipHolidays: true)
        let now = at("2026-10-29T06:50")
        XCTAssertNil(AlarmSchedule.holidayNote([a], now: now, calendar: zurich, holidays: holidays))
        let done = ["a": at("2026-10-29T07:00")]
        XCTAssertEqual(AlarmSchedule.holidayNote([a], now: now, doneUntil: done, calendar: zurich, holidays: holidays), "2026-10-30")
    }
}
