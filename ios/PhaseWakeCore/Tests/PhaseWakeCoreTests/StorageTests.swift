import XCTest
@testable import PhaseWakeCore

final class StorageTests: XCTestCase {
    private func storage() -> PhaseWakeStorage {
        let suite = "test-\(UUID().uuidString)"
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(suite)
        return PhaseWakeStorage(defaults: UserDefaults(suiteName: suite)!, directory: dir)
    }

    func testMutateSortsAndStampsStrictlyIncreasing() throws {
        let s = storage()
        let now = Date(timeIntervalSince1970: 1000)
        let a = s.mutate({ _ in [try! AlarmSpec(id: "b", hour: 8, minute: 0), try! AlarmSpec(id: "a", hour: 6, minute: 0)] }, now: now)
        XCTAssertEqual(a.alarms.map(\.id), ["a", "b"])
        let b = s.mutate({ $0 }, now: now)
        XCTAssertEqual(b.updatedAt, a.updatedAt + 1)
        XCTAssertEqual(s.snapshot(), b)
    }

    /// Heutige Zeitstempel in Millisekunden liegen über Int32.max (32-Bit-Uhren).
    func testTimestampBeyondInt32() {
        let s = storage()
        let snap = s.mutate({ _ in [] }, now: Date(timeIntervalSince1970: 1_793_173_963.589))
        XCTAssertGreaterThan(snap.updatedAt, Int64(Int32.max))
        XCTAssertEqual(s.snapshot().updatedAt, snap.updatedAt)
    }

    /// Gleichzeitige Änderungen gehen nicht verloren.
    func testConcurrentMutationsAreSerialized() {
        let s = storage()
        DispatchQueue.concurrentPerform(iterations: 200) { i in
            s.mutate { $0 + [try! AlarmSpec(id: "a\(i)", hour: i % 24, minute: 0)] }
        }
        XCTAssertEqual(s.snapshot().alarms.count, 200)
    }

    func testDoneUntilPersists() {
        let s = storage()
        let date = Date(timeIntervalSince1970: 1_793_173_963)
        s.doneUntil = date
        XCTAssertEqual(s.doneUntil, date)
    }

    func testApplyRemoteOnlyWhenNewer() throws {
        let s = storage()
        let local = s.mutate({ _ in [try! AlarmSpec(id: "a", hour: 6, minute: 0)] }, now: Date(timeIntervalSince1970: 1000))
        XCTAssertFalse(s.applyRemote(AlarmSnapshot(alarms: [], updatedAt: local.updatedAt)))
        XCTAssertTrue(s.applyRemote(AlarmSnapshot(alarms: [], updatedAt: local.updatedAt + 5)))
        XCTAssertEqual(s.snapshot().alarms, [])
    }

    func testNightsRoundTripReplaceAndClear() {
        let s = storage()
        let start = Date(timeIntervalSince1970: 1_793_165_400)
        let night = NightRecord(alarmId: "a", windowStart: start, target: start.addingTimeInterval(1800), wokeAt: start.addingTimeInterval(900), reason: .lightSleep, snoozes: 0, epochs: [Epoch(motion: 0.001, heartRate: 50)])
        s.add(night)
        s.add(night)
        XCTAssertEqual(s.nights(), [night])
        s.clearNights()
        XCTAssertEqual(s.nights(), [])
    }
}
