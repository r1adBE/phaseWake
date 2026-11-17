import PhaseWakeCore
import XCTest
@testable import PhaseWake

/// Akku-Hinweise der Uhr im Nacht-Check, iPhone: Einschlaf-Messung und Lernphase brauchen mehr Akku, am Ladegerät
/// keine Warnung (Herkunft: Tiefenaudit c3b8b01 O-05, Prüfrunde 2 F-6).
final class BatteryHintPhoneTests: XCTestCase {
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
