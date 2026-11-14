import PhaseWakeCore
import XCTest
@testable import PhaseWake

/// Geschätzte Feiertage gelten nicht als frei (Entscheid r1AD 14.11.2026, Audit F-2), wie `HolidayDaysTest` (Android).
final class HolidayEstimatedPhoneTests: XCTestCase {
    func testEstimatedDaysAreNotFree() {
        let database = HolidayDatabase(countries: [
            "XK": .init(regions: ["R": "Region"], days: ["": ["2026-01-01"], "R": ["2026-04-01"]],
                        estimatedDays: ["": ["2026-03-20", "2026-04-01"]]),
        ])
        XCTAssertEqual(database.dates(country: "XK", region: "", today: "2026-01-01"), ["2026-01-01"])
        XCTAssertEqual(database.estimatedDates(country: "XK", region: "", today: "2026-01-01"), ["2026-03-20", "2026-04-01"])
        // In der Region ist der 1.4. sicher frei: dort nicht «geschätzt».
        XCTAssertEqual(database.dates(country: "XK", region: "R", today: "2026-01-01"), ["2026-01-01", "2026-04-01"])
        XCTAssertEqual(database.estimatedDates(country: "XK", region: "R", today: "2026-01-01"), ["2026-03-20"])
    }

    /// Echte Datendatei: Kosovo, «Eid al-Fitr (estimated)» am 20.03.2026.
    func testBundledDataSeparatesEstimatedDays() throws {
        let database = try XCTUnwrap(HolidayDatabase.load())
        XCTAssertFalse(database.dates(country: "XK", region: "", today: "2026-01-01").contains("2026-03-20"))
        XCTAssertTrue(database.estimatedDates(country: "XK", region: "", today: "2026-01-01").contains("2026-03-20"))
        XCTAssertTrue(database.dates(country: "XK", region: "", today: "2026-01-01").contains("2026-02-17"), "Unabhängigkeitstag sicher")
    }
}
