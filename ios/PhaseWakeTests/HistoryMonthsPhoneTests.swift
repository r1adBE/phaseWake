import PhaseWakeCore
import XCTest
@testable import PhaseWake

/// Übersicht «Monate» mit allen Nächten der gezeigten Monate; «Tage» und «Wochen» wie die übrigen Kennzahlen. Wie
/// `HistoryChartsMonthsTest` (Android).
final class HistoryMonthsPhoneTests: XCTestCase {
    func testMonthsGetTheWholeOldestMonth() throws {
        var calendar = Calendar.gregorian(in: TimeZone(identifier: "UTC")!)
        calendar.locale = Locale(identifier: "en_US_POSIX")
        // 2026-11-18 12:00 UTC.
        let now = try XCTUnwrap(calendar.date(from: DateComponents(year: 2026, month: 11, day: 18, hour: 12)))
        // Eine Nacht je Tag vom 1. Juni bis gestern: 23:00 bis 06:30, 7 Std. Schlaf.
        var all: [SleepSummary] = []
        var day = try XCTUnwrap(calendar.date(from: DateComponents(year: 2026, month: 6, day: 1, hour: 23)))
        while day < now {
            all.append(SleepSummary(start: day, end: day.addingTimeInterval(450 * 60), asleepMinutes: 420))
            day = try XCTUnwrap(calendar.date(byAdding: .day, value: 1, to: day))
        }
        // Wie die übrigen Kennzahlen: Sitzungen der letzten 91 Tage.
        let recent = all.filter { $0.end > now.addingTimeInterval(-91 * 86400) }
        func charts(_ month: [SleepSummary]?) -> HistoryChartsData {
            HistoryChartsData.of(HistoryChartsData.Input(sleeps: recent, monthSleeps: month, goalMinutes: 450),
                                 now: now, calendar: calendar)
        }
        let full = charts(all)
        let before = charts(nil)
        let oldest = try XCTUnwrap(full.overview[.months]?.bars.first)
        // Älteste Säule: alle Nächte ihres Monats (Nachtdaten erstes bis letztes), nicht nur die der 90 Tage.
        let inMonth = all.filter {
            let date = SleepNotes.nightDate($0.start, calendar: calendar)
            return date >= oldest.first && date <= oldest.last
        }
        XCTAssertEqual(oldest.nights, inMonth.count)
        XCTAssertEqual(inMonth.count, 31)
        XCTAssertLessThan(try XCTUnwrap(before.overview[.months]?.bars.first?.nights), oldest.nights)
        // «Tage» und «Wochen» unverändert.
        XCTAssertEqual(before.overview[.days], full.overview[.days])
        XCTAssertEqual(before.overview[.weeks], full.overview[.weeks])
    }
}
