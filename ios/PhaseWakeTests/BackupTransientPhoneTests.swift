import PhaseWakeCore
import XCTest
@testable import PhaseWake

/// Sicherung von Probewecker und Powernap am iPhone (`BackupAlarm+Transient.swift`, Audit 17.11. CODE-01): klingelt nach
/// der Nachfrist, ohne iPhone-Wecker keine, eine neue ersetzt die alte, Verwerfen räumt auf. Mit den Ersatzobjekten aus
/// `BackupAlarmPhoneTests.swift`: nichts wird wirklich gestellt.
@MainActor
final class BackupTransientPhoneTests: XCTestCase {
    private var settings: SettingsFile!
    private var platform: FakeBackupPlatform!
    private var backup: BackupAlarm!

    private func start(watch: Bool = true) {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("transient-\(UUID().uuidString)")
        settings = SettingsFile(url: folder.appendingPathComponent("settings.v1"))
        settings.set(watch, forKey: "backupWatchPresent")
        platform = FakeBackupPlatform()
        backup = BackupAlarm(settings: settings, platform: platform, host: FakeBackupHost())
        addTeardownBlock { try? FileManager.default.removeItem(at: folder) }
    }

    private func base(phone: PhoneAlert) throws -> AlarmSpec {
        try AlarmSpec(id: "base", hour: 7, minute: 0, days: Set(Weekday.allCases), phone: phone)
    }

    func testNapBackupRingsAfterTheGraceWithSnoozeAndKnowsItsNight() async throws {
        start()
        let nap = Transient.nap(base: try base(phone: .sound), now: Date(), calendar: .gregorianLocal, minutes: 20, uuid: "a")
        let id = try XCTUnwrap(await backup.scheduleTransient(nap))
        let placed = try XCTUnwrap(platform.placed[id])
        XCTAssertEqual(placed.at.timeIntervalSince(nap.plan.target), backup.currentGrace, accuracy: 0.001)
        XCTAssertGreaterThan(backup.currentGrace, 0)
        XCTAssertEqual(placed.snoozesLeft, Snooze.maxCount)
        XCTAssertEqual(backup.storedId(BackupAlarm.transientKey), id)
        XCTAssertEqual(backup.night(for: id), RingNight(alarmId: nap.plan.alarmId, target: nap.plan.target))
    }

    func testProbeBackupWithoutWatchRingsAtTheTargetWithoutSnooze() async throws {
        start(watch: false)
        let probe = Transient.test(base: try base(phone: .sound), now: Date(), calendar: .gregorianLocal, uuid: "b")
        let id = try XCTUnwrap(await backup.scheduleTransient(probe))
        let placed = try XCTUnwrap(platform.placed[id])
        XCTAssertEqual(placed.at.timeIntervalSince(probe.plan.target), 0, accuracy: 0.001)
        XCTAssertEqual(placed.snoozesLeft, 0)
    }

    func testNoBackupWhenThePhoneStaysSilent() async throws {
        start()
        let nap = Transient.nap(base: try base(phone: .off), now: Date(), calendar: .gregorianLocal, minutes: 20, uuid: "c")
        let id = await backup.scheduleTransient(nap)
        XCTAssertNil(id)
        XCTAssertTrue(platform.placed.isEmpty)
        XCTAssertNil(backup.storedId(BackupAlarm.transientKey))
    }

    func testNewBackupReplacesTheOldOneAndCancelClearsIt() async throws {
        start()
        let first = try XCTUnwrap(await backup.scheduleTransient(
            Transient.nap(base: try base(phone: .sound), now: Date(), calendar: .gregorianLocal, minutes: 20, uuid: "d")))
        let second = try XCTUnwrap(await backup.scheduleTransient(
            Transient.nap(base: try base(phone: .sound), now: Date(), calendar: .gregorianLocal, minutes: 30, uuid: "e")))
        XCTAssertEqual(platform.cancelled, [first])
        XCTAssertEqual(Set(platform.placed.keys), [second])
        await backup.cancelTransient()
        XCTAssertEqual(platform.cancelled, [first, second])
        XCTAssertTrue(platform.placed.isEmpty)
        XCTAssertNil(backup.storedId(BackupAlarm.transientKey))
        // Ohne gemerkte Sicherung tut Verwerfen nichts.
        await backup.cancelTransient()
        XCTAssertEqual(platform.cancelled, [first, second])
    }
}
