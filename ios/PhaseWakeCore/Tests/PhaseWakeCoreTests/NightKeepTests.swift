import XCTest
import PhaseWakeCore

/// Wie `NightKeepTest` (Kotlin): Verlauf 2 Jahre auf dem iPhone, 90 Nächte auf der Uhr; Kennzahlen weiter über 90
/// Nächte bzw. Tage.
final class NightKeepTests: XCTestCase {
    private let day: TimeInterval = 86_400

    private func night(_ wokeAt: Date, _ id: String = "a") -> NightRecord {
        NightRecord(alarmId: id, windowStart: wokeAt.addingTimeInterval(-1200), target: wokeAt.addingTimeInterval(600),
                    wokeAt: wokeAt, reason: .lightSleep, snoozes: 0, epochs: [])
    }

    private func iso(_ text: String) -> Date { ISO8601DateFormatter().date(from: text)! }

    func testDocumentedNumbers() {
        XCTAssertEqual(NightHistory.days, 730)
        XCTAssertEqual(NightHistory.phoneNights, 800)
        XCTAssertEqual(NightHistory.watchNights, 90)
        XCTAssertEqual(NightHistory.insightNights, 90)
        XCTAssertEqual(NightHistory.maxDates, 731)
        XCTAssertEqual(NightKeep.phone.maxAge, 730 * day)
        XCTAssertNil(NightKeep.watch.maxAge)
    }

    func testPhoneKeepsExactlySevenHundredThirtyDays() {
        let now = iso("2026-11-18T08:00:00Z")
        let edge = now.addingTimeInterval(-730 * day)
        let nights = [night(edge.addingTimeInterval(-0.001), "alt"), night(edge, "grenze"), night(now, "neu")]
        // Genau 730 Tage: bleibt; 1 ms älter: weg.
        XCTAssertEqual(NightKeep.phone.trim(nights, now: now).map(\.alarmId), ["grenze", "neu"])
    }

    func testLeapYearMakesTwoCalendarYearsOneDayLonger() {
        // 2028 ist ein Schaltjahr: 730 Tage vor dem 01.03.2028 ist der 02.03.2026, nicht der 01.03.2026.
        let now = iso("2028-03-01T12:00:00Z")
        let kept = NightKeep.phone.trim([night(iso("2026-03-01T12:00:00Z"), "zwei-jahre"),
                                         night(iso("2026-03-02T12:00:00Z"), "730-tage")], now: now)
        XCTAssertEqual(kept.map(\.alarmId), ["730-tage"])
        // Ohne Schalttag dazwischen (2028-03-01 bis 2030-03-01) sind 730 Tage genau zwei Kalenderjahre.
        XCTAssertEqual(NightKeep.phone.trim([night(iso("2028-03-01T12:00:00Z"))], now: iso("2030-03-01T12:00:00Z"))
            .count, 1)
    }

    func testPhoneCapsTheNumberOfNights() {
        let now = iso("2026-11-18T08:00:00Z")
        // Zwei Wecker je Nacht über ein Jahr: 900 Nächte, alle jünger als 2 Jahre; die jüngsten 800 bleiben.
        let nights = (0..<900).map { night(now.addingTimeInterval(-Double($0) * 9 * 3600), "n\($0)") }
        let kept = NightKeep.phone.trim(nights, now: now)
        XCTAssertEqual(kept.count, NightHistory.phoneNights)
        XCTAssertEqual(kept.first?.alarmId, "n799")
        XCTAssertEqual(kept.last?.alarmId, "n0")
    }

    func testWatchKeepsNinetyRegardlessOfAge() {
        let now = iso("2026-11-18T08:00:00Z")
        let nights = (0..<95).map { night(now.addingTimeInterval(-Double(1_000 + $0) * day), "n\($0)") }
        let kept = NightKeep.watch.trim(nights, now: now)
        XCTAssertEqual(kept.count, 90)
        XCTAssertEqual(kept.first?.alarmId, "n89")
    }

    func testLatestTakesTheNewestNinety() {
        let now = iso("2026-11-18T08:00:00Z")
        let nights = (0..<700).map { night(now.addingTimeInterval(-Double($0) * day), "n\($0)") }
        let latest = NightHistory.latest(nights)
        XCTAssertEqual(latest.count, 90)
        XCTAssertEqual([latest.first?.alarmId, latest.last?.alarmId], ["n89", "n0"])
        XCTAssertEqual(NightHistory.latest(nights, count: 14).count, 14)
    }

    func testCsvExportsTheWholeHistory() {
        let now = iso("2026-11-18T08:00:00Z")
        let nights = (0..<120).map { i -> NightRecord in
            let n = night(now.addingTimeInterval(-Double(i) * day))
            return NightRecord(alarmId: n.alarmId, windowStart: n.windowStart, target: n.target, wokeAt: n.wokeAt,
                               reason: n.reason, snoozes: 0, epochs: [Epoch(motion: 0.1, heartRate: 50)])
        }
        let rows = NightHistory.exportCsv(nights).split(separator: "\n")
        XCTAssertEqual(rows.count, 121, "Kopf und je Nacht eine Epoche")
        XCTAssertTrue(rows.last?.hasPrefix("120,") ?? false)
    }

    func testHealthReadsTwoYearsInsightsOnlyNinetyDays() {
        let now = iso("2026-11-18T08:00:00Z")
        // Gelesen wird so weit wie der Verlauf (ein Tag mehr, damit die älteste Nacht ganz drin ist).
        XCTAssertEqual(HealthHistory.days, 730)
        func session(_ endDaysAgo: Double) -> HealthSession {
            let end = now.addingTimeInterval(-endDaysAgo * day)
            return HealthSession(start: end.addingTimeInterval(-8 * 3600), end: end)
        }
        let sessions = [session(400), session(92), session(91), session(90), session(1)]
        let nights = (0..<200).map { night(now.addingTimeInterval(-Double($0) * day), "n\($0)") }
        let recent = HealthHistory.forInsights(nights: nights, sessions: sessions, now: now)
        XCTAssertEqual(recent.nights.count, 90)
        // Ende echt nach jetzt − 91 Tage.
        XCTAssertEqual(recent.sessions, [session(90), session(1)])
        // Mit Vorlauf für die Phasen: bis vor die älteste der 90 Nächte (Fenster 89 Tage und 20 Min) minus 2 Tage.
        let before = HealthHistory.forInsights(nights: nights, sessions: sessions, now: now, before: 2 * day)
        XCTAssertEqual(before.sessions, [session(91), session(90), session(1)])
    }
}
