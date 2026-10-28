import XCTest
import PhaseWakeCore

final class MotionEnergyAccumulatorTests: XCTestCase {

    private let stepNanos: Int64 = 40_000_000 // 25 Hz

    /// Füllt eine Epoche mit `count` Werten; `value` liefert den Vektor je Index.
    private func fill(
        _ acc: MotionEnergyAccumulator, count: Int, start: Int64 = 0,
        _ value: (Int) -> (Double, Double, Double)
    ) {
        for i in 0..<count {
            let (x, y, z) = value(i)
            acc.add(timestampNanos: start + Int64(i) * stepNanos, x: x, y: y, z: z)
        }
    }

    func testStillWristIsZero() {
        let acc = MotionEnergyAccumulator()
        fill(acc, count: 750) { _ in (0.0, 0.0, 9.81) }
        XCTAssertEqual(acc.closeEpoch(), 0.0)
    }

    func testNoiseBelowFloorIsIgnored() {
        let acc = MotionEnergyAccumulator()
        fill(acc, count: 750) { i in (i % 2 == 0 ? 0.0 : 0.04, 0.0, 9.81) }
        XCTAssertEqual(acc.closeEpoch(), 0.0)
    }

    func testMotionAboveFloorIsAveraged() throws {
        let acc = MotionEnergyAccumulator()
        // Jeder Schritt springt um 0.25 → 0.20 über dem Rauschen.
        fill(acc, count: 750) { i in (i % 2 == 0 ? 0.0 : 0.25, 0.0, 9.81) }
        XCTAssertEqual(try XCTUnwrap(acc.closeEpoch()), 0.20, accuracy: 1e-12)
    }

    func testTooFewSamplesGiveNil() {
        let acc = MotionEnergyAccumulator()
        fill(acc, count: 300) { _ in (0.0, 0.0, 9.81) } // 299 Differenzen
        XCTAssertNil(acc.closeEpoch())
    }

    func testFasterSensorIsThrottled() throws {
        let acc = MotionEnergyAccumulator()
        // 100 Hz für 30 s: übernommen wird nur jeder vierte Wert (40 ms ≥ 35 ms).
        for i in 0..<3000 {
            acc.add(timestampNanos: Int64(i) * 10_000_000, x: i % 8 < 4 ? 0.0 : 1.0, y: 0.0, z: 9.81)
        }
        XCTAssertEqual(try XCTUnwrap(acc.closeEpoch()), 0.95, accuracy: 1e-12)
    }

    func testGapDiffIsDiscarded() {
        let acc = MotionEnergyAccumulator()
        fill(acc, count: 400) { _ in (0.0, 0.0, 9.81) }
        // Nach 2 s Lücke ein ganz anderer Wert: keine Bewegung, nur neuer Bezugspunkt.
        fill(acc, count: 400, start: 400 * stepNanos + 2_000_000_000) { _ in (5.0, 0.0, 9.81) }
        XCTAssertEqual(acc.closeEpoch(), 0.0)
    }

    func testEpochsAreIndependentButContinuous() throws {
        let acc = MotionEnergyAccumulator()
        fill(acc, count: 750) { i in (i % 2 == 0 ? 0.0 : 0.25, 0.0, 9.81) }
        _ = acc.closeEpoch()
        fill(acc, count: 750, start: 750 * stepNanos) { _ in (0.0, 0.0, 9.81) }
        // Nur die erste Differenz der neuen Epoche (0.25 → 0.0) zählt: 0.20 / 750.
        XCTAssertEqual(try XCTUnwrap(acc.closeEpoch()), 0.20 / 750, accuracy: 1e-12)
    }
}
