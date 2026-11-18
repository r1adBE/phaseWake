import PhaseWakeCore
import XCTest
@testable import PhaseWake

/// Schlafziel-Zeile der Startseite, iPhone: wie Android und die Trends im Verlauf die jüngsten 7 Nachtdaten aus dem
/// Fenster der Kennzahlen (90 Tage), nicht nur die Nächte der letzten 7 Tage.
final class HomeSleepWeekPhoneTests: XCTestCase {
    func testGoalCountsTheNewestSevenNightDates() throws {
        var calendar = Calendar.gregorian(in: TimeZone(identifier: "UTC")!)
        calendar.locale = Locale(identifier: "en_US_POSIX")
        let now = try XCTUnwrap(calendar.date(from: DateComponents(year: 2026, month: 11, day: 18, hour: 12)))
        /// Sitzung ab 23:00 `daysBack` Tage vor heute, `minutes` lang leichter Schlaf.
        func session(_ daysBack: Int, _ minutes: Int) throws -> HealthSession {
            let day = try XCTUnwrap(calendar.date(byAdding: .day, value: -daysBack, to: now))
            let start = try XCTUnwrap(calendar.date(bySettingHour: 23, minute: 0, second: 0, of: day))
            let end = start.addingTimeInterval(Double(minutes) * 60)
            return HealthSession(start: start, end: end, stages: [StageSpan(start: start, end: end, stage: .light)])
        }
        // Letzte 7 Tage: 3 Nächte über dem Ziel; davor (10–14 Tage zurück) 5 Nächte darunter.
        let sessions = try [1, 2, 3].map { try session($0, 480) } + (10...14).map { try session($0, 300) }
        let week = try XCTUnwrap(HomeSleepWeek.of(nights: [], sessions: sessions, clearedAt: nil, goalMinutes: 450,
                                                  now: now, calendar: calendar))
        XCTAssertEqual(week.asleepMinutes, 480)
        XCTAssertEqual(week.reached, 3)
        XCTAssertEqual(week.nights, 7)
        XCTAssertGreaterThanOrEqual(HomeSleepWeek.days, NightHistory.insightNights + 1)
    }
}
