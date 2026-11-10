import XCTest
@testable import PhaseWakeCore

/// Meldung «Messung vor dem Schlafen nicht gestartet» (Audit F-06); Gegenstück zu `RecorderSkipTest` (Kotlin).
final class RecorderSkipTests: XCTestCase {
    private let target = Date(timeIntervalSince1970: 1_800_000_000)

    func testEncodesAndDecodes() {
        let skip = RecorderSkip(target: target, reason: .battery)
        XCTAssertEqual(skip.encode(), "1800000000000|battery")
        XCTAssertEqual(RecorderSkip.decode(skip.encode()), skip)
        XCTAssertEqual(RecorderSkip.decode(" 1800000000000|motion\n"), RecorderSkip(target: target, reason: .motion))
    }

    func testUnreadableOrUnknownIsNil() {
        XCTAssertNil(RecorderSkip.decode(nil))
        XCTAssertNil(RecorderSkip.decode(""), "leer: keine Meldung")
        XCTAssertNil(RecorderSkip.decode("1800000000000"))
        XCTAssertNil(RecorderSkip.decode("x|battery"))
        XCTAssertNil(RecorderSkip.decode("0|battery"))
        XCTAssertNil(RecorderSkip.decode("1800000000000|later"), "unbekannter Grund einer neueren Uhr")
    }

    func testAppliesUntilTheTargetTime() {
        let skip = RecorderSkip(target: target, reason: .battery)
        XCTAssertTrue(skip.applies(at: target.addingTimeInterval(-8 * 3600)))
        XCTAssertTrue(skip.applies(at: target.addingTimeInterval(-0.001)))
        XCTAssertFalse(skip.applies(at: target), "Nacht vorbei")
    }
}
