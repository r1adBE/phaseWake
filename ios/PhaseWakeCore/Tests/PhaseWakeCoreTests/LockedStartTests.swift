import XCTest
@testable import PhaseWakeCore

/// Prüfrunde 2 (SP-3, SP-5): Start vor dem ersten Entsperren. Eine Datei an ihrem Platz, die (noch) nicht lesbar ist,
/// steht hier für die geschützte Datei auf dem Gerät (ein Ordner an ihrer Stelle, wie in `Audit93PhoneTests`).
final class LockedStartTests: XCTestCase {
    private var dir: URL!

    override func setUp() {
        super.setUp()
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("locked-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: dir)
        super.tearDown()
    }

    /// Macht `url` unlesbar (beiseite, Ordner an ihrer Stelle); der Aufruf von `restore` macht sie wieder lesbar.
    private func lock(_ url: URL) throws -> () throws -> Void {
        let aside = url.appendingPathExtension("aside")
        try FileManager.default.moveItem(at: url, to: aside)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return {
            try FileManager.default.removeItem(at: url)
            try FileManager.default.moveItem(at: aside, to: url)
        }
    }

    private func night(_ alarmId: String, at seconds: TimeInterval) -> NightRecord {
        let start = Date(timeIntervalSince1970: seconds)
        return NightRecord(alarmId: alarmId, windowStart: start, target: start.addingTimeInterval(1800), wokeAt: start.addingTimeInterval(900),
                           reason: .lightSleep, snoozes: 0, epochs: [Epoch(motion: 0.001, heartRate: 50)])
    }

    // MARK: ProtectedFile

    func testProtectedFileTellsMissingFromUnreadable() throws {
        let url = dir.appendingPathComponent("learning.v1")
        XCTAssertEqual(ProtectedFile.read(url), .missing)
        try Data("x".utf8).write(to: url)
        XCTAssertEqual(ProtectedFile.read(url), .data(Data("x".utf8)))
        let restore = try lock(url)
        XCTAssertEqual(ProtectedFile.read(url), .unreadable)
        try restore()
        XCTAssertEqual(ProtectedFile.read(url), .data(Data("x".utf8)))
    }

    // MARK: SP-3

    /// Wörterbuch vor dem ersten Lesen geändert (lesen, ändern, schreiben gegen den leeren Stand, etwa
    /// `backupSuppressedByAlarm`): Beim Lesen kommt der Eintrag dazu, die übrigen der Datei bleiben.
    func testDictionaryChangedBeforeFirstReadKeepsOtherEntries() throws {
        let url = dir.appendingPathComponent("settings.v1")
        let writer = SettingsFile(url: url)
        writer.set(["A": 1000.0, "C": 2000.0], forKey: "backupSuppressedByAlarm")
        writer.set("x", forKey: "text")
        let restore = try lock(url)

        let locked = SettingsFile(url: url)
        var raw = locked.dictionary(forKey: "backupSuppressedByAlarm") as? [String: Double] ?? [:]
        raw["B"] = 3000.0
        locked.set(raw, forKey: "backupSuppressedByAlarm")
        locked.set("y", forKey: "text")
        try restore()

        XCTAssertTrue(locked.isReadable)
        XCTAssertEqual(locked.dictionary(forKey: "backupSuppressedByAlarm") as? [String: Double], ["A": 1000, "B": 3000, "C": 2000])
        XCTAssertEqual(locked.string(forKey: "text"), "y", "andere Typen: der neuere Wert gilt")
        XCTAssertEqual(SettingsFile(url: url).dictionary(forKey: "backupSuppressedByAlarm") as? [String: Double],
                       ["A": 1000, "B": 3000, "C": 2000])
    }

    /// Wecker und erledigte Nächte, solange die Einstellungen nicht gelesen sind: nichts ändern, den Stand der Gegenseite
    /// nicht als übernommen merken; nach dem Lesen gilt er, und kein Eintrag geht verloren.
    func testStorageWaitsForUnreadSettings() throws {
        let url = dir.appendingPathComponent("settings.v1")
        let now = Date(timeIntervalSince1970: 1_793_173_963)
        let writer = PhaseWakeStorage(defaults: SettingsFile(url: url), directory: dir)
        let a = try AlarmSpec(id: "a", hour: 6, minute: 30)
        let saved = try XCTUnwrap(writer.mutate({ _ in [a] }, now: now))
        writer.markDone(alarmId: "a", until: now, now: now)
        let restore = try lock(url)

        let locked = PhaseWakeStorage(defaults: SettingsFile(url: url), directory: dir)
        XCTAssertNil(locked.mutate({ $0 + [try! AlarmSpec(id: "x", hour: 5, minute: 0)] }, now: now), "leerer Stand ist nicht «nichts gespeichert»")
        let b = try AlarmSpec(id: "b", hour: 7, minute: 0)
        let remote = AlarmSnapshot(alarms: [b], updatedAt: saved.updatedAt + 10, stamps: ["b": saved.updatedAt + 10])
        XCTAssertFalse(locked.applyRemoteContext(remote, now: now))
        locked.markDone(alarmId: "b", until: now, now: now)
        try restore()

        XCTAssertTrue(locked.applyRemoteContext(remote, now: now), "nicht als schon übernommen gemerkt")
        XCTAssertEqual(Set(locked.snapshot().alarms.map(\.id)), ["a", "b"])
        XCTAssertEqual(locked.doneUntil, ["a": now, "b": now])
    }

    // MARK: SP-5

    /// Ein ungültiges Byte im Verlauf kostet nicht alle Nächte (wie `decodeToString` auf Android), und die nächste
    /// Nacht überschreibt sie nicht.
    func testInvalidByteKeepsOtherNights() throws {
        let storage = PhaseWakeStorage(defaults: UserDefaults(suiteName: "locked-\(UUID().uuidString)")!, directory: dir)
        let first = night("a", at: 1_793_165_400), second = night("a", at: 1_793_251_800)
        storage.add(first)
        storage.add(second)
        let url = dir.appendingPathComponent("nights.v1")
        var bytes = try Data(contentsOf: url)
        bytes.insert(0xFF, at: 0)
        try bytes.write(to: url)

        XCTAssertTrue(storage.nights().contains(second))
        let third = night("a", at: 1_793_338_200)
        XCTAssertTrue(storage.add(third))
        XCTAssertTrue(storage.nights().contains(second))
        XCTAssertTrue(storage.nights().contains(third))
    }

    /// Verlauf nicht lesbar: keine Nacht schreibt ihn leer; danach ist er unverändert da.
    func testUnreadableHistoryIsNeverOverwritten() throws {
        let storage = PhaseWakeStorage(defaults: UserDefaults(suiteName: "locked-\(UUID().uuidString)")!, directory: dir)
        let first = night("a", at: 1_793_165_400), second = night("a", at: 1_793_251_800)
        storage.add(first)
        let restore = try lock(dir.appendingPathComponent("nights.v1"))

        XCTAssertFalse(storage.nightsReadable)
        XCTAssertFalse(storage.add(second))
        storage.setFeeling(4, alarmId: "a", windowStart: first.windowStart)
        XCTAssertEqual(storage.nights(), [])
        try restore()

        XCTAssertTrue(storage.nightsReadable)
        XCTAssertEqual(storage.nights(), [first])
        XCTAssertTrue(storage.add(second))
        XCTAssertEqual(storage.nights(), [first, second])
    }
}
