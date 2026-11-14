import XCTest
@testable import PhaseWake

/// Akku-Überwachung des iPhones: Das Ende der Geräuschaufnahme schaltet sie nicht ab, solange «Akku je Nacht» sie hält.
@MainActor
final class BatteryMonitoringTests: XCTestCase {
    func testRecorderReleaseKeepsItOnWhileBatteryNightHolds() {
        var enabled = false
        let monitoring = BatteryMonitoring { enabled = $0 }
        monitoring.acquire() // PhoneBattery.listen
        monitoring.acquire() // Aufnahme beginnt
        monitoring.release() // Aufnahme endet
        XCTAssertTrue(enabled)
        XCTAssertEqual(monitoring.holders, 1)
    }

    func testLastReleaseTurnsItOffAndExtraReleasesStayAtZero() {
        var enabled = false
        let monitoring = BatteryMonitoring { enabled = $0 }
        monitoring.acquire()
        XCTAssertTrue(enabled)
        monitoring.release()
        XCTAssertFalse(enabled)
        monitoring.release()
        XCTAssertEqual(monitoring.holders, 0)
        monitoring.acquire()
        XCTAssertTrue(enabled)
    }
}
