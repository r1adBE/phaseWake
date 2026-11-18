import XCTest
import PhaseWakeCore

/// Gruppen der Liste im Verlauf. Gegenstück zu `HistoryGroupsTest` (Kotlin), gleiche Fälle.
final class HistoryGroupsTests: XCTestCase {
    /// Eintrag der Liste: Tag (Morgen) und ob er als Nacht zählt (sonst Aufzeichnung oder Geräusche).
    private struct E: Equatable {
        let day: String
        var night = true
    }

    private let today = "2026-11-18"

    /// Fünf Nächte 17.–13.11.2026 (stehen einzeln oben).
    private let recent = (13...17).reversed().map { E(day: "2026-11-\($0)") }

    private func e(_ day: String, night: Bool = true) -> E { E(day: day, night: night) }

    private func group(_ entries: [E], firstDay: Weekday = .monday) -> [HistoryGroup<E>] {
        HistoryGroups.of(entries, dayOf: \.day, isNight: \.night, today: today, firstDay: firstDay)
    }

    private func singles(_ entries: [E]) -> [HistoryGroup<E>] { entries.map { .single($0) } }

    private func week(_ start: String, _ entries: [E]) -> HistoryWeek<E> { HistoryWeek(start: start, entries: entries) }

    func testFewerThanFiveNightsStaySingle() {
        let entries = Array(recent.prefix(3))
        XCTAssertEqual(group(entries), singles(entries))
    }

    func testExactlyFiveNightsStaySingleTheSixthIsGrouped() {
        XCTAssertEqual(group(recent), singles(recent))
        let old = e("2025-12-24")
        XCTAssertEqual(group(recent + [old]), singles(recent) + [.week(week("2025-12-22", [old]))])
    }

    func testFiveNewestStaySingleNoMatterHowOld() {
        let entries = [e("2020-05-04"), e("2019-03-02"), e("2018-01-01"), e("2017-07-07"), e("2016-06-06")]
        XCTAssertEqual(group(entries), singles(entries))
    }

    func testRecordingsAndSoundsBeforeTheFifthNightStaySingleAfterItAreGrouped() {
        let sounds = e("2026-11-16", night: false)
        let recording = e("2026-11-12", night: false)
        let sixth = e("2026-11-11")
        let entries = Array(recent.prefix(2)) + [sounds] + Array(recent.dropFirst(2)) + [recording, sixth]
        XCTAssertEqual(group(entries),
                       singles(Array(recent.prefix(2)) + [sounds] + Array(recent.dropFirst(2)))
                           + [.week(week("2026-11-09", [recording, sixth]))])
    }

    func testWeekSplitAtTheFiveNightsLimitKeepsOnlyItsOwnNights() {
        // Woche ab Mo 9.11.: 13.11. steht einzeln (fünfte Nacht), 12. und 10.11. in der Wochenzeile.
        let groups = group(recent + [e("2026-11-12"), e("2026-11-10")])
        XCTAssertEqual(groups.last, .week(week("2026-11-09", [e("2026-11-12"), e("2026-11-10")])))
        XCTAssertEqual(groups.count, 6)
    }

    func testWeekAcrossMonthBoundary() {
        let older = [e("2026-11-01"), e("2026-10-30"), e("2026-10-26")]
        XCTAssertEqual(group(recent + older), singles(recent) + [.week(week("2026-10-26", older))])
    }

    func testWeekStartsOnSundayOrMondayByLanguage() {
        // So 1.11. und Fr 30.10.: mit Montag eine Woche, mit Sonntag zwei.
        let older = [e("2026-11-01"), e("2026-10-30")]
        XCTAssertEqual(group(recent + older, firstDay: .monday), singles(recent) + [.week(week("2026-10-26", older))])
        XCTAssertEqual(group(recent + older, firstDay: .sunday),
                       singles(recent) + [.week(week("2026-11-01", [e("2026-11-01")])),
                                          .week(week("2026-10-25", [e("2026-10-30")]))])
        XCTAssertEqual(week("2026-10-25", []).end, "2026-10-31")
    }

    func testGapsOfSeveralWeeksGiveNoEmptyWeeks() {
        XCTAssertEqual(group(recent + [e("2026-10-20"), e("2026-09-01")]),
                       singles(recent) + [.week(week("2026-10-19", [e("2026-10-20")])),
                                          .week(week("2026-08-31", [e("2026-09-01")]))])
    }

    func testWeekAcrossTheOneYearLimitIsSplit() {
        // Heute 18.11.2026: bis 18.11.2025 je Woche, 17.11.2025 schon im Jahr 2025 (gleiche Woche ab Mo 17.11.).
        XCTAssertEqual(group(recent + [e("2025-11-19"), e("2025-11-18"), e("2025-11-17")]),
                       singles(recent) + [.week(week("2025-11-17", [e("2025-11-19"), e("2025-11-18")])),
                                          .year(2025, [week("2025-11-17", [e("2025-11-17")])])])
    }

    func testWeekAcrossNewYearIsSplitBetweenTheYears() {
        XCTAssertEqual(group(recent + [e("2025-01-01"), e("2024-12-31")]),
                       singles(recent) + [.year(2025, [week("2024-12-30", [e("2025-01-01")])]),
                                          .year(2024, [week("2024-12-30", [e("2024-12-31")])])])
    }

    func testNightsOlderThanAYearInSeveralYears() {
        let groups = group(recent + [e("2026-01-05"), e("2025-03-12"), e("2025-03-10"), e("2025-02-01"),
                                     e("2023-06-15")])
        XCTAssertEqual(Array(groups.dropFirst(5)), [
            .week(week("2026-01-05", [e("2026-01-05")])),
            .year(2025, [week("2025-03-10", [e("2025-03-12"), e("2025-03-10")]),
                         week("2025-01-27", [e("2025-02-01")])]),
            .year(2023, [week("2023-06-12", [e("2023-06-15")])]),
        ])
        XCTAssertEqual(groups[6].entries, [e("2025-03-12"), e("2025-03-10"), e("2025-02-01")])
    }

    func testOneYearLimitOnLeapDay() {
        XCTAssertEqual(HistoryGroups.yearCutoff("2028-02-29"), "2027-02-28")
        XCTAssertEqual(HistoryGroups.yearCutoff(today), "2025-11-18")
    }

    func testDaylightSavingChangeKeepsTheLocalDay() {
        // Tag in der Ortszeit: 28.03. 23:30 UTC ist So 29.03. 00:30 in Zürich (Umstellung in dieser Nacht),
        // 25.10. 23:30 UTC ist Mo 26.10. 00:30 (Rückstellung in der Nacht davor).
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Europe/Zurich")!
        func at(_ text: String) -> E {
            E(day: SleepNotes.dateString(ISO8601DateFormatter().date(from: text)!, calendar: calendar))
        }
        let spring = at("2026-03-28T23:30:00Z")
        let autumn = at("2026-10-25T23:30:00Z")
        XCTAssertEqual(spring.day, "2026-03-29")
        XCTAssertEqual(autumn.day, "2026-10-26")
        XCTAssertEqual(group(recent + [autumn, spring]),
                       singles(recent) + [.week(week("2026-10-26", [autumn])), .week(week("2026-03-23", [spring]))])
    }

    func testSummaryCountsNightsAveragesSleepPerNightDateAndFeeling() {
        struct N {
            let date: String?
            let feeling: Int?
        }
        let entries = [N(date: "2026-11-01", feeling: 4), N(date: "2026-11-01", feeling: 2),
                       N(date: "2026-10-31", feeling: nil), N(date: nil, feeling: 5),
                       N(date: "2026-10-30", feeling: 5)]
        let asleep = ["2026-11-01": 420, "2026-10-31": 391]
        let summary = HistoryGroups.summary(entries, nightDate: \.date, asleep: asleep, feeling: \.feeling)
        // Zwei Nächte am 1.11. zählen beim Schlaf einmal: (420 + 391) / 2 = 405 (abgerundet).
        XCTAssertEqual(summary, HistoryGroupSummary(nights: 4, asleepMinutes: 405, feeling: 11.0 / 3))
        let empty = HistoryGroups.summary([N(date: "2026-10-29", feeling: nil)], nightDate: \.date, asleep: asleep,
                                          feeling: \.feeling)
        XCTAssertEqual(empty.nights, 1)
        XCTAssertNil(empty.asleepMinutes)
        XCTAssertNil(empty.feeling)
    }
}
