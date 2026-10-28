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

    func testDoneUntilPerAlarm() {
        let s = storage()
        let date = Date(timeIntervalSince1970: 1_793_173_963)
        s.markDone(alarmId: "a", until: date, now: date)
        s.markDone(alarmId: "a", until: date.addingTimeInterval(-60), now: date)
        XCTAssertEqual(s.doneUntil["a"], date)
        XCTAssertNil(s.doneUntil["b"])
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
        s.clearNights()
    }

    /// Zwei Wecker mit exakt gleichem Fensterbeginn sind zwei Nächte; das Befinden gilt nur für eine.
    func testNightsKeyedByAlarmAndWindowStart() {
        let s = storage()
        let start = Date(timeIntervalSince1970: 1_793_165_400)
        func night(_ id: String) -> NightRecord {
            NightRecord(alarmId: id, windowStart: start, target: start.addingTimeInterval(1800), wokeAt: start.addingTimeInterval(900), reason: .lightSleep, snoozes: 0, epochs: [])
        }
        s.add(night("a"))
        s.add(night("b"))
        XCTAssertEqual(s.nights().map(\.alarmId).sorted(), ["a", "b"])
        s.setFeeling(4, alarmId: "b", windowStart: start)
        XCTAssertNil(s.nights().first { $0.alarmId == "a" }?.feeling)
        XCTAssertEqual(s.nights().first { $0.alarmId == "b" }?.feeling, 4)
        // Dieselbe Nacht noch einmal von der Uhr: ersetzt, Befinden bleibt.
        s.add(night("b"))
        XCTAssertEqual(s.nights().count, 2)
        XCTAssertEqual(s.nights().first { $0.alarmId == "b" }?.feeling, 4)
    }

    func testMinutesBeforeAndAfterTarget() {
        let target = Date(timeIntervalSince1970: 1_793_167_200)
        func night(_ offset: TimeInterval) -> NightRecord {
            NightRecord(alarmId: "a", windowStart: target.addingTimeInterval(-1800), target: target, wokeAt: target.addingTimeInterval(offset), reason: .deadline, snoozes: 0, epochs: [])
        }
        XCTAssertEqual(night(-12 * 60 - 30).minutesBeforeTarget, 12)
        XCTAssertEqual(night(-12 * 60 - 30).minutesAfterTarget, 0)
        XCTAssertEqual(night(7 * 60 + 59).minutesBeforeTarget, 0)
        XCTAssertEqual(night(7 * 60 + 59).minutesAfterTarget, 7)
        XCTAssertEqual(night(59).minutesAfterTarget, 0)
    }

    func testNotesArePersistedPerNight() {
        let s = storage()
        XCTAssertEqual(s.notes(), [:])
        s.setNotes(["STRESS", "ALCOHOL"], nightDate: "2026-10-27")
        s.setNotes(["NAP"], nightDate: "2026-10-26")
        s.setNotes([], nightDate: "2026-10-26")
        XCTAssertEqual(s.notes(), ["2026-10-27": ["ALCOHOL", "STRESS"]])
        s.setCustomNotes([CustomNote(id: "custom:a", name: "Tee"), CustomNote(id: "custom:b", name: " ")])
        XCTAssertEqual(s.customNotes(), [CustomNote(id: "custom:a", name: "Tee")])
        s.clearNotes()
        XCTAssertEqual(s.notes(), [:])
        XCTAssertEqual(s.customNotes().count, 1)
    }
}
