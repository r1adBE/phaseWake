import PhaseWakeCore
import XCTest
@testable import PhaseWake

/// Ersatz für AlarmKit bzw. Mitteilungen (``BackupPlatform``): hält fest, was gestellt, verworfen und gestoppt wird.
@MainActor
final class FakeBackupPlatform: BackupPlatform {
    struct Placed: Equatable {
        let at: Date
        let fromWatch: Bool
        let snoozesLeft: Int
        let tone: String?
    }

    var alarmKit = true
    var placed: [UUID: Placed] = [:]
    var cancelled: [UUID] = []
    var stopped: [UUID] = []
    var alerting: [UUID] = []

    func place(id: UUID, at date: Date, fromWatch: Bool, snoozesLeft: Int, tone: WakeTone?,
               chain: Int) async -> BackupAlarm.Placement {
        placed[id] = Placed(at: date, fromWatch: fromWatch, snoozesLeft: snoozesLeft, tone: tone?.rawValue)
        return .alarmKit
    }

    func notify(id: UUID, at date: Date, tone: WakeTone?, fixed: Bool, count: Int, skip: Set<String>) async -> Int {
        count
    }

    func cancel(_ id: UUID) {
        cancelled.append(id)
        placed[id] = nil
    }

    func cancelScheduled(_ id: UUID) { placed[id] = nil }

    func stop(_ id: UUID) {
        stopped.append(id)
        alerting.removeAll { $0 == id }
    }

    func alarmKitIds() async -> Set<UUID> { Set(placed.keys) }
    func scheduledAlarmKitIds() -> [UUID]? { Array(placed.keys) }
    func alertingIds() -> [UUID] { alerting }
    func pendingNotifications() async -> [(id: String, calendar: Bool)] { [] }
    func deliveredNotificationIds() async -> [String] { [] }
    func removeNotifications(_ ids: [String]) {}
}

/// Ersatz für die übrige App (``BackupHost``): Wecker, Feiertage, ohne «Gute Nacht».
@MainActor
final class FakeBackupHost: BackupHost {
    var stateLoaded = true
    var alarms: [AlarmSpec] = []
    var holidayDates: Set<String> = []
    var disabled: [String] = []
    var alerted: [String] = []
    var stops: [RingNight] = []

    func catchUpState() -> Bool { false }
    func disableAfterRing(_ spec: AlarmSpec) { disabled.append(spec.id) }
    func phoneAlerted(_ id: String) { alerted.append(id) }
    func shifted(_ plan: WakePlan?, now: Date) -> WakePlan? { plan }
    func originalTarget(alarmId: String?, target: Date?) -> Date? { nil }
    func noteAlarms(_ alarms: [AlarmSpec], now: Date) {}
    func sendStop(_ night: RingNight) { stops.append(night) }
}

/// iPhone-Zweitwecker, gespiegelt aus Android `BackupPlanTest`, `BackupWatchTest` und
/// `BackupReplanTest`: Nachfrist je nach Uhr, Neuplanen lässt Gleiches stehen, Nachfrist behält eine fällige Sicherung,
/// «Uhr hat übernommen» mit und ohne Kennung, Mitklingeln, Sicherung nach dem Schlummern, Schlummern an einer
/// Sicherung. Eigene Einstellungsdatei und ``FakeBackupPlatform``: nichts wird wirklich gestellt.
@MainActor
final class BackupAlarmPhoneTests: XCTestCase {
    private var dir: URL!
    private var settings: SettingsFile!
    private var platform: FakeBackupPlatform!
    private var host: FakeBackupHost!
    private var backup: BackupAlarm!

    /// Zu Beginn jedes Tests: eigene Einstellungen, Ersatz für Plattform und App, Uhr gekoppelt.
    private func start() {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("backup-\(UUID().uuidString)")
        dir = folder
        settings = SettingsFile(url: folder.appendingPathComponent("settings.v1"))
        platform = FakeBackupPlatform()
        host = FakeBackupHost()
        backup = BackupAlarm(settings: settings, platform: platform, host: host)
        setWatch(true)
        addTeardownBlock { try? FileManager.default.removeItem(at: folder) }
    }

    private func setWatch(_ present: Bool) { settings.set(present, forKey: "backupWatchPresent") }

    /// Täglicher Wecker zur vollen Minute von «jetzt + `minutes`» (Ortszeit) mit Fenster `window`.
    private func daily(_ id: String, inMinutes minutes: Double, phone: PhoneAlert = .sound,
                       window: Int = AlarmSpec.defaultWindowMinutes) throws -> AlarmSpec {
        let parts = Calendar.gregorianLocal.dateComponents([.hour, .minute],
                                                           from: Date().addingTimeInterval(minutes * 60))
        return try AlarmSpec(id: id, hour: parts.hour!, minute: parts.minute!, days: Set(Weekday.allCases),
                             windowMinutes: window, phone: phone)
    }

    private func target(_ spec: AlarmSpec) throws -> Date {
        try XCTUnwrap(AlarmSchedule.nextTarget(spec, now: Date(), calendar: .gregorianLocal))
    }

    /// Nächste gestellte Sicherung (Klingelzeit in der Zukunft).
    private var next: PlannedBackup? {
        backup.plannedBackups.filter { $0.slot.fireAt > Date() }.min { $0.slot.fireAt < $1.slot.fireAt }
    }

    private func plan(_ alarms: [AlarmSpec]) async {
        host.alarms = alarms
        await backup.reschedule(alarms)
    }

    // MARK: BackupPlanTest

    func testGraceOnlyWithAWatch() async throws {
        start()
        let spec = try daily("a", inMinutes: 360)
        let at = try target(spec)
        await plan([spec])
        let first = try XCTUnwrap(next)
        XCTAssertEqual(first.slot.fireAt.timeIntervalSince(at), 120, accuracy: 0.001, "Uhr da: Nachfrist")
        XCTAssertEqual(try XCTUnwrap(platform.placed[first.id]).at.timeIntervalSince(at), 120, accuracy: 0.001)
        XCTAssertFalse(platform.placed[first.id]?.fromWatch ?? true)
        XCTAssertEqual(platform.placed[first.id]?.snoozesLeft, Snooze.maxCount)
        setWatch(false)
        await plan([spec])
        XCTAssertEqual(try XCTUnwrap(next).slot.fireAt.timeIntervalSince(at), 0, accuracy: 0.001, "ohne Uhr: pünktlich")
        XCTAssertTrue(platform.cancelled.contains(first.id), "die Sicherung mit Nachfrist ist verworfen")
    }

    func testSameMinuteAlarmsBothGetABackup() async throws {
        start()
        let a = try daily("a", inMinutes: 360)
        let c = try AlarmSpec(id: "c", hour: a.hour, minute: a.minute, days: a.days)
        await plan([a, c])
        let at = try target(a)
        let night = backup.plannedBackups.filter { abs($0.slot.target.timeIntervalSince(at)) < 0.001 }
        XCTAssertEqual(Set(night.map(\.slot.alarmId)), ["a", "c"])
    }

    func testOneShotIsSwitchedOffAfterItsFirstNight() async throws {
        start()
        let once = try AlarmSpec(id: "o", hour: 7, minute: 0)
        // Erstes Vorkommen vor einer Stunde gemerkt, seine Sicherung ist nicht mehr gestellt.
        settings.set(["o|7:0": Date().addingTimeInterval(-3600).timeIntervalSince1970], forKey: "backupOneShotTargets")
        await plan([once])
        XCTAssertEqual(host.disabled, ["o"])
        XCTAssertNil(next)
    }

    func testNothingIsPlannedBeforeTheStateIsRead() async throws {
        start()
        host.stateLoaded = false
        await plan([try daily("a", inMinutes: 360)])
        XCTAssertTrue(platform.placed.isEmpty)
        XCTAssertTrue(backup.plannedBackups.isEmpty)
    }

    // MARK: BackupReplanTest

    func testReplanKeepsTheSameBackupsAndCancelsWithoutPlan() async throws {
        start()
        let spec = try daily("a", inMinutes: 360)
        await plan([spec])
        let first = backup.plannedBackups
        XCTAssertFalse(first.isEmpty)
        let placed = platform.placed
        await plan([spec])
        XCTAssertEqual(Set(backup.plannedBackups.map(\.id)), Set(first.map(\.id)), "gleiche Sicherungen bleiben stehen")
        XCTAssertEqual(platform.placed, placed)
        XCTAssertTrue(platform.cancelled.isEmpty)
        // Wecker gelöscht: keine Sicherung mehr.
        await plan([])
        XCTAssertNil(next)
        XCTAssertEqual(Set(platform.cancelled), Set(first.map(\.id)))
    }

    func testBackupInGraceStaysWhenReplanned() async throws {
        start()
        // Zielzeit eben vorbei (diese Minute), Nachfrist läuft noch: Die Sicherung bleibt (sonst klingelte nichts).
        let now = Date()
        let minute = Date(timeIntervalSince1970: (now.timeIntervalSince1970 / 60).rounded(.down) * 60)
        let parts = Calendar.gregorianLocal.dateComponents([.hour, .minute], from: minute)
        let spec = try AlarmSpec(id: "g", hour: parts.hour!, minute: parts.minute!, days: Set(Weekday.allCases))
        let id = UUID()
        let slot = BackupSlot(alarmId: "g", target: minute, fireAt: minute.addingTimeInterval(120), sound: "",
                              zone: TimeZone.current.identifier)
        backup.plannedBackups = [PlannedBackup(id: id, slot: slot)]
        platform.placed[id] = FakeBackupPlatform.Placed(at: slot.fireAt, fromWatch: false, snoozesLeft: Snooze.maxCount,
                                                        tone: nil)
        await plan([spec])
        XCTAssertTrue(backup.plannedBackups.contains { $0.id == id })
        XCTAssertFalse(platform.cancelled.contains(id))
        // Wecker gelöscht: Auch die Sicherung in der Nachfrist geht (sie hat noch nicht geklingelt).
        await plan([])
        XCTAssertTrue(platform.cancelled.contains(id))
    }

    // MARK: BackupWatchTest

    func testWatchHandledSuppressesThatNightOnly() async throws {
        start()
        let spec = try daily("a", inMinutes: 360)
        let at = try target(spec)
        await plan([spec])
        let tonight = try XCTUnwrap(next)
        await backup.watchHandled(alarmId: "a", target: at)
        XCTAssertEqual(try XCTUnwrap(backup.suppressedUntil()["a"]).timeIntervalSince(at), 120, accuracy: 0.001)
        XCTAssertTrue(platform.cancelled.contains(tonight.id))
        // Die nächste Nacht hat ihre Sicherung.
        XCTAssertGreaterThan(try XCTUnwrap(next).slot.target, at)
    }

    func testWatchHandledWithoutIdSuppressesOnlyNightsInTheWindow() async throws {
        start()
        let soon = try daily("a", inMinutes: 10)
        let far = try daily("far", inMinutes: 360)
        let recent = try daily("recent", inMinutes: -20)
        let soonAt = try target(soon), farAt = try target(far)
        let recentAt = try XCTUnwrap(AlarmSchedule.nextTarget(recent, now: Date().addingTimeInterval(-3600),
                                                              calendar: .gregorianLocal))
        await plan([soon, far, recent])
        await backup.watchHandled(alarmId: nil, target: nil)
        // Ohne Kennung und Zielzeit (ältere Uhr): nur die Wecker, deren eigenes Fenster begonnen hat oder deren
        // Zielzeit eben vorbei ist, je bis zu ihrer Zielzeit plus Nachfrist – wie Android.
        let suppressed = backup.suppressedUntil()
        XCTAssertEqual(try XCTUnwrap(suppressed["a"]).timeIntervalSince(soonAt), 120, accuracy: 0.001)
        XCTAssertEqual(try XCTUnwrap(suppressed["recent"]).timeIntervalSince(recentAt), 120, accuracy: 0.001)
        XCTAssertNil(suppressed["far"], "Nacht noch nicht im Fenster: nicht ausgesetzt")
        let planned = backup.plannedBackups.filter { $0.slot.fireAt > Date() }
        XCTAssertFalse(planned.contains {
            $0.slot.alarmId == "a" && abs($0.slot.target.timeIntervalSince(soonAt)) < 0.001
        })
        XCTAssertTrue(planned.contains {
            $0.slot.alarmId == "far" && abs($0.slot.target.timeIntervalSince(farAt)) < 0.001
        },
                      "Nacht noch nicht im Fenster: bleibt")
    }

    func testWatchHandledWithoutIdKeepsAnAlarmWhoseWindowHasNotBegun() async throws {
        start()
        // Die Uhr klingelt jetzt ohne Kennung: Wecker in 45 Min mit 30-Min-Fenster (ab in 15 Min) bleibt gesichert,
        // der in 20 Min mit 30-Min-Fenster (seit 10 Min) ist ausgesetzt.
        let late = try daily("late", inMinutes: 45, window: 30)
        let mid = try daily("mid", inMinutes: 20, window: 30)
        let lateAt = try target(late), midAt = try target(mid)
        await plan([late, mid])
        await backup.watchHandled(alarmId: nil, target: nil)
        let suppressed = backup.suppressedUntil()
        XCTAssertNil(suppressed["late"], "Fenster noch nicht begonnen: nicht ausgesetzt")
        XCTAssertEqual(try XCTUnwrap(suppressed["mid"]).timeIntervalSince(midAt), 120, accuracy: 0.001)
        XCTAssertTrue(backup.plannedBackups.contains {
            $0.slot.alarmId == "late" && abs($0.slot.target.timeIntervalSince(lateAt)) < 0.001
        })
    }

    func testWatchRingIsForwardedAndSuppressesTheNight() async throws {
        start()
        let spec = try daily("a", inMinutes: 5)
        let at = try target(spec)
        await plan([spec])
        let rang = await backup.ringNow(alarmId: "a", alarm: spec, target: at, snoozesLeft: 2)
        let ring = try XCTUnwrap(rang)
        let placed = try XCTUnwrap(platform.placed[ring])
        XCTAssertTrue(placed.fromWatch)
        XCTAssertEqual(placed.snoozesLeft, 2)
        XCTAssertEqual(placed.at.timeIntervalSinceNow, 2, accuracy: 2)
        XCTAssertTrue(backup.ringActive)
        XCTAssertEqual(backup.currentRingNight, RingNight(alarmId: "a", target: at))
        XCTAssertEqual(try XCTUnwrap(backup.suppressedUntil()["a"]).timeIntervalSince(at), 120, accuracy: 0.001)
    }

    func testPhoneAlertOffStaysSilentButSuppresses() async throws {
        start()
        let off = try daily("off", inMinutes: 5, phone: .off)
        let at = try target(off)
        await plan([off])
        let rang = await backup.ringNow(alarmId: "off", alarm: off, target: at, snoozesLeft: 2)
        XCTAssertNil(rang)
        XCTAssertTrue(platform.placed.values.allSatisfy { !$0.fromWatch })
        XCTAssertFalse(backup.ringActive)
        XCTAssertNotNil(backup.suppressedUntil()["off"], "trotzdem ausgesetzt: Die Uhr weckt")
    }

    func testSnoozeSafetyFollowsTheWatchAndOnlyItsNight() async throws {
        start()
        let spec = try daily("a", inMinutes: 5)
        let at = try target(spec)
        await plan([spec])
        await backup.ringNow(alarmId: "a", alarm: spec, target: at, snoozesLeft: 2)
        let night = RingNight(alarmId: "a", target: at)
        let until = Date().addingTimeInterval(300)
        await backup.watchSnoozed(night: night, until: until)
        let safety = try XCTUnwrap(backup.storedId("backupSnoozeSafetyId"))
        XCTAssertEqual(try XCTUnwrap(platform.placed[safety]).at.timeIntervalSince(until), 90, accuracy: 0.001)
        XCTAssertEqual(platform.placed[safety]?.snoozesLeft, 1, "diese Runde ist verbraucht")
        XCTAssertEqual(backup.night(for: safety), night)
        // Verspätet zugestellt (Schlummer-Ende samt 90 s vorbei): keine neue.
        await backup.watchSnoozed(night: night, until: Date().addingTimeInterval(-200))
        XCTAssertEqual(backup.storedId("backupSnoozeSafetyId"), safety)
        // «stop» einer anderen Nacht lässt sie, «stop» derselben verwirft sie.
        await backup.stopRinging(night: RingNight(alarmId: "b", target: at.addingTimeInterval(1800)))
        XCTAssertEqual(backup.storedId("backupSnoozeSafetyId"), safety)
        await backup.stopRinging(night: night)
        XCTAssertNil(backup.storedId("backupSnoozeSafetyId"))
        XCTAssertTrue(platform.cancelled.contains(safety))
    }

    func testRingingAgainCancelsTheSnoozeSafety() async throws {
        start()
        let spec = try daily("a", inMinutes: 5)
        let at = try target(spec)
        await plan([spec])
        await backup.ringNow(alarmId: "a", alarm: spec, target: at, snoozesLeft: 2)
        await backup.watchSnoozed(night: RingNight(alarmId: "a", target: at), until: Date().addingTimeInterval(300))
        let safety = try XCTUnwrap(backup.storedId("backupSnoozeSafetyId"))
        await backup.ringNow(alarmId: "a", alarm: spec, target: at, snoozesLeft: 1)
        XCTAssertNil(backup.storedId("backupSnoozeSafetyId"))
        XCTAssertTrue(platform.cancelled.contains(safety))
    }

    func testStopFromWatchStopsOnlyAlarmsOfItsNight() async throws {
        start()
        let spec = try daily("a", inMinutes: 360)
        await plan([spec])
        let tonight = try XCTUnwrap(next)
        platform.alerting = [tonight.id]
        await backup.stopRinging(night: RingNight(alarmId: "b", target: tonight.slot.target))
        XCTAssertTrue(platform.stopped.isEmpty, "andere Nacht: das iPhone klingelt weiter")
        await backup.stopRinging(night: tonight.slot.night)
        XCTAssertEqual(platform.stopped, [tonight.id])
        XCTAssertEqual(host.alerted, [tonight.id.uuidString])
        // Von der App gestoppt: Sein Stopp-Intent schickt nichts an die Uhr.
        backup.backupStopped(tonight.id.uuidString)
        XCTAssertTrue(host.stops.isEmpty)
    }

    // MARK: Schlummern an einer Sicherung (Android `PhoneSnooze`)

    func testBackupSnoozeRingsAgainAndStopsTheWatchWhenEnded() async throws {
        start()
        let spec = try daily("a", inMinutes: 360)
        await plan([spec])
        let tonight = try XCTUnwrap(next)
        await backup.backupSnoozed(tonight.id.uuidString, snoozesLeft: 3)
        XCTAssertEqual(platform.stopped, [tonight.id])
        let again = try XCTUnwrap(backup.storedId(BackupAlarm.backupSnoozeKey))
        let placed = try XCTUnwrap(platform.placed[again])
        XCTAssertEqual(placed.snoozesLeft, 2)
        XCTAssertEqual(placed.at.timeIntervalSinceNow, TimeInterval(Snooze.minutes * 60), accuracy: 5)
        XCTAssertEqual(try XCTUnwrap(backup.backupSnoozedUntil()).timeIntervalSince(placed.at), 0, accuracy: 0.001)
        XCTAssertEqual(backup.night(for: again), tonight.slot.night)
        // «Halten zum Stoppen»: Schlummern vorbei, die Uhr hört für dieselbe Nacht auf.
        backup.stopBackupSnooze()
        XCTAssertNil(backup.backupSnoozedUntil())
        XCTAssertTrue(platform.cancelled.contains(again))
        XCTAssertEqual(host.stops, [tonight.slot.night])
    }

    func testUserStopAtABackupStopsTheWatchForThatNight() async throws {
        start()
        let spec = try daily("a", inMinutes: 360)
        await plan([spec])
        let tonight = try XCTUnwrap(next)
        backup.backupStopped(tonight.id.uuidString)
        XCTAssertEqual(host.stops, [tonight.slot.night])
        // Ohne gemerkte Nacht nichts (sonst träfe es jede Nacht der Uhr).
        backup.backupStopped(UUID().uuidString)
        XCTAssertEqual(host.stops.count, 1)
    }

    /// Verwaiste AlarmKit-Alarme: nur geplante, deren Kennung die App nicht kennt.
    func testOnlyUnknownScheduledAlarmsAreOrphans() {
        let known = UUID(), orphan = UUID(), other = UUID()
        XCTAssertEqual(BackupAlarm.orphans(scheduled: [known, orphan], known: [known, other]), [orphan])
        XCTAssertEqual(BackupAlarm.orphans(scheduled: [known], known: [known]), [])
        XCTAssertEqual(BackupAlarm.orphans(scheduled: [], known: []), [])
    }
}
