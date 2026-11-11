import XCTest
import PhaseWakeCore

/// Obergrenze für Inhalte der Uhr (Audit S-2, wie Android `PeerLimitTest`).
final class PeerLimitTests: XCTestCase {

    /// Die längste Aufzeichnung (12 Std., ungünstige Zahlen) liegt weit unter der Grenze.
    func testLongestRecordingIsFarBelowTheLimit() {
        let epochs = (0..<NightRecording.maxEpochs).map { Epoch(motion: 1.2345678901234567e-5 + Double($0), heartRate: 62.333333333333336) }
        let start = Date(timeIntervalSince1970: 1_794_348_000)
        let text = NightRecording.encode(NightRecording(start: start, epochs: epochs, end: start.addingTimeInterval(NightRecording.maxDuration)))
        XCTAssertEqual(NightRecording.maxEpochs, 1440)
        XCTAssertLessThan(text.utf8.count, 100_000)
        XCTAssertLessThan(text.utf8.count * 50, SyncKeys.maxPeerBytes)
    }
}
