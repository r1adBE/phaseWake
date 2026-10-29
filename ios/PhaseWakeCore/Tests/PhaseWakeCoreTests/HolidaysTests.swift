import XCTest
import PhaseWakeCore

/// Feiertage auswählen und für die Uhr kodieren; Gegenstück zu `HolidaysTest` (Kotlin).
final class HolidaysTests: XCTestCase {
    private let today = "2026-10-29"

    func testResolveMergesRegionAndLimitsRange() {
        let national = ["2026-01-01", "2026-12-25", "2027-12-25", "kaputt"]
        let regional = ["2026-12-26", "2026-12-25"]
        XCTAssertEqual(Holidays.resolve(national: national, regional: regional, today: today).sorted(), ["2026-12-25", "2026-12-26"])
        // Heute zählt, das Ende (heute + Tage) nicht mehr.
        XCTAssertEqual(Holidays.resolve(national: ["2026-10-29", "2026-10-31"], regional: [], today: today, days: 2), [today])
    }

    func testEncodeAndDecode() {
        let list = HolidayList(region: "CH-BE", dates: ["2026-12-26", "2026-12-25"])
        let text = Holidays.encode(list)
        XCTAssertEqual(text, "CH-BE|2026-12-25,2026-12-26")
        XCTAssertEqual(Holidays.decode(text), list)
        XCTAssertEqual(Holidays.decode(Holidays.encode(HolidayList(region: "CH", dates: []))), HolidayList(region: "CH", dates: []))
    }

    func testDecodeIsTolerant() {
        XCTAssertEqual(Holidays.decode(nil), HolidayList(region: "", dates: []))
        XCTAssertEqual(Holidays.decode(" "), HolidayList(region: "", dates: []))
        XCTAssertEqual(Holidays.decode("DE|2026-10-03,2026-13-01,"), HolidayList(region: "DE", dates: ["2026-10-03"]))
        XCTAssertEqual(Holidays.decode("|2026-10-03"), HolidayList(region: "", dates: ["2026-10-03"]))
    }

    func testRegionCode() {
        XCTAssertEqual(Holidays.regionCode(country: "", region: "BE"), "")
        XCTAssertEqual(Holidays.regionCode(country: "CH", region: nil), "CH")
        XCTAssertEqual(Holidays.regionCode(country: "CH", region: ""), "CH")
        XCTAssertEqual(Holidays.regionCode(country: "CH", region: "BE"), "CH-BE")
    }
}
