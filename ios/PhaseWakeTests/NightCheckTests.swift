import PhaseWakeCore
import XCTest
@testable import PhaseWake

/// Nacht-Check am iPhone (``NightCheck/watchChecks(_:now:)``, Audit F-03): Uhr gekoppelt und App installiert, scharf
/// geschaltet (ab der Erinnerung am Abend deutlicher), Akku der Uhr, Rekorder nicht gestartet (bis zur Zielzeit),
/// Mitteilungen der Uhr und ihr Alter, ältere Uhr-App. Dazu der iPhone-Akku und welche Prüfungen nur Hinweise sind.
final class NightCheckTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_793_000_000)

    /// Nacht mit Zielzeit in `hours` Stunden, Fenster 30 Min.
    private func plan(inHours hours: Double) -> WakePlan {
        let target = now.addingTimeInterval(hours * 3600)
        return WakePlan(alarmId: "a", windowStart: target.addingTimeInterval(-1800), target: target)
    }

    /// Alles in Ordnung, ausser was der Test ändert.
    private func state(
        paired: Bool = true,
        appInstalled: Bool = true,
        plan: WakePlan? = nil,
        armed: Date? = nil,
        reminder: Date? = nil,
        battery: Int? = 80,
        skip: RecorderSkip? = nil,
        notifications: (state: WatchNotifications, checkedAt: Date?)? = nil,
        knowsSkip: Bool = true,
        watchProtocol: Int? = WatchProtocol.current
    ) -> NightCheck.WatchState {
        NightCheck.WatchState(paired: paired, appInstalled: appInstalled, plan: plan, armedTarget: armed, reminder: reminder,
                              battery: battery, recorderSkip: skip, notifications: notifications, knowsSkip: knowsSkip,
                              watchProtocol: watchProtocol)
    }

    @MainActor
    func testWithoutWatchOrAppOnlyThatIsReported() {
        XCTAssertEqual(NightCheck.watchChecks(state(paired: false, battery: 5, knowsSkip: false), now: now), [.watch])
        XCTAssertEqual(NightCheck.watchChecks(state(appInstalled: false, battery: 5), now: now), [.watchApp])
        XCTAssertEqual(NightCheck.watchChecks(state(), now: now), [])
    }

    @MainActor
    func testArmedWithin36Hours() {
        let night = plan(inHours: 8)
        // Scharf für genau diese Nacht: in Ordnung.
        XCTAssertEqual(NightCheck.watchChecks(state(plan: night, armed: night.target, reminder: now.addingTimeInterval(3600)), now: now), [])
        // Nicht scharf: vor der Erinnerung am Abend ein Hinweis, ab ihr «heute Nacht ohne Phasen».
        XCTAssertEqual(NightCheck.watchChecks(state(plan: night, reminder: now.addingTimeInterval(60)), now: now), [.watchArm])
        XCTAssertEqual(NightCheck.watchChecks(state(plan: night, armed: night.target.addingTimeInterval(86400), reminder: now), now: now),
                       [.watchArmTonight])
        // Weiter als 36 Std. voraus lässt sich noch nicht scharf schalten: keine Warnung.
        XCTAssertEqual(NightCheck.watchChecks(state(plan: plan(inHours: 40)), now: now), [])
    }

    @MainActor
    func testWatchBatteryBelow20Percent() {
        XCTAssertEqual(NightCheck.watchChecks(state(battery: 19), now: now), [.watchBattery])
        XCTAssertEqual(NightCheck.watchChecks(state(battery: NightCheck.lowBatteryPercent), now: now), [])
        // Unbekannt oder veraltet: keine Warnung.
        XCTAssertEqual(NightCheck.watchChecks(state(battery: nil), now: now), [])
    }

    @MainActor
    func testRecorderSkipUntilTheTarget() {
        let target = now.addingTimeInterval(6 * 3600)
        XCTAssertEqual(NightCheck.watchChecks(state(skip: RecorderSkip(target: target, reason: .battery)), now: now), [.watchRecorderBattery])
        XCTAssertEqual(NightCheck.watchChecks(state(skip: RecorderSkip(target: target, reason: .motion)), now: now), [.watchRecorderMotion])
        // Nacht vorbei: nichts mehr.
        XCTAssertEqual(NightCheck.watchChecks(state(skip: RecorderSkip(target: now, reason: .battery)), now: now), [])
    }

    @MainActor
    func testWatchNotifications() {
        XCTAssertEqual(NightCheck.watchChecks(state(notifications: (state: .blocked, checkedAt: now)), now: now), [.watchNotifications])
        XCTAssertEqual(NightCheck.watchChecks(state(notifications: (state: .notTimeSensitive, checkedAt: nil)), now: now), [.watchNotifications])
        // Erlaubt, aber seit über 3 Tagen nicht geprüft: nur ein Hinweis.
        let old = now.addingTimeInterval(-WatchNotifications.maxAge - 60)
        XCTAssertEqual(NightCheck.watchChecks(state(notifications: (state: .allowed, checkedAt: old)), now: now), [.watchStateOld])
        XCTAssertEqual(NightCheck.watchChecks(state(notifications: (state: .allowed, checkedAt: now)), now: now), [])
    }

    @MainActor
    func testOlderWatchApp() {
        // Ohne Überspringen und Feiertage die genauere Meldung, nicht zusätzlich die allgemeine.
        XCTAssertEqual(NightCheck.watchChecks(state(knowsSkip: false, watchProtocol: 1), now: now), [.watchUpdate])
        XCTAssertEqual(NightCheck.watchChecks(state(watchProtocol: WatchProtocol.current - 1), now: now), [.watchOutdated])
        // Unbekannter Stand: keine Warnung.
        XCTAssertEqual(NightCheck.watchChecks(state(watchProtocol: nil), now: now), [])
    }

    @MainActor
    func testSeveralProblemsInFixedOrder() {
        let night = plan(inHours: 8)
        let checks = NightCheck.watchChecks(
            state(plan: night, reminder: now.addingTimeInterval(60), battery: 10, skip: RecorderSkip(target: night.target, reason: .battery),
                  notifications: (state: .blocked, checkedAt: nil), knowsSkip: false),
            now: now
        )
        XCTAssertEqual(checks, [.watchArm, .watchBattery, .watchRecorderBattery, .watchNotifications, .watchUpdate])
    }

    @MainActor
    func testPhoneBattery() {
        XCTAssertTrue(NightCheck.phoneBatteryLow(unplugged: true, level: 0.19))
        XCTAssertFalse(NightCheck.phoneBatteryLow(unplugged: true, level: 0.2))
        // Am Strom oder unbekannt (Simulator: -1): keine Warnung.
        XCTAssertFalse(NightCheck.phoneBatteryLow(unplugged: false, level: 0.05))
        XCTAssertFalse(NightCheck.phoneBatteryLow(unplugged: true, level: -1))
    }

    func testAdvisoriesAndFixes() {
        XCTAssertEqual(PhoneCheck.allCases.filter(\.isAdvisory), [.silentSwitch, .watchStateOld])
        XCTAssertEqual(PhoneCheck.allCases.filter(\.canFix), [.alarms, .notifications])
    }
}
