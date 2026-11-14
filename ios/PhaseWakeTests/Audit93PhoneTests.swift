import PhaseWakeCore
import XCTest
@testable import PhaseWake

/// Tiefenaudit c3b8b01, iPhone: Start vor dem ersten Entsperren (AP3-1) – Stand erst lesen, wenn die Datei lesbar ist,
/// und nur unbekannte, geplante AlarmKit-Alarme verwerfen.
final class Audit93PhoneTests: XCTestCase {
    private var dir: URL!

    override func setUp() {
        super.setUp()
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("audit93-\(UUID().uuidString)", isDirectory: true)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: dir)
        super.tearDown()
    }

    // MARK: AP3-1

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

    /// Verwaiste AlarmKit-Alarme: nur geplante, deren Kennung die App nicht kennt.
    func testOnlyUnknownScheduledAlarmsAreOrphans() {
        let known = UUID(), orphan = UUID(), other = UUID()
        XCTAssertEqual(BackupAlarm.orphans(scheduled: [known, orphan], known: [known, other]), [orphan])
        XCTAssertEqual(BackupAlarm.orphans(scheduled: [known], known: [known]), [])
        XCTAssertEqual(BackupAlarm.orphans(scheduled: [], known: []), [])
    }

    // MARK: O-03

    /// Die Bettzeit-Linie im Diagramm rechnet die gelernte Einschlafdauer mit (die eine Bettzeit, wie Karte und Erinnerung):
    /// Wecker 07:00, Ziel 7½ Std., gelernt 15 Min → 23:15 statt 23:30.
    func testChartBedtimeIncludesLearnedLatency() throws {
        var calendar = Calendar.gregorian(in: TimeZone(identifier: "UTC")!)
        calendar.locale = Locale(identifier: "en_US_POSIX")
        let now = Date(timeIntervalSince1970: 1_793_000_000)
        let start = calendar.date(byAdding: .hour, value: -10, to: now)!
        let sleeps = [SleepSummary(start: start, end: start.addingTimeInterval(7 * 3600), asleepMinutes: 420)]
        let target = calendar.nextDate(after: now, matching: DateComponents(hour: 7, minute: 0), matchingPolicy: .nextTime)!
        func bedtime(_ latency: Int?) throws -> Int {
            let data = HistoryChartsData.of(nights: [], sleeps: sleeps, stages: [], notes: [:], nextTarget: target, goalMinutes: 450,
                                            latencyMinutes: latency, now: now, calendar: calendar)
            return try XCTUnwrap(data.period(ChartData.nights).bars?.bedtimeMinute)
        }
        // Minuten ab Mittag: 23:30 = 690, 23:15 = 675.
        XCTAssertEqual(try bedtime(nil), 690)
        XCTAssertEqual(try bedtime(15), 675)
    }

    // MARK: O-05

    private func watch(battery: Int?, autoOnset: Bool, learning: Bool, skip: RecorderSkip? = nil) -> NightCheck.WatchState {
        NightCheck.WatchState(paired: true, appInstalled: true, plan: nil, armedTarget: nil, reminder: nil, battery: battery,
                              recorderSkip: skip, notifications: nil, watchProtocol: WatchProtocol.current,
                              autoOnset: autoOnset, learning: learning)
    }

    /// Uhr bei 25 %: Bisher «Bereit», obwohl sie weder Einschlafen misst (unter 30 %) noch für die Lernphase aufzeichnet
    /// (unter 40 %). Jetzt ein Hinweis (wie Android), höchstens einer, nicht unter 20 % (dort «lade die Uhr»).
    @MainActor
    func testWatchBatteryHintsBeforeTheNight() {
        let now = Date(timeIntervalSince1970: 1_793_000_000)
        let onset = NightCheck.watchOnsetBatteryPercent
        XCTAssertEqual(onset, 30)
        XCTAssertEqual(NightCheck.watchChecks(watch(battery: 25, autoOnset: true, learning: true), now: now), [.watchOnsetBattery])
        XCTAssertEqual(NightCheck.watchChecks(watch(battery: 35, autoOnset: true, learning: true), now: now), [.watchLearnBattery])
        XCTAssertEqual(NightCheck.watchChecks(watch(battery: 35, autoOnset: true, learning: false), now: now), [])
        XCTAssertEqual(NightCheck.watchChecks(watch(battery: 25, autoOnset: false, learning: false), now: now), [])
        XCTAssertEqual(NightCheck.watchChecks(watch(battery: Learning.minBattery, autoOnset: true, learning: true), now: now), [])
        XCTAssertEqual(NightCheck.watchChecks(watch(battery: 19, autoOnset: true, learning: true), now: now), [.watchBattery])
        XCTAssertEqual(NightCheck.watchChecks(watch(battery: nil, autoOnset: true, learning: true), now: now), [])
        // Schon gemeldet, dass der Rekorder wegen des Akkus nicht startete: nicht noch einmal.
        let skip = RecorderSkip(target: now.addingTimeInterval(8 * 3600), reason: .battery)
        XCTAssertEqual(NightCheck.watchChecks(watch(battery: 25, autoOnset: true, learning: true, skip: skip), now: now), [.watchRecorderBattery])
        // Nur Hinweise: Die Karte bleibt «bereit».
        XCTAssertTrue(PhoneCheck.watchOnsetBattery.isAdvisory)
        XCTAssertTrue(PhoneCheck.watchLearnBattery.isAdvisory)
    }

    /// Prüfrunde 2 F-6: Die Uhr meldet den Akku jetzt auch am Ladegerät, mit Ladezustand. Dann misst sie trotz 25 % bzw.
    /// 15 % (sie wartet am Ladegerät): kein Hinweis und keine Warnung (wie Android `NightCheckState.collect`).
    @MainActor
    func testNoBatteryWarningWhileTheWatchCharges() {
        let now = Date(timeIntervalSince1970: 1_793_000_000)
        var charging = watch(battery: 25, autoOnset: true, learning: true)
        charging.charging = true
        XCTAssertEqual(NightCheck.watchChecks(charging, now: now), [])
        charging.battery = 15
        XCTAssertEqual(NightCheck.watchChecks(charging, now: now), [])
        XCTAssertEqual(NightCheck.watchChecks(watch(battery: 15, autoOnset: true, learning: true), now: now), [.watchBattery])
    }
}
