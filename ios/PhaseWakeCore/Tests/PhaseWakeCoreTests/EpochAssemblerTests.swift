import XCTest
import PhaseWakeCore

/// Gleiche Fälle wie `EpochAssemblerTest` (Kotlin).
final class EpochAssemblerTests: XCTestCase {
    private let s: Int64 = 1_000_000_000
    private var start: Int64 { 500 * s }

    /// Füllt die Epoche `epoch` mit 25-Hz-Werten, die bei jedem Schritt um `jump` springen.
    private func fill(_ a: EpochAssembler, epoch: Int64, jump: Double) -> [Epoch] {
        var closed: [Epoch] = []
        for i in 0..<Int64(750) {
            let t = start + epoch * 30 * s + i * 40_000_000
            closed += a.addMotion(timestampNanos: t, x: i % 2 == 0 ? 0 : jump, y: 0, z: 9.81)
        }
        return closed
    }

    func testClosesEpochWhenNextBegins() {
        let a = EpochAssembler(startNanos: start)
        XCTAssertTrue(fill(a, epoch: 0, jump: 0.25).isEmpty)
        _ = a.addHeartRate(timestampNanos: start + 10 * s, bpm: 50)
        _ = a.addHeartRate(timestampNanos: start + 20 * s, bpm: 54)
        let closed = a.addMotion(timestampNanos: start + 30 * s, x: 0, y: 0, z: 9.81)
        XCTAssertEqual(closed.count, 1)
        XCTAssertEqual(closed[0].motion!, 0.20, accuracy: 1e-12)
        XCTAssertEqual(closed[0].heartRate, 52)
        XCTAssertEqual(a.currentIndex, 1)
    }

    func testGapProducesEmptyEpochs() {
        let a = EpochAssembler(startNanos: start)
        _ = fill(a, epoch: 0, jump: 0)
        let closed = fill(a, epoch: 3, jump: 0)
        XCTAssertEqual(closed.count, 3)
        XCTAssertEqual(closed[0].motion, 0)
        XCTAssertNil(closed[1].motion)
        XCTAssertNil(closed[2].heartRate)
    }

    func testSamplesBeforeStartAndLateSamplesAreIgnored() {
        let a = EpochAssembler(startNanos: start)
        XCTAssertTrue(a.addMotion(timestampNanos: start - 1, x: 0, y: 0, z: 9.81).isEmpty)
        _ = a.addMotion(timestampNanos: start + 31 * s, x: 0, y: 0, z: 9.81)
        // Gehört zu Epoche 0, die schon abgeschlossen ist.
        XCTAssertTrue(a.addHeartRate(timestampNanos: start + 5 * s, bpm: 60).isEmpty)
        XCTAssertEqual(a.currentIndex, 1)
    }

    func testAdvanceClosesWithGrace() {
        let a = EpochAssembler(startNanos: start)
        _ = fill(a, epoch: 0, jump: 0)
        // 25 s: Epoche 0 läuft noch.
        XCTAssertTrue(a.advance(nowNanos: start + 35 * s, graceNanos: 10 * s).isEmpty)
        XCTAssertEqual(a.advance(nowNanos: start + 41 * s, graceNanos: 10 * s).count, 1)
        XCTAssertTrue(a.advance(nowNanos: start - 100 * s, graceNanos: 0).isEmpty)
    }
}
