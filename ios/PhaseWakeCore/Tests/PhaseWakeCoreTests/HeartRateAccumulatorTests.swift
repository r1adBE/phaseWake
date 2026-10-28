import XCTest
import PhaseWakeCore

final class HeartRateAccumulatorTests: XCTestCase {

    func testMeanOfValidValues() {
        let acc = HeartRateAccumulator()
        for bpm in [50.0, 54.0, 0.0, -3.0, 300.0, .nan] { acc.add(bpm: bpm) }
        XCTAssertEqual(acc.closeEpoch(), 52.0)
    }

    func testEmptyEpochIsNilAndResets() {
        let acc = HeartRateAccumulator()
        acc.add(bpm: 60.0)
        _ = acc.closeEpoch()
        XCTAssertNil(acc.closeEpoch())
    }
}
