import PhaseWakeCore
import XCTest
@testable import PhaseWake

/// Akku je Nacht in der Detailseite (r1AD 14.11.2026): eine Zeile mit den bekannten Teilen, ohne Wert keine Zeile.
/// Wie `BatteryLineTest` (Android).
final class BatteryLinePhoneTests: XCTestCase {
    private let t0 = Date(timeIntervalSince1970: 1_794_603_600)

    private func track(_ from: Int, _ to: Int, plugged: Bool = false) -> [BatteryPoint] {
        [BatteryPoint(at: t0, percent: from, plugged: plugged, mark: .start),
         BatteryPoint(at: t0.addingTimeInterval(36_000), percent: to, plugged: plugged, mark: .end)]
    }

    private func night(watch: [BatteryPoint]? = nil, phone: [BatteryPoint]? = nil) -> NightRecord {
        NightRecord(alarmId: "a", windowStart: t0.addingTimeInterval(34_200), target: t0.addingTimeInterval(36_000),
                    wokeAt: t0.addingTimeInterval(35_400), reason: .lightSleep, snoozes: 0, epochs: [], watchBattery: watch, phoneBattery: phone)
    }

    func testBothParts() {
        let expected = L("battery_night", L("battery_watch", Int64(14)) + " · " + L("battery_phone", Int64(3)))
        XCTAssertEqual(batteryLine(night(watch: track(80, 66), phone: track(50, 47))), expected)
    }

    func testOnlyKnownParts() {
        XCTAssertEqual(batteryLine(night(watch: track(80, 66))), L("battery_night", L("battery_watch", Int64(14))))
        // Handy die ganze Nacht am Ladegerät: kein Wert, nur die Uhr.
        XCTAssertEqual(batteryLine(night(watch: track(80, 66), phone: track(50, 90, plugged: true))), L("battery_night", L("battery_watch", Int64(14))))
        XCTAssertEqual(batteryLine(night(phone: track(50, 47))), L("battery_night", L("battery_phone", Int64(3))))
    }

    func testNoValueNoLine() {
        XCTAssertNil(batteryLine(night()))
        // Anstieg ohne Ladegerät: unbemerkt geladen, kein Wert.
        XCTAssertNil(batteryLine(night(watch: track(50, 60), phone: track(50, 90, plugged: true))))
    }
}
