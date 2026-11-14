import XCTest
@testable import PhaseWakeCore

final class SettingsFileTests: XCTestCase {
    private var dir: URL!
    private var suite: String!

    override func setUp() {
        super.setUp()
        suite = "settings-\(UUID().uuidString)"
        dir = FileManager.default.temporaryDirectory.appendingPathComponent(suite, isDirectory: true)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: dir)
        UserDefaults().removePersistentDomain(forName: suite)
        super.tearDown()
    }

    private var url: URL { dir.appendingPathComponent("settings.v1") }
    private func legacy() -> UserDefaults { UserDefaults(suiteName: suite)! }

    /// Typen wie in `UserDefaults`, auch nach dem Neuladen aus der Datei.
    func testValuesSurviveReload() throws {
        let file = SettingsFile(url: url)
        file.set("a", forKey: "text")
        file.set(450, forKey: "int")
        file.set(true, forKey: "bool")
        file.set(1.5, forKey: "double")
        file.set(NSNumber(value: Int64(1_793_173_963_589)), forKey: "ms")
        file.set(Data([1, 2]), forKey: "data")
        file.set(["x": 1.25], forKey: "dict")
        file.set(["p", "q"], forKey: "list")
        for store in [file, SettingsFile(url: url)] {
            XCTAssertEqual(store.string(forKey: "text"), "a")
            XCTAssertEqual(store.integer(forKey: "int"), 450)
            XCTAssertTrue(store.bool(forKey: "bool"))
            XCTAssertEqual(store.double(forKey: "double"), 1.5)
            XCTAssertEqual((store.object(forKey: "ms") as? NSNumber)?.int64Value, 1_793_173_963_589)
            XCTAssertEqual(store.data(forKey: "data"), Data([1, 2]))
            XCTAssertEqual(store.dictionary(forKey: "dict") as? [String: Double], ["x": 1.25])
            XCTAssertEqual(store.stringArray(forKey: "list"), ["p", "q"])
            XCTAssertEqual(store.object(forKey: "bool") as? Bool, true)
        }
    }

    func testMissingValuesAndRemove() {
        let file = SettingsFile(url: url)
        XCTAssertNil(file.object(forKey: "x"))
        XCTAssertEqual(file.integer(forKey: "x"), 0)
        XCTAssertFalse(file.bool(forKey: "x"))
        XCTAssertEqual(file.double(forKey: "x"), 0)
        file.set("a", forKey: "x")
        file.set(nil, forKey: "x")
        file.set("b", forKey: "y")
        file.removeObject(forKey: "y")
        XCTAssertNil(SettingsFile(url: url).string(forKey: "x"))
        XCTAssertNil(SettingsFile(url: url).string(forKey: "y"))
        XCTAssertTrue(file.flush())
    }

    /// Kein Typ einer Property List: nichts gespeichert, die Datei bleibt schreibbar.
    func testInvalidValueIgnored() {
        let file = SettingsFile(url: url)
        file.set(Date.self, forKey: "bad")
        file.set("ok", forKey: "good")
        XCTAssertNil(file.object(forKey: "bad"))
        XCTAssertEqual(SettingsFile(url: url).string(forKey: "good"), "ok")
    }

    /// Übernahme: Werte in die Datei, danach aus `UserDefaults` gelöscht; andere Schlüssel bleiben dort.
    func testAdoptMovesKeysAndRemovesThem() {
        let old = legacy()
        old.set("06:30", forKey: "alarms")
        old.set(480, forKey: "sleepGoalMinutes")
        old.set(true, forKey: "onboardingDone")
        let file = SettingsFile(url: url)
        file.adopt(["alarms", "sleepGoalMinutes", "missing"], from: old)
        XCTAssertNil(old.object(forKey: "alarms"))
        XCTAssertNil(old.object(forKey: "sleepGoalMinutes"))
        XCTAssertTrue(old.bool(forKey: "onboardingDone"))
        let reread = SettingsFile(url: url)
        XCTAssertEqual(reread.string(forKey: "alarms"), "06:30")
        XCTAssertEqual(reread.integer(forKey: "sleepGoalMinutes"), 480)
        XCTAssertNil(reread.object(forKey: "missing"))
    }

    /// Ein Wert in der Datei ist neuer als der in `UserDefaults` (abgebrochene Übernahme): Er gilt.
    func testAdoptKeepsValueAlreadyInFile() {
        let old = legacy()
        old.set(400, forKey: "sleepGoalMinutes")
        let file = SettingsFile(url: url)
        file.set(500, forKey: "sleepGoalMinutes")
        file.adopt(["sleepGoalMinutes"], from: old)
        XCTAssertEqual(file.integer(forKey: "sleepGoalMinutes"), 500)
        XCTAssertNil(old.object(forKey: "sleepGoalMinutes"))
    }

    /// Endete die App nach dem Schreiben, aber vor dem Löschen: Ein inzwischen entfernter Wert kommt nicht zurück.
    func testAdoptDoesNotResurrectRemovedValue() {
        let old = legacy()
        old.set(NSNumber(value: Int64(5)), forKey: "historyClearedAt")
        let file = SettingsFile(url: url)
        file.adopt(["historyClearedAt"], from: old)
        file.removeObject(forKey: "historyClearedAt")
        old.set(NSNumber(value: Int64(5)), forKey: "historyClearedAt")
        let restarted = SettingsFile(url: url)
        restarted.adopt(["historyClearedAt"], from: old)
        XCTAssertNil(restarted.object(forKey: "historyClearedAt"))
        XCTAssertNil(old.object(forKey: "historyClearedAt"))
    }

    /// Datei da, aber nicht lesbar (hier ein Ordner an ihrer Stelle; auf dem Gerät vor dem ersten Entsperren): nie
    /// überschreiben, nichts aus `UserDefaults` löschen; Änderungen kommen nach dem ersten gelungenen Lesen dazu.
    func testUnreadableFileIsNeverOverwritten() throws {
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        let old = legacy()
        old.set("06:30", forKey: "alarms")
        let file = SettingsFile(url: url)
        file.adopt(["alarms"], from: old)
        XCTAssertEqual(old.string(forKey: "alarms"), "06:30")
        file.set(420, forKey: "sleepGoalMinutes")
        XCTAssertEqual(file.integer(forKey: "sleepGoalMinutes"), 420)
        XCTAssertFalse(file.flush())
        try FileManager.default.removeItem(at: url)
        XCTAssertTrue(file.flush())
        XCTAssertEqual(SettingsFile(url: url).integer(forKey: "sleepGoalMinutes"), 420)
    }

    /// Kaputte Datei: beiseitegelegt, dann ein leerer Stand, der wieder gespeichert wird.
    func testBrokenFileIsSetAside() throws {
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try Data("kein plist".utf8).write(to: url)
        let file = SettingsFile(url: url)
        XCTAssertNil(file.object(forKey: "alarms"))
        XCTAssertTrue(FileManager.default.fileExists(atPath: url.appendingPathExtension("broken").path))
        file.set("a", forKey: "k")
        XCTAssertEqual(SettingsFile(url: url).string(forKey: "k"), "a")
    }

    /// Prüfrunde 2 SP-7 (wie Android): Der leere Stand nach einer kaputten Datei ist markiert und bleibt es; die Kopie
    /// enthält die kaputte Datei. Ein heiler oder neuer Stand ist nicht markiert.
    func testResetAfterBrokenFileIsMarked() throws {
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let garbage = Data("kein plist".utf8)
        try garbage.write(to: url)
        let file = SettingsFile(url: url)
        XCTAssertTrue(file.wasReset)
        XCTAssertGreaterThan(file.integer(forKey: SettingsFile.resetAtKey), 0)
        XCTAssertEqual(try Data(contentsOf: url.appendingPathExtension("broken")), garbage)
        file.set("a", forKey: "k")
        XCTAssertTrue(SettingsFile(url: url).wasReset, "auch nach dem Neuladen")
        let other = dir.appendingPathComponent("other.v1")
        XCTAssertFalse(SettingsFile(url: other).wasReset)
    }

    /// `PhaseWakeStorage` auf der Datei: Wecker mit Zeitstempel über Int32, erledigte Nächte, Feiertage, Schlafziel.
    func testStorageOnSettingsFile() throws {
        let storage = PhaseWakeStorage(defaults: SettingsFile(url: url), directory: dir)
        let now = Date(timeIntervalSince1970: 1_793_173_963.589)
        let snap = try XCTUnwrap(storage.mutate({ _ in [try! AlarmSpec(id: "a", hour: 6, minute: 30)] }, now: now))
        storage.markDone(alarmId: "a", until: now, now: now)
        storage.sleepGoal = 480
        storage.holidays = HolidayList(region: "CH-BE", dates: ["2026-12-25"])
        let reread = PhaseWakeStorage(defaults: SettingsFile(url: url), directory: dir)
        XCTAssertEqual(reread.snapshot(), snap)
        XCTAssertEqual(reread.doneUntil["a"], now)
        XCTAssertEqual(reread.sleepGoal, 480)
        XCTAssertEqual(reread.holidays, storage.holidays)
    }

    /// Nur lesend (Komplikation neben der Uhr-App): sieht jede Änderung des Schreibenden sofort, ohne alten Stand.
    func testReadOnlySeesChangesOfWriter() {
        let writer = SettingsFile(url: url)
        let reader = SettingsFile(url: url, readOnly: true)
        XCTAssertNil(reader.string(forKey: "alarms"))
        writer.set("06:30", forKey: "alarms")
        XCTAssertEqual(reader.string(forKey: "alarms"), "06:30")
        writer.set("07:00", forKey: "alarms")
        XCTAssertEqual(reader.string(forKey: "alarms"), "07:00")
        writer.removeObject(forKey: "alarms")
        XCTAssertNil(reader.string(forKey: "alarms"))
    }

    /// Nur lesend: schreibt nie, legt nichts an, übernimmt nichts und lässt eine kaputte Datei liegen.
    func testReadOnlyNeverWrites() throws {
        let reader = SettingsFile(url: url, readOnly: true)
        XCTAssertFalse(FileManager.default.fileExists(atPath: dir.path))
        reader.set("x", forKey: "k")
        XCTAssertNil(reader.object(forKey: "k"))
        XCTAssertTrue(reader.flush())
        let old = legacy()
        old.set("06:30", forKey: "alarms")
        reader.adopt(["alarms"], from: old)
        XCTAssertEqual(old.string(forKey: "alarms"), "06:30")
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try Data("kein plist".utf8).write(to: url)
        XCTAssertNil(reader.object(forKey: "alarms"))
        XCTAssertTrue(FileManager.default.fileExists(atPath: url.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.appendingPathExtension("broken").path))
    }

    /// Nur lesend und die Datei kurz nicht lesbar: Der zuletzt gelesene Stand bleibt; fehlt sie, ist der Stand leer.
    func testReadOnlyKeepsLastStateWhileUnreadable() throws {
        SettingsFile(url: url).set("06:30", forKey: "alarms")
        let reader = SettingsFile(url: url, readOnly: true)
        XCTAssertEqual(reader.string(forKey: "alarms"), "06:30")
        try FileManager.default.removeItem(at: url)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        XCTAssertEqual(reader.string(forKey: "alarms"), "06:30")
        try FileManager.default.removeItem(at: url)
        XCTAssertNil(reader.string(forKey: "alarms"))
    }

    /// `PhaseWakeStorage` nur lesend auf der Datei der Uhr-App: Wecker, erledigte Nächte und Feiertage frisch.
    func testStorageReadOnlyFollowsWriter() throws {
        let app = PhaseWakeStorage(defaults: SettingsFile(url: url), directory: dir)
        let complication = PhaseWakeStorage(defaults: SettingsFile(url: url, readOnly: true), directory: dir)
        XCTAssertEqual(complication.snapshot().alarms, [])
        let now = Date(timeIntervalSince1970: 1_793_173_963.589)
        let snap = try XCTUnwrap(app.mutate({ _ in [try! AlarmSpec(id: "a", hour: 6, minute: 30)] }, now: now))
        app.markDone(alarmId: "a", until: now, now: now)
        app.holidays = HolidayList(region: "CH-BE", dates: ["2026-12-25"])
        XCTAssertEqual(complication.snapshot(), snap)
        XCTAssertEqual(complication.doneUntil["a"], now)
        XCTAssertEqual(complication.holidays, app.holidays)
        app.mutate({ _ in [] }, now: now)
        XCTAssertEqual(complication.snapshot().alarms, [])
    }

    /// Gleichzeitige Änderungen von mehreren Threads gehen nicht verloren.
    func testConcurrentWrites() {
        let file = SettingsFile(url: url)
        DispatchQueue.concurrentPerform(iterations: 50) { i in file.set(i, forKey: "k\(i)") }
        let reread = SettingsFile(url: url)
        XCTAssertEqual((0..<50).filter { reread.integer(forKey: "k\($0)") == $0 }.count, 50)
    }
}
