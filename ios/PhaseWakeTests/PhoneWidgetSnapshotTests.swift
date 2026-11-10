import XCTest
@testable import PhaseWake

/// Stand des iPhone-Widgets ([PhoneWidgetSnapshot], Audit F-03): nächstes Fenster nach einer Zielzeit, am Weckmorgen bis
/// 12 Uhr die letzte Nacht, danach wieder der nächste Wecker; ein Stand älterer Versionen bleibt lesbar.
final class PhoneWidgetSnapshotTests: XCTestCase {
    private let zurich: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Europe/Zurich")!
        return calendar
    }()

    private func at(_ day: Int, _ hour: Int, _ minute: Int = 0) -> Date {
        zurich.date(from: DateComponents(year: 2026, month: 11, day: day, hour: hour, minute: minute))!
    }

    private func plan(_ day: Int, _ hour: Int) -> PhoneWidgetSnapshot.Plan {
        PhoneWidgetSnapshot.Plan(windowStart: at(day, hour).addingTimeInterval(-1800), target: at(day, hour), label: "")
    }

    func testNextWindowAfterTheTarget() {
        let snapshot = PhoneWidgetSnapshot(plans: [plan(10, 7), plan(11, 6)], issue: "", lastNight: nil)
        XCTAssertEqual(snapshot.next(after: at(10, 6))?.target, at(10, 7))
        // Ab der Zielzeit gilt der nächste Wecker, ohne dass die App läuft.
        XCTAssertEqual(snapshot.next(after: at(10, 7))?.target, at(11, 6))
        XCTAssertNil(snapshot.next(after: at(11, 6)))
    }

    func testLastNightUntilNoonOfTheWakeMorning() {
        let night = PhoneWidgetSnapshot.LastNight(wokeAt: at(10, 6, 40), reason: "Unruhig", feeling: nil, asleepMinutes: 432)
        let snapshot = PhoneWidgetSnapshot(plans: [plan(11, 6)], issue: nil, lastNight: night)
        XCTAssertNil(snapshot.morningNight(at: at(10, 6, 30), calendar: zurich), "vor dem Wecken")
        XCTAssertEqual(snapshot.morningNight(at: at(10, 6, 40), calendar: zurich), night)
        XCTAssertEqual(snapshot.morningNight(at: at(10, 11, 59), calendar: zurich), night)
        XCTAssertNil(snapshot.morningNight(at: at(10, 12), calendar: zurich), "ab 12 Uhr der nächste Wecker")
        XCTAssertNil(snapshot.morningNight(at: at(11, 6), calendar: zurich), "nicht am nächsten Morgen")
        XCTAssertEqual(snapshot.morningEnd(calendar: zurich), at(10, 12))
        XCTAssertNil(PhoneWidgetSnapshot(plans: [], issue: nil, lastNight: nil).morningEnd(calendar: zurich))
    }

    func testOlderStateStaysReadable() throws {
        // Ohne «exact», «issue», «lastNight» und «asleepMinutes» (frühere Versionen).
        let json = #"{"plans":[{"windowStart":0,"target":1800,"label":"Arbeit"}],"lastNight":{"wokeAt":900,"reason":"x"}}"#
        let snapshot = try JSONDecoder().decode(PhoneWidgetSnapshot.self, from: Data(json.utf8))
        XCTAssertEqual(snapshot.plans.first?.label, "Arbeit")
        XCTAssertNil(snapshot.plans.first?.exact)
        XCTAssertNil(snapshot.issue)
        XCTAssertNil(snapshot.lastNight?.asleepMinutes)
        // Hin und zurück unverändert.
        let again = try JSONDecoder().decode(PhoneWidgetSnapshot.self, from: JSONEncoder().encode(snapshot))
        XCTAssertEqual(again, snapshot)
    }
}
