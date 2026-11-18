import XCTest
@testable import PhaseWake

/// Eine Nachkommastelle mit dem Dezimalzeichen der Sprache: «Befinden 1,5» auch in der Schweiz, nicht «1.5». Wie
/// `FormatDecimalTest` (Android).
final class DecimalTextPhoneTests: XCTestCase {
    private func decimal(_ id: String, _ value: Double, signed: Bool = false) -> String {
        decimalText(value, signed: signed, locale: Locale(identifier: id))
    }

    func testSeparatorFollowsTheLanguage() {
        XCTAssertEqual(decimal("de_CH", 1.5), "1,5")
        XCTAssertEqual(decimal("de_DE", 1.5), "1,5")
        XCTAssertEqual(decimal("fr_CH", 1.5), "1,5")
        XCTAssertEqual(decimal("ru", 1.5), "1,5")
        XCTAssertEqual(decimal("fi", 1.5), "1,5")
        XCTAssertEqual(decimal("en_US", 1.5), "1.5")
        XCTAssertEqual(decimal("en", 3.0), "3.0")
    }

    func testSignedUsesARealMinus() {
        XCTAssertEqual(decimal("de_CH", -0.8, signed: true), "\u{2212}0,8")
        XCTAssertEqual(decimal("de_CH", 0.5, signed: true), "+0,5")
        XCTAssertEqual(decimal("en", 0.5, signed: true), "+0.5")
    }

    func testRoundsHalfUpLikeAndroid() {
        XCTAssertEqual(decimal("de", 3.25), "3,3")
        XCTAssertEqual(decimal("de", 3.75), "3,8")
    }
}
