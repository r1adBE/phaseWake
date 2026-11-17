import PhaseWakeCore
import XCTest
@testable import PhaseWake

/// Start vor dem ersten Entsperren, iPhone: Einstellungsdatei erst lesen, wenn sie lesbar ist (Herkunft: Tiefenaudit
/// c3b8b01 AP3-1). Verwaiste AlarmKit-Alarme prüft `BackupAlarmPhoneTests`.
final class LockedSettingsPhoneTests: XCTestCase {
    private var dir: URL!

    override func setUp() {
        super.setUp()
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("locked-settings-\(UUID().uuidString)", isDirectory: true)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: dir)
        super.tearDown()
    }

    /// Datei da, aber nicht lesbar (hier ein Ordner an ihrer Stelle; auf dem Gerät vor dem ersten Entsperren): nicht
    /// lesbar gemeldet, der Stand ist leer. Sobald sie lesbar ist: gelesen, Änderungen dieser Sitzung darübergelegt.
    func testSettingsReportUnreadableUntilFileIsAvailable() throws {
        let url = dir.appendingPathComponent("settings.v1")
        let writer = SettingsFile(url: url)
        writer.set("planned", forKey: "backupPlanned")
        XCTAssertTrue(writer.flush())
        let aside = dir.appendingPathComponent("aside")
        try FileManager.default.moveItem(at: url, to: aside)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)

        let locked = SettingsFile(url: url)
        XCTAssertFalse(locked.isReadable)
        XCTAssertNil(locked.string(forKey: "backupPlanned"))
        locked.set(true, forKey: "backupWatchPresent")
        XCTAssertFalse(locked.isReadable)

        try FileManager.default.removeItem(at: url)
        try FileManager.default.moveItem(at: aside, to: url)
        XCTAssertTrue(locked.isReadable)
        XCTAssertEqual(locked.string(forKey: "backupPlanned"), "planned")
        XCTAssertTrue(locked.bool(forKey: "backupWatchPresent"))
    }

    /// Ohne Datei (neue Installation) gilt der leere Stand als gelesen: Die App plant normal.
    func testMissingSettingsFileCountsAsReadable() {
        XCTAssertTrue(SettingsFile(url: dir.appendingPathComponent("settings.v1")).isReadable)
    }
}
