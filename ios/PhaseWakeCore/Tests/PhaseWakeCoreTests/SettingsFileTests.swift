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

    /// `PhaseWakeStorage` auf der Datei: Wecker mit Zeitstempel über Int32, erledigte Nächte, Feiertage, Schlafziel.
    func testStorageOnSettingsFile() throws {
        let storage = PhaseWakeStorage(defaults: SettingsFile(url: url), directory: dir)
        let now = Date(timeIntervalSince1970: 1_793_173_963.589)
        let snap = storage.mutate({ _ in [try! AlarmSpec(id: "a", hour: 6, minute: 30)] }, now: now)
        storage.markDone(alarmId: "a", until: now, now: now)
        storage.sleepGoal = 480
        storage.holidays = HolidayList(region: "CH-BE", dates: ["2026-12-25"])
        let reread = PhaseWakeStorage(defaults: SettingsFile(url: url), directory: dir)
        XCTAssertEqual(reread.snapshot(), snap)
        XCTAssertEqual(reread.doneUntil["a"], now)
        XCTAssertEqual(reread.sleepGoal, 480)
        XCTAssertEqual(reread.holidays, storage.holidays)
    }

    /// Gleichzeitige Änderungen von mehreren Threads gehen nicht verloren.
    func testConcurrentWrites() {
        let file = SettingsFile(url: url)
        DispatchQueue.concurrentPerform(iterations: 50) { i in file.set(i, forKey: "k\(i)") }
        let reread = SettingsFile(url: url)
        XCTAssertEqual((0..<50).filter { reread.integer(forKey: "k\($0)") == $0 }.count, 50)
    }
}
