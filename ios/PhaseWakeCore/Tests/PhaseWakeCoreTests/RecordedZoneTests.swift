import XCTest
import PhaseWakeCore

/// Fremde Nächte überall in der Zone ihrer Aufzeichnung (Audit F-3, Entscheid r1AD 14.11.2026): Nachtdatum, Diagramme,
/// Übersicht, Trends und Regelmässigkeit; ohne Zone die des Geräts. Gegenstück zu `RecordedZoneTest` (Kotlin), gleiche Fälle.
final class RecordedZoneTests: XCTestCase {
    private let zurichZone = TimeZone(identifier: "Europe/Zurich")!
    private let tokyoZone = TimeZone(identifier: "Asia/Tokyo")!

    private func calendar(_ zone: String) -> Calendar {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: zone)!
        return c
    }

    private var zurich: Calendar { calendar("Europe/Zurich") }
    private var tokyo: Calendar { calendar("Asia/Tokyo") }
    private var honolulu: Calendar { calendar("Pacific/Honolulu") }

    private func t(_ text: String) -> Date { ISO8601DateFormatter().date(from: text)! }

    /// Nacht in Tokio am Nachtdatum `day` (Oktober 2026), 23:00–07:00 Ortszeit (+09:00).
    private func tokyoNight(_ day: Int, zone: TimeZone? = TimeZone(identifier: "Asia/Tokyo")!) -> HealthSession {
        HealthSession(start: t(String(format: "2026-10-%02dT14:00:00Z", day)), end: t(String(format: "2026-10-%02dT22:00:00Z", day)),
                      timeZone: zone)
    }

    /// Nacht in Zürich am Nachtdatum `day` (Oktober 2026, Sommerzeit), 23:00–07:00 Ortszeit (+02:00).
    private func zurichNight(_ day: Int) -> HealthSession {
        HealthSession(start: t(String(format: "2026-10-%02dT21:00:00Z", day)), end: t(String(format: "2026-10-%02dT05:00:00Z", day + 1)),
                      timeZone: zurichZone)
    }

    private func summaries(_ sessions: [HealthSession], _ calendar: Calendar) -> [SleepSummary] {
        HealthHistory.summaries(sessions, clearedAt: nil, calendar: calendar)
    }

    private func dates(_ sessions: [HealthSession], _ calendar: Calendar) -> [String] {
        HealthHistory.merge(nights: [], sessions: sessions, clearedAt: nil, calendar: calendar).map(\.date)
    }

    func testTokyoNightSeenInZurichKeepsItsClock() {
        let sleeps = summaries([tokyoNight(1)], zurich)
        let bars = ChartData.sleepBars(sleeps, calendar: zurich, today: "2026-10-02", nextTarget: nil, goalMinutes: 480)!
        XCTAssertEqual(bars.bars.last, SleepBar(date: "2026-10-01", startMinute: 11 * 60, endMinute: 19 * 60))
        let overview = SleepOverviews.of(sleeps, calendar: zurich, today: "2026-10-02", goalMinutes: 480, unit: .days)!
        XCTAssertEqual(overview.bedtimeMinute, 11 * 60)
        XCTAssertEqual(overview.wakeMinute, 19 * 60)
        XCTAssertEqual(dates([tokyoNight(1)], zurich), ["2026-10-01"])
    }

    func testNightDateFollowsTheRecordingEvenFarAway() {
        // In Honolulu (−10:00) läge die Mitte der Nacht am 1. Oktober um 08:00, also am Nachtdatum 30. September.
        let session = tokyoNight(1)
        let sleeps = summaries([session], honolulu)
        XCTAssertEqual(dates([session], honolulu), ["2026-10-01"])
        XCTAssertEqual(NoteEffect.asleepByNight(sleeps, calendar: honolulu), ["2026-10-01": 480])
        XCTAssertEqual(SleepGoal.nightsOf(sleeps, calendar: honolulu).map(\.date), ["2026-10-01"])
        XCTAssertEqual(Streak.reachedByNight(sleeps, calendar: honolulu, goalMinutes: 480), ["2026-10-01": true])
        XCTAssertEqual(SleepNotes.nightDate(of: session, calendar: honolulu), "2026-10-01")
    }

    func testZurichAndTokyoMixedCountAsOneBedtime() {
        let sessions = (1...3).map { zurichNight($0) } + (4...5).map { tokyoNight($0) }
        let sleeps = summaries(sessions, zurich)
        XCTAssertEqual(sleeps.count, 5)
        let regular = Regularity.count(Regularity.bedtimes(of: sleeps, calendar: zurich), calendar: zurich)
        XCTAssertEqual(regular.regular, 5)
        XCTAssertEqual(regular.total, 5)
        let trends = Trends.compute(nights: [], sleeps: sleeps, now: t("2026-10-06T12:00:00Z"), calendar: zurich)
        XCTAssertEqual(trends.bedtimeSpreadMinutes, 0)
        let bars = ChartData.sleepBars(sleeps, calendar: zurich, today: "2026-10-06", nextTarget: nil, goalMinutes: 480)!
        XCTAssertEqual(bars.bars.filter(\.hasData).map(\.startMinute), Array(repeating: 11 * 60, count: 5))
        let week = WeeklySummary.of(nights: [], sleeps: sleeps, notes: [:], calendar: zurich, goalMinutes: 480, weekEnd: "2026-10-05")
        XCTAssertEqual(week.regular, 5)
        XCTAssertEqual(week.regularCounted, 5)
    }

    func testNightOverTheClockChangeKeepsLocalTimes() {
        // 24./25. Oktober 2026: Zürich stellt um 03:00 auf 02:00 zurück. 23:00 (+02:00) bis 07:00 (+01:00), 9 Stunden.
        let recorded = HealthSession(start: t("2026-10-24T21:00:00Z"), end: t("2026-10-25T06:00:00Z"), timeZone: zurichZone)
        let plain = HealthSession(start: recorded.start, end: recorded.end)
        for (session, device) in [(recorded, tokyo), (plain, zurich)] {
            let sleeps = summaries([session], device)
            XCTAssertEqual(sleeps.map(\.asleepMinutes), [540])
            let bars = ChartData.sleepBars(sleeps, calendar: device, today: "2026-10-25", nextTarget: nil, goalMinutes: 480)!
            XCTAssertEqual(bars.bars.last, SleepBar(date: "2026-10-24", startMinute: 11 * 60, endMinute: 19 * 60))
            let overview = SleepOverviews.of(sleeps, calendar: device, today: "2026-10-25", goalMinutes: 480, unit: .days)!
            XCTAssertEqual(overview.bedtimeMinute, 11 * 60)
            XCTAssertEqual(overview.wakeMinute, 19 * 60)
            XCTAssertEqual(dates([session], device), ["2026-10-24"])
        }
    }

    func testWithoutZoneTheDeviceCounts() {
        let sleeps = summaries([tokyoNight(1, zone: nil)], zurich)
        // 14:00–22:00 UTC sind in Zürich 16:00–00:00.
        let bars = ChartData.sleepBars(sleeps, calendar: zurich, today: "2026-10-02", nextTarget: nil, goalMinutes: 480)!
        XCTAssertEqual(bars.bars.last, SleepBar(date: "2026-10-01", startMinute: 4 * 60, endMinute: 12 * 60))
        XCTAssertNil(sleeps.first?.timeZone)
        XCTAssertEqual(sleeps.first?.localCalendar(zurich).timeZone, zurichZone)
        XCTAssertEqual(summaries([tokyoNight(1)], zurich).first?.timeZone, tokyoZone)
    }
}
