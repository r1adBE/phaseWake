import PhaseWakeCore
import XCTest
@testable import PhaseWake

/// Build-Nummer der Testversion in «Über», Diagnose und Nacht-Check (Entscheid r1AD 14.11.2026), wie
/// `BuildNumberTest` und `NightCheckBuildTest` auf Android.
final class BuildNumberPhoneTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_793_000_000)

    func testLabelWithAndWithoutNumber() {
        XCTAssertEqual(BuildNumber.label(version: "16.2.2", build: 96), "16.2.2 (96)")
        XCTAssertEqual(BuildNumber.label(version: "16.2.2", build: nil), "16.2.2")
    }

    func testBundleVersion() {
        XCTAssertEqual(BuildNumber.of("96"), 96)
        // Lokal gebaut (CURRENT_PROJECT_VERSION 1), fehlend oder unlesbar: unbekannt, also ohne Klammer.
        XCTAssertNil(BuildNumber.of("1"))
        XCTAssertNil(BuildNumber.of(nil))
        XCTAssertNil(BuildNumber.of("1.0"))
    }

    func testDiffersOnlyWithBothNumbers() {
        XCTAssertTrue(BuildNumber.differs(phone: 96, watch: 95))
        XCTAssertFalse(BuildNumber.differs(phone: 96, watch: 96))
        XCTAssertFalse(BuildNumber.differs(phone: 96, watch: nil))
        XCTAssertFalse(BuildNumber.differs(phone: nil, watch: 95))
    }

    func testContextOldAndNew() {
        var context: [String: Any] = [:]
        BuildNumber.applyWatch(to: &context, build: 96)
        XCTAssertEqual(context[SyncKeys.watchProtocol] as? Int64, Int64(WatchProtocol.current))
        XCTAssertEqual(BuildNumber.watchBuild(in: context), 96)
        // Ohne bekannte Nummer fällt ein früher gemeldetes Feld weg.
        BuildNumber.applyWatch(to: &context, build: nil)
        XCTAssertNil(context[SyncKeys.watchBuild])
        XCTAssertNil(BuildNumber.watchBuild(in: context))
        // Ältere Uhr (Kontext ohne Feld) oder lokaler Build: unbekannt, kein Hinweis.
        XCTAssertNil(BuildNumber.watchBuild(in: [SyncKeys.watchProtocol: Int64(3)]))
        XCTAssertNil(BuildNumber.watchBuild(in: [SyncKeys.watchBuild: Int64(1)]))
    }

    private func state(phone: Int?, watch: Int?, watchProtocol: Int? = WatchProtocol.current) -> NightCheck.WatchState {
        NightCheck.WatchState(paired: true, appInstalled: true, plan: nil, armedTarget: nil, reminder: nil, battery: 80,
                              recorderSkip: nil, notifications: nil, watchProtocol: watchProtocol,
                              phoneBuild: phone, watchBuild: watch)
    }

    @MainActor
    func testNightCheckHint() {
        XCTAssertEqual(NightCheck.watchChecks(state(phone: 96, watch: 95), now: now), [.buildMismatch(phone: 96, watch: 95)])
        XCTAssertTrue(PhoneCheck.buildMismatch(phone: 96, watch: 95).isAdvisory, "nur ein Hinweis, die Karte bleibt bereit")
        XCTAssertEqual(NightCheck.watchChecks(state(phone: 96, watch: 96), now: now), [])
        XCTAssertEqual(NightCheck.watchChecks(state(phone: 96, watch: nil), now: now), [], "ältere Uhr oder lokaler Build")
        XCTAssertEqual(NightCheck.watchChecks(state(phone: nil, watch: 95), now: now), [])
        // Mit «Uhr-App veraltet» kein zweiter Hinweis.
        XCTAssertEqual(NightCheck.watchChecks(state(phone: 96, watch: 95, watchProtocol: 0), now: now), [.watchOutdated])
        XCTAssertNil(NightCheck.buildHint(phone: 96, watch: 95, outdated: true))
    }
}
