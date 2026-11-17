import PhaseWakeCore
import XCTest
@testable import PhaseWake

/// Bettzeit-Linie im Diagramm des Verlaufs, iPhone.
final class ChartBedtimePhoneTests: XCTestCase {
    /// Die Bettzeit-Linie im Diagramm rechnet die gelernte Einschlafdauer mit (die eine Bettzeit, wie Karte und
    /// Erinnerung): Wecker 07:00, Ziel 7½ Std., gelernt 15 Min → 23:15 statt 23:30.
    func testChartBedtimeIncludesLearnedLatency() throws {
        var calendar = Calendar.gregorian(in: TimeZone(identifier: "UTC")!)
        calendar.locale = Locale(identifier: "en_US_POSIX")
        let now = Date(timeIntervalSince1970: 1_793_000_000)
        let start = calendar.date(byAdding: .hour, value: -10, to: now)!
        let sleeps = [SleepSummary(start: start, end: start.addingTimeInterval(7 * 3600), asleepMinutes: 420)]
        let target = calendar.nextDate(after: now, matching: DateComponents(hour: 7, minute: 0), matchingPolicy: .nextTime)!
        func bedtime(_ latency: Int?) throws -> Int {
            let data = HistoryChartsData.of(nights: [], sleeps: sleeps, stages: [], notes: [:], nextTarget: target, goalMinutes: 450,
                                            latencyMinutes: latency, now: now, calendar: calendar)
            return try XCTUnwrap(data.period(ChartData.nights).bars?.bedtimeMinute)
        }
        // Minuten ab Mittag: 23:30 = 690, 23:15 = 675.
        XCTAssertEqual(try bedtime(nil), 690)
        XCTAssertEqual(try bedtime(15), 675)
    }
}
