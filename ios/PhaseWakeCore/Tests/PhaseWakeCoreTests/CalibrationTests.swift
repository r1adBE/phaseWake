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
        // 3 Nächte × (2 × 0.0 + 8 Werte) = 30; je Nacht Grundrauschen 0.0 (Rang 2 von 10), abgezogen bleibt alles gleich.
        // Werte 0.0005, 0.00055, … : sortiert 6 × 0.0, dann diese; Rang ⌈0.8 · 30⌉ = 24 → der 18. Wert (0.00135).
        let all = (0..<24).map { 0.0005 + Double($0) * 0.00005 }
        let nights = stride(from: 0, to: 24, by: 8).map { night([0.0, 0.0] + all[$0..<$0 + 8].map { Optional($0) }) }
        let config = Calibration.calibrate(nights: nights)
        XCTAssertEqual(config.lightThreshold, all[17])
        XCTAssertEqual(config.wakeThreshold, all[17] * 50.0)
    }

    func testNoiseFloorOfEachNightIsSubtracted() {
        // Audit c3b8b01 E-2: Uhr mit Rauschen 0.02 (Werte 0.02000 … 0.02095). Grundrauschen je Nacht 0.02015 (Rang 4 von
        // 20) abgezogen: 80. Perzentil 0.0006 → untere Grenze 0.001, Wach 0.05. Ohne Abzug: 0.004 und 0.2.
        let nights = Array(repeating: night((0..<20).map { Optional(0.02 + Double($0) * 0.00005) }), count: 3)
        let config = Calibration.calibrate(nights: nights)
        XCTAssertEqual(config.lightThreshold, 0.001, accuracy: 1e-12)
        XCTAssertEqual(config.wakeThreshold, 0.05, accuracy: 1e-12)
    }

    func testRealNoisyNightsKeepTheDefaultWakeThreshold() throws {
        // Gemeinsame Fälle mit Kotlin (shared/testdata/calibration-real.vectors): 4 echte Nächte einer Galaxy Watch.
        let text = try String(contentsOf: NightFixtures.directory.appendingPathComponent("calibration-real.vectors"), encoding: .utf8)
        let lines = text.components(separatedBy: "\n").filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty && !$0.hasPrefix("#") }
        let expectLine = try XCTUnwrap(lines.first { $0.hasPrefix("@expect ") })
        let expect = expectLine.dropFirst("@expect ".count).split(separator: " ").compactMap { Double(String($0)) }
        XCTAssertEqual(expect.count, 2)
        let nights: [[Double?]] = lines.filter { !$0.hasPrefix("@") }.map { line in
            line.split(separator: ",", omittingEmptySubsequences: false).map { Double(String($0)) }
        }
        XCTAssertEqual(nights.count, 4)
        let config = Calibration.calibrate(nights: nights)
        XCTAssertEqual(config.lightThreshold, expect[0], accuracy: 1e-12)
        XCTAssertEqual(config.wakeThreshold, expect[1], accuracy: 1e-12)
    }

    func testClampsToRange() {
        let quiet = Array(repeating: night(Array(repeating: 0.0, count: 20)), count: 3)
        XCTAssertEqual(Calibration.calibrate(nights: quiet).lightThreshold, 0.001)
        let restless = Array(repeating: night(Array(repeating: 0.5, count: 20)), count: 3)
        XCTAssertEqual(Calibration.calibrate(nights: restless).lightThreshold, 0.004)
        // Wach höchstens beim Startwert 0.1 (E-2, r1AD 14.11.2026), nicht 0.004 × 50 = 0.2; ruhig 0.001 × 50 = 0.05.
        XCTAssertEqual(Calibration.calibrate(nights: restless).wakeThreshold, 0.1)
        XCTAssertEqual(Calibration.calibrate(nights: quiet).wakeThreshold, 0.05)
    }

    func testOnlyLastFourteenNightsCount() {
        let old = Array(repeating: night(Array(repeating: 0.5, count: 20)), count: 10)
        let recent = Array(repeating: night(Array(repeating: 0.0, count: 20)), count: 14)
        XCTAssertEqual(Calibration.calibrate(nights: old + recent).lightThreshold, 0.001)
    }
}
