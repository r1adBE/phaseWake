import XCTest
import PhaseWakeCore

/// Befinden zu Nächten aus anderen Apps. Gegenstück zu `HealthFeelingsTest` (Kotlin), gleiche Fälle.
final class HealthFeelingsTests: XCTestCase {
    private let calendar: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "Europe/Zurich")!
        return c
    }()

    private func t(_ text: String) -> Date { ISO8601DateFormatter().date(from: text)! }

    private func night(_ windowStart: String, _ feeling: Int?) -> NightRecord {
        NightRecord(alarmId: "a", windowStart: t(windowStart), target: t(windowStart).addingTimeInterval(30 * 60),
                    wokeAt: t(windowStart).addingTimeInterval(20 * 60), reason: .lightSleep, snoozes: 0, epochs: [], feeling: feeling)
    }

    /// `count` Nächte aus einer anderen App ab dem 1.10., je am Morgen danach um 07:00 Ortszeit zu Ende.
    private func health(_ count: Int, _ feeling: (Int) -> Int) -> [HealthFeeling] {
        (0..<count).map { i in
            HealthFeeling(date: NightDate.adding(i, to: "2026-10-01"), at: t("2026-10-02T05:00:00Z").addingTimeInterval(Double(i) * 86_400),
                          feeling: feeling(i))
        }
    }

    func testEncodeAndDecode() {
        let map = ["2026-10-01": 4, "2026-09-30": 2]
        XCTAssertEqual(HealthFeelings.encode(map), "2026-09-30|2\n2026-10-01|4")
        XCTAssertEqual(HealthFeelings.decode(HealthFeelings.encode(map)), map)
        // Unlesbar: kein Datum, Wert ausserhalb 1–5, leer, ungültiges Datum, zusätzliche Felder.
        let text = "x|3\n2026-10-02|9\n2026-10-03|\n2026-02-30|3\n2026-10-04|0\n2026-10-06|2|x\n2026-10-05| 1 "
        XCTAssertEqual(HealthFeelings.decode(text), ["2026-10-05": 1])
        XCTAssertEqual(HealthFeelings.decode(""), [:])
        XCTAssertEqual(HealthFeelings.encode([:]), "")
    }

    func testSetReplacesAndTrims() {
        let one = HealthFeelings.set([:], date: "2026-10-01", feeling: 5)
        XCTAssertEqual(HealthFeelings.set(one, date: "2026-10-01", feeling: 3), ["2026-10-01": 3])
        XCTAssertEqual(HealthFeelings.set(one, date: "2026-10-02", feeling: 0), one)
        XCTAssertEqual(HealthFeelings.set(one, date: "2026-10-02", feeling: 6), one)
        var many: [String: Int] = [:]
        for i in 0..<91 { many = HealthFeelings.set(many, date: NightDate.adding(i, to: "2026-01-01"), feeling: 4) }
        XCTAssertEqual(many.count, HealthFeelings.maxDates)
        XCTAssertNil(many["2026-01-01"])
        XCTAssertEqual(many["2026-04-01"], 4)
    }

    func testOnlyNightsFromOtherAppsInHistory() {
        let own = night("2026-10-02T04:30:00Z", 2)
        let session = HealthSession(start: t("2026-10-02T21:00:00Z"), end: t("2026-10-03T05:00:00Z"))
        let items = HealthHistory.merge(nights: [own], sessions: [session], clearedAt: nil, calendar: calendar)
        // 1.10. gehört der eigenen Nacht, 20.9. ist in der Quelle weg: beide zählen nicht.
        let stored = ["2026-10-01": 5, "2026-10-02": 4, "2026-09-20": 1]
        XCTAssertEqual(HealthFeelings.of(items, feelings: stored), [HealthFeeling(date: "2026-10-02", at: session.end, feeling: 4)])
        XCTAssertTrue(HealthFeelings.of(items, feelings: [:]).isEmpty)
    }

    func testOwnFeelingWins() {
        let rated = night("2026-10-02T04:30:00Z", 2)
        let unrated = night("2026-10-03T04:30:00Z", nil)
        let other = [
            HealthFeeling(date: "2026-10-01", at: t("2026-10-02T05:00:00Z"), feeling: 5),
            HealthFeeling(date: "2026-10-02", at: t("2026-10-03T05:00:00Z"), feeling: 4),
            HealthFeeling(date: "2026-10-04", at: t("2026-10-05T05:00:00Z"), feeling: 3),
        ]
        XCTAssertEqual(HealthFeelings.byDate([rated, unrated], health: other, calendar: calendar),
                       ["2026-10-01": 2, "2026-10-02": 4, "2026-10-04": 3])
    }

    func testCountsInChartsAndTrends() {
        let three = health(3) { _ in 4 }
        XCTAssertEqual(ChartData.feelingSeries([], notes: [:], calendar: calendar, today: "2026-10-04", health: three)?.points.compactMap(\.feeling),
                       [4, 4, 4])
        XCTAssertNil(ChartData.feelingSeries([], notes: [:], calendar: calendar, today: "2026-10-04"))
        let now = t("2026-10-04T12:00:00Z")
        XCTAssertEqual(Trends.compute(nights: [], sleeps: [], now: now, calendar: calendar, health: three).feelingThisWeek, 4)
        XCTAssertNil(Trends.compute(nights: [], sleeps: [], now: now, calendar: calendar).feelingThisWeek)
        // Woche bis Samstag, 3.10.
        XCTAssertEqual(WeeklySummary.of(nights: [], sleeps: [], notes: [:], calendar: calendar, goalMinutes: 450, weekEnd: "2026-10-03",
                                        health: three).feelingAverage, 4)
        // Notiz-Wirkung: drei Nächte mit Kaffee (Befinden 2), drei ohne (4).
        let six = health(6) { $0 < 3 ? 2 : 4 }
        let notes = Dictionary(uniqueKeysWithValues: (0..<3).map { (NightDate.adding($0, to: "2026-10-01"), ["CAFFEINE"]) })
        let effects = NoteEffect.compute(nights: [], notes: notes, health: six, calendar: calendar)
        XCTAssertEqual(effects.count, 1)
        XCTAssertEqual(effects.first?.delta, -2)
    }
}
