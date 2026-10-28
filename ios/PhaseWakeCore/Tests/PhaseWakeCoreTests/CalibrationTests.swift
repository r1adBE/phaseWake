import XCTest
import PhaseWakeCore

final class CalibrationTests: XCTestCase {

    /// Eine Nacht: zwei Aufwärm-Epochen (werden verworfen) plus `values`.
    private func night(_ values: [Double?]) -> [Double?] { [9.0, 9.0] + values }

    func testTooFewNightsKeepsDefaults() {
        let nights = Array(repeating: night(Array(repeating: 0.003, count: 40)), count: 2)
        XCTAssertEqual(Calibration.calibrate(nights: nights), .standard)
    }

    func testEmptyNightsDoNotCount() {
        let nights = Array(repeating: night(Array(repeating: 0.003, count: 40)), count: 2) + Array(repeating: night([nil, nil]), count: 2)
        XCTAssertEqual(Calibration.calibrate(nights: nights), .standard)
    }

    func testTooFewValuesKeepsDefaults() {
        let nights = Array(repeating: night([0.003, nil, 0.003]), count: 3)
        XCTAssertEqual(Calibration.calibrate(nights: nights), .standard)
    }

    func testUsesEightiethPercentile() {
        // 3 Nächte × 10 Werte = 30: 0.0010, 0.0011, … 0.0039. Rang ⌈0.8 · 30⌉ = 24 → 0.0033.
        let all = (0..<30).map { 0.001 + Double($0) * 0.0001 }
        let nights = stride(from: 0, to: 30, by: 10).map { night(Array(all[$0..<$0 + 10])) }
        let config = Calibration.calibrate(nights: nights)
        XCTAssertEqual(config.lightThreshold, all[23])
        XCTAssertEqual(config.wakeThreshold, all[23] * 50.0)
    }

    func testClampsToRange() {
        let quiet = Array(repeating: night(Array(repeating: 0.0, count: 20)), count: 3)
        XCTAssertEqual(Calibration.calibrate(nights: quiet).lightThreshold, 0.001)
        let restless = Array(repeating: night(Array(repeating: 0.5, count: 20)), count: 3)
        XCTAssertEqual(Calibration.calibrate(nights: restless).lightThreshold, 0.004)
    }

    func testOnlyLastFourteenNightsCount() {
        let old = Array(repeating: night(Array(repeating: 0.5, count: 20)), count: 10)
        let recent = Array(repeating: night(Array(repeating: 0.0, count: 20)), count: 14)
        XCTAssertEqual(Calibration.calibrate(nights: old + recent).lightThreshold, 0.001)
    }
}
