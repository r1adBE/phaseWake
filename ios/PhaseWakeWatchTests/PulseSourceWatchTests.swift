import PhaseWakeCore
import WatchKit
import XCTest
@testable import PhaseWakeWatch

/// Puls nur der Apple Watch.
final class PulseSourceWatchTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_793_000_000)

    private func minutes(_ value: Double) -> Date { now.addingTimeInterval(value * 60) }

    /// Nur Werte mit Gerät «Watch» (Apple Watch) zählen; Ring, Brustgurt oder eine App ohne Gerät nicht.
    func testOnlyApplesWatchPulseCounts() {
        XCTAssertTrue(WatchPulseSource.counts(deviceModel: "Watch"))
        XCTAssertFalse(WatchPulseSource.counts(deviceModel: "Oura"))
        XCTAssertFalse(WatchPulseSource.counts(deviceModel: nil))
        let predicate = WatchPulseSource.predicate(start: now, end: minutes(60))
        XCTAssertEqual((predicate as? NSCompoundPredicate)?.subpredicates.count, 2)
    }
}
