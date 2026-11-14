import XCTest
import PhaseWakeCore

/// Gleiche Fälle wie `CsvNumbersTest` (Kotlin): Zahlen im CSV-Export auf beiden Plattformen gleich (Audit F-7).
final class CsvNumbersTests: XCTestCase {
    func testHalfRoundsAwayFromZeroOnScaledValue() {
        // Exakt halbe Werte: `String(format:)` gab 56.2, Java 56.3 – jetzt beide 56.3.
        XCTAssertEqual(CsvNumbers.fixed(56.25, decimals: 1), "56.3")
        XCTAssertEqual(CsvNumbers.fixed(72.75, decimals: 1), "72.8")
        XCTAssertEqual(CsvNumbers.fixed(52.34, decimals: 1), "52.3")
        XCTAssertEqual(CsvNumbers.fixed(60.0, decimals: 1), "60.0")
        XCTAssertEqual(CsvNumbers.fixed(0.0002465, decimals: 6), "0.000246")
        XCTAssertEqual(CsvNumbers.fixed(0.0021, decimals: 6), "0.002100")
        XCTAssertEqual(CsvNumbers.fixed(0.0, decimals: 6), "0.000000")
    }

    func testEdgeCases() {
        XCTAssertEqual(CsvNumbers.fixed(-1.5, decimals: 1), "-1.5")
        XCTAssertEqual(CsvNumbers.fixed(-1.5, decimals: 0), "-2")
        XCTAssertEqual(CsvNumbers.fixed(-0.4, decimals: 0), "0")
        XCTAssertEqual(CsvNumbers.fixed(.nan, decimals: 1), "")
        XCTAssertEqual(CsvNumbers.fixed(.infinity, decimals: 1), "")
        XCTAssertEqual(CsvNumbers.fixed(1e300, decimals: 1), "")
    }

    func testExportsUseTheSameRule() {
        let at = Date(timeIntervalSince1970: 1_790_829_000)
        let night = NightRecord(alarmId: "a", windowStart: at, target: at.addingTimeInterval(1800), wokeAt: at.addingTimeInterval(1080),
                                reason: .lightSleep, snoozes: 0, epochs: [Epoch(motion: 0.0002465, heartRate: 56.25)])
        let line = NightHistory.exportCsv([night]).split(separator: "\n").map(String.init)[1]
        XCTAssertTrue(line.hasSuffix(",0.000246,56.3,"), line)
    }
}
