import PhaseWakeCore
import XCTest
@testable import PhaseWakeWatch

/// Startseite der Uhr (Audit 17.11. PERF-04): Die letzte Nacht ist gemerkt (``WatchLastNight``), statt bei jedem
/// Neuzeichnen den Verlauf zu lesen, und folgt jeder Änderung der Nächte sofort.
final class LastNightWatchTests: XCTestCase {
    private let start = Date(timeIntervalSince1970: 1_793_000_000)

    private func storage() -> PhaseWakeStorage {
        let suite = "test-\(UUID().uuidString)"
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(suite)
        return PhaseWakeStorage(defaults: UserDefaults(suiteName: suite)!, directory: dir)
    }

    private func night(_ day: Int, id: String = "a") -> NightRecord {
        let window = start.addingTimeInterval(TimeInterval(day) * 86400)
        return NightRecord(alarmId: id, windowStart: window, target: window.addingTimeInterval(1800), wokeAt: window.addingTimeInterval(1200),
                           reason: .lightSleep, snoozes: 0, epochs: [Epoch(motion: 0.01, heartRate: 52)])
    }

    @MainActor
    func testStartsWithTheStoredLastNight() {
        let s = storage()
        s.add(night(0))
        s.add(night(1))
        XCTAssertEqual(WatchLastNight(storage: s).night, night(1))
        XCTAssertNil(WatchLastNight(storage: storage()).night)
    }

    @MainActor
    func testFollowsAddReplaceAndClear() {
        let s = storage()
        let last = WatchLastNight(storage: s)
        XCTAssertTrue(last.add(night(0)))
        XCTAssertEqual(last.night, night(0))
        // Eine ältere Nacht ändert die letzte nicht (Verlauf nach Fensterbeginn).
        last.add(night(-3))
        XCTAssertEqual(last.night, night(0))
        last.add(night(2, id: "b"))
        XCTAssertEqual(last.night, night(2, id: "b"))
        last.replace([night(0), night(-3)])
        XCTAssertEqual(last.night, night(0))
        XCTAssertEqual(s.nights().count, 2)
        last.clear()
        XCTAssertNil(last.night)
        XCTAssertTrue(s.nights().isEmpty)
    }

    @MainActor
    func testStoredValueIsNotReadAgainUntilAChange() {
        let s = storage()
        let last = WatchLastNight(storage: s)
        // Am Speicher vorbei geschrieben: Die Startseite liest den Verlauf nicht bei jedem Zugriff neu.
        s.add(night(0))
        last.loadIfNeeded()
        XCTAssertNil(last.night)
        last.add(night(1))
        XCTAssertEqual(last.night, night(1))
    }
}
