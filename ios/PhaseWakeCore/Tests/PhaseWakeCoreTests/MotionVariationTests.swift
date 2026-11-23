import XCTest
import PhaseWakeCore

/// «Auf und Ab» (Variante B); dieselben Fälle prüft die Kotlin-Umsetzung (`MotionVariationTest`).
final class MotionVariationTests: XCTestCase {

    private let floor = 0.02

    func testPlateauDoesNotVary() {
        let previous: [Double?] = [0.070, 0.074, 0.071, 0.076, 0.072, 0.075, 0.072, 0.071, 0.072, 0.075]
        XCTAssertFalse(MotionVariation.varies(previous: previous, motion: 0.072, floor: floor))
    }

    func testJumpVaries() {
        let previous = [Double?](repeating: 0.02, count: 10)
        XCTAssertTrue(MotionVariation.varies(previous: previous, motion: 0.07, floor: floor))
    }

    func testDropVaries() {
        let previous = [Double?](repeating: 0.07, count: 10)
        XCTAssertTrue(MotionVariation.varies(previous: previous, motion: 0.02, floor: floor))
    }

    func testWithoutPreviousMotionCounts() {
        XCTAssertTrue(MotionVariation.varies(previous: [], motion: 0.07, floor: floor))
        XCTAssertTrue(MotionVariation.varies(previous: [nil, nil], motion: 0.07, floor: floor))
    }

    func testEvenCountMedianIsMeanOfTheMiddleTwo() {
        // Sortiert 0, 0.03, 0.07, 0.10: Median 0.05; Vorepoche 0.07. Mit 0.03 oder 0.07 als Median kippte je ein Fall.
        let previous: [Double?] = [0.0, 0.10, 0.03, 0.07]
        XCTAssertTrue(MotionVariation.varies(previous: previous, motion: 0.08, floor: 0.025))
        XCTAssertFalse(MotionVariation.varies(previous: previous, motion: 0.06, floor: 0.025))
    }

    func testPreviousEpochAloneVaries() {
        // Median der 10 vorherigen 0.07 wie die Epoche, die Vorepoche 0.02 weicht ab.
        let previous = [Double?](repeating: 0.07, count: 9) + [0.02]
        XCTAssertTrue(MotionVariation.varies(previous: previous, motion: 0.07, floor: floor))
    }

    func testEmptyPreviousEpochIsNotCompared() {
        let previous = [Double?](repeating: 0.07, count: 10) + [nil]
        XCTAssertFalse(MotionVariation.varies(previous: previous, motion: 0.07, floor: floor))
    }

    func testMedianUsesOnlyTheLastTenMotions() {
        // Letzte 10: 4 × 0.02 und 6 × 0.07, Median 0.07; über alle 16 wäre er 0.02.
        let previous = [Double?](repeating: 0.02, count: 10) + [Double?](repeating: 0.07, count: 6)
        XCTAssertFalse(MotionVariation.varies(previous: previous, motion: 0.07, floor: floor))
    }
}
