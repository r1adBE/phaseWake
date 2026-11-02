import XCTest
import PhaseWakeCore

/// Nächte mit Zweitwecker auf dem iPhone (`BackupPlan.nights`): je Wecker gesucht, damit zwei Wecker mit derselben
/// Zielzeit beide eine Sicherung bekommen. Nur Apple.
final class BackupPlanTests: XCTestCase {
    private var zurich: Calendar {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "Europe/Zurich")!
        return c
    }

    /// Mittwoch, 28.10.2026.
    private func at(_ d: Int, _ h: Int, _ mi: Int) -> Date {
        zurich.date(from: DateComponents(year: 2026, month: 10, day: d, hour: h, minute: mi))!
    }

    private func alarm(_ id: String, hour: Int = 7, minute: Int = 0, days: Set<Weekday> = Set(Weekday.allCases)) -> AlarmSpec {
        try! AlarmSpec(id: id, hour: hour, minute: minute, days: days)
    }

    private func nights(_ specs: [AlarmSpec], now: Date, days: Int = 3, max: Int = 40, suppressed: [String: Date] = [:],
                        first: [String: Date] = [:], skip: Set<String> = []) -> [WakePlan] {
        BackupPlan.nights(specs, now: now, until: now.addingTimeInterval(TimeInterval(days * 24 * 3600)), maxNights: max,
                          suppressed: suppressed, firstTargets: first, skip: skip, calendar: zurich)
    }

    func testSameTargetGetsBothBackups() {
        let plans = nights([alarm("b"), alarm("a")], now: at(28, 6, 0), days: 1)
        XCTAssertEqual(plans.map(\.alarmId), ["a", "b"], "beide, bei Gleichstand nach Kennung")
        XCTAssertEqual(plans.map(\.target), [at(28, 7, 0), at(28, 7, 0)])
    }

    func testOneShotWithSameTargetAsRepeating() {
        // Früher fiel nach dem einmaligen Wecker der wiederholte mit derselben Zielzeit weg.
        let once = alarm("o", days: [])
        let plans = nights([once, alarm("r")], now: at(28, 6, 0), days: 2, first: ["o": at(28, 7, 0)])
        XCTAssertEqual(plans.map(\.alarmId), ["o", "r", "r"])
        XCTAssertEqual(plans.map(\.target), [at(28, 7, 0), at(28, 7, 0), at(29, 7, 0)])
    }

    func testOneShotOnlyFirstOccurrence() {
        let once = alarm("o", days: [])
        // Erstes Vorkommen schon vorbei (gemerkt): keine Sicherung für den nächsten Tag.
        XCTAssertTrue(nights([once], now: at(28, 8, 0), first: ["o": at(28, 7, 0)]).isEmpty)
        // Ausgesetzt (die Uhr hat die Nacht übernommen): auch keine.
        XCTAssertTrue(nights([once], now: at(28, 6, 0), suppressed: ["o": at(28, 7, 2)], first: ["o": at(28, 7, 0)]).isEmpty)
        XCTAssertTrue(nights([once], now: at(28, 6, 0), first: ["o": at(28, 7, 0)], skip: ["o"]).isEmpty)
    }

    func testSuppressedAndLimits() {
        let plans = nights([alarm("a")], now: at(28, 6, 0), days: 3, suppressed: ["a": at(28, 7, 2)])
        XCTAssertEqual(plans.map(\.target), [at(29, 7, 0), at(30, 7, 0)], "heute ausgesetzt, bis zum Horizont")
        let many = nights([alarm("a"), alarm("b", minute: 30)], now: at(28, 6, 0), days: 14, max: 5)
        XCTAssertEqual(many.count, 5)
        XCTAssertEqual(many.map(\.alarmId), ["a", "b", "a", "b", "a"], "die frühesten zuerst")
        XCTAssertTrue(nights([alarm("a").with(enabled: false)], now: at(28, 6, 0)).isEmpty)
        XCTAssertTrue(nights([alarm("a")], now: at(28, 6, 0), max: 0).isEmpty)
    }
}
