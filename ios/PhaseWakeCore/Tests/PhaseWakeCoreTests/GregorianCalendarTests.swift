import XCTest
@testable import PhaseWakeCore

/// Nachtdaten und Feiertage bleiben gregorianisch, auch wenn das Gerät einen anderen Kalender nutzt (buddhistisch:
/// Jahr 2569 statt 2026). Sonst passten Notizen, Verlauf und Feiertage nicht mehr zu den Daten «yyyy-MM-dd».
final class GregorianCalendarTests: XCTestCase {
    private let zone = TimeZone(identifier: "Europe/Zurich")!

    private var buddhist: Calendar {
        var c = Calendar(identifier: .buddhist)
        c.timeZone = zone
        return c
    }

    /// Ortszeit Zürich «yyyy-MM-ddTHH:mm» (gregorianisch).
    private func at(_ text: String) -> Date {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.calendar = Calendar(identifier: .gregorian)
        f.timeZone = zone
        f.dateFormat = "yyyy-MM-dd'T'HH:mm"
        return f.date(from: text)!
    }

    func testBuddhistCalendarStillGivesGregorianDates() {
        // Voraussetzung: Der buddhistische Kalender zählt die Jahre wirklich anders.
        XCTAssertEqual(buddhist.component(.year, from: at("2026-10-28T22:00")), 2569)
        XCTAssertEqual(SleepNotes.dateString(at("2026-10-28T22:00"), calendar: buddhist), "2026-10-28")
        XCTAssertEqual(SleepNotes.nightDate(at("2026-10-29T06:30"), calendar: buddhist), "2026-10-28")
        XCTAssertEqual(NightDate.today(at("2026-10-29T00:30"), calendar: buddhist), "2026-10-29")
        // Zeitzone des Kalenders gilt weiter: 00:30 in Zürich ist in UTC noch der Vortag.
        var utc = buddhist
        utc.timeZone = TimeZone(identifier: "UTC")!
        XCTAssertEqual(NightDate.today(at("2026-10-29T00:30"), calendar: utc), "2026-10-28")
    }

    func testHolidaysResolveWithBuddhistToday() {
        let today = NightDate.today(at("2026-10-29T08:00"), calendar: buddhist)
        XCTAssertEqual(Holidays.resolve(national: ["2026-10-31", "2569-10-31"], regional: [], today: today, days: 10), ["2026-10-31"])
    }

    func testHolidaySkippedWithBuddhistCalendar() throws {
        let weekdays: Set<Weekday> = [.monday, .tuesday, .wednesday, .thursday, .friday]
        let alarm = try AlarmSpec(id: "a", hour: 7, minute: 0, days: weekdays, skipHolidays: true)
        // Donnerstag, 29. Oktober 2026, ist Feiertag: geweckt wird erst am Freitag.
        let target = AlarmSchedule.nextTarget(alarm, now: at("2026-10-28T22:00"), calendar: buddhist, holidays: ["2026-10-29"])
        XCTAssertEqual(target, at("2026-10-30T07:00"))
        XCTAssertEqual(AlarmSchedule.skippedHoliday(alarm, now: at("2026-10-28T22:00"), calendar: buddhist, holidays: ["2026-10-29"]),
                       "2026-10-29")
    }

    func testGregorianLocalFollowsCurrentZone() {
        XCTAssertEqual(Calendar.gregorianLocal.identifier, .gregorian)
        XCTAssertEqual(Calendar.gregorianLocal.timeZone.identifier, TimeZone.current.identifier)
        XCTAssertEqual(Calendar.gregorian(in: zone).timeZone, zone)
    }
}
