import XCTest
@testable import PhaseWake

/// Dauer im Fliesstext bleibt ganz zusammen, wie `FindingTextTest.durationInRunningTextKeepsTogether` (Android).
final class DurationTextPhoneTests: XCTestCase {
    func testRunningTextKeepsTheDurationTogether() {
        let together = Format.duration(minutes: 23 * 60 + 20, keepTogether: true)
        XCTAssertFalse(together.contains(" "), together)
        XCTAssertEqual(together, Format.unbreakable(Format.duration(minutes: 23 * 60 + 20)))
        XCTAssertEqual(Format.unbreakable("6 h 17 min"), "6\u{00A0}h\u{00A0}17\u{00A0}min")
    }

    func testTileKeepsItsBreakBetweenHoursAndMinutes() {
        // Die Kachel behält das normale Leerzeichen zwischen Stunden und Minuten (Sprachen mit Abstand).
        let tile = Format.duration(minutes: 377)
        let words = tile.split(separator: " ")
        XCTAssertLessThanOrEqual(words.count, 2, tile)
        if words.count == 2 { XCTAssertFalse(Format.duration(minutes: 377, keepTogether: true).contains(" ")) }
    }
}
