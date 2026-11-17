import SwiftUI
import XCTest
@testable import PhaseWake

/// Nachttisch-Modus: vor dem Sonnenaufgang Minutentakt, ab seinem Beginn jede Sekunde.
final class NightstandSchedulePhoneTests: XCTestCase {
    /// Volle Minute.
    private let t0 = Date(timeIntervalSince1970: 1_794_000_000)

    func testMinutesUntilTheSunriseThenSeconds() {
        let light = t0.addingTimeInterval(150)
        let start = t0.addingTimeInterval(17)
        let dates = Array(NightstandSchedule(light: light).entries(from: start, mode: .normal).prefix(6))
        XCTAssertEqual(dates.map { $0.timeIntervalSince(t0) }, [17, 60, 120, 150, 151, 152])
    }

    func testWithoutSunriseOnlyFullMinutes() {
        let dates = Array(NightstandSchedule(light: nil).entries(from: t0.addingTimeInterval(59.5), mode: .normal).prefix(3))
        XCTAssertEqual(dates.map { $0.timeIntervalSince(t0) }, [59.5, 60, 120])
    }

    func testRunningSunriseTicksEverySecond() {
        // Begonnen (oder die Uhr hat eben geweckt): ab jetzt jede Sekunde.
        XCTAssertEqual(NightstandSchedule.next(after: t0, light: t0.addingTimeInterval(-600)), t0.addingTimeInterval(1))
        XCTAssertEqual(NightstandSchedule.next(after: t0.addingTimeInterval(0.5), light: t0.addingTimeInterval(1)), t0.addingTimeInterval(1.5))
    }
}
