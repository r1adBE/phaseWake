import PhaseWakeCore
import WatchKit
import XCTest
@testable import PhaseWakeWatch

/// Prüfrunde 2, Apple Watch: Laden abtasten (F-1), Puls nur der Apple Watch (F-8), verwaiste Sitzung übernimmt die Nacht
/// statt ohne Vibration zu enden (HG-4).
final class Round2DetectionWatchTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_793_000_000)

    private func minutes(_ value: Double) -> Date { now.addingTimeInterval(value * 60) }

    // MARK: F-1

    /// Stichproben 22:00 lädt, 22:30 lädt, 23:00 nicht: am Ladegerät 22:00–23:00. Lädt die letzte, höchstens bis 15 Min
    /// danach (die Uhr weiss nicht, wann sie abgenommen wurde; die Puls-Regel deckt den Rest).
    func testChargeSpansFromSamples() {
        let gap = LearnRecorder.chargeCheck
        XCTAssertEqual(gap, 15 * 60)
        let spans = LearnRecorder.chargeSpans([(at: minutes(30), charging: true), (at: minutes(0), charging: true), (at: minutes(60), charging: false)],
                                              until: minutes(300), gap: gap)
        XCTAssertEqual(spans.map { $0.from }, [minutes(0)])
        XCTAssertEqual(spans.map { $0.until }, [minutes(60)])
        let open = LearnRecorder.chargeSpans([(at: minutes(0), charging: false), (at: minutes(90), charging: true)], until: minutes(300), gap: gap)
        XCTAssertEqual(open.map { $0.from }, [minutes(90)])
        XCTAssertEqual(open.map { $0.until }, [minutes(105)])
        // Nie über das Ende der Rechnung hinaus, und ohne ladende Stichprobe keine Zeit.
        XCTAssertEqual(LearnRecorder.chargeSpans([(at: minutes(90), charging: true)], until: minutes(95), gap: gap).map { $0.until }, [minutes(95)])
        XCTAssertTrue(LearnRecorder.chargeSpans([(at: minutes(0), charging: false)], until: minutes(300), gap: gap).isEmpty)
    }

    // MARK: F-8

    /// Nur Werte mit Gerät «Watch» (Apple Watch) zählen; Ring, Brustgurt oder eine App ohne Gerät nicht.
    func testOnlyApplesWatchPulseCounts() {
        XCTAssertTrue(WatchPulseSource.counts(deviceModel: "Watch"))
        XCTAssertFalse(WatchPulseSource.counts(deviceModel: "Oura"))
        XCTAssertFalse(WatchPulseSource.counts(deviceModel: nil))
        let predicate = WatchPulseSource.predicate(start: now, end: minutes(60))
        XCTAssertEqual((predicate as? NSCompoundPredicate)?.subpredicates.count, 2)
    }

    // MARK: HG-4

    /// Startet eine verwaiste Sitzung, die die Nacht bedienen kann, und läuft keine eigene: Sie übernimmt (misst und weckt
    /// mit Vibration), statt ohne Vibration zu enden. Läuft die eigene schon, misst oder klingelt eine Nacht oder gibt es
    /// keine passende: wie bisher beenden.
    func testOrphanSessionTakesOverTheNight() {
        XCTAssertTrue(SessionRules.usesOrphan(own: nil, busy: false, serves: true))
        XCTAssertTrue(SessionRules.usesOrphan(own: .scheduled, busy: false, serves: true))
        XCTAssertTrue(SessionRules.usesOrphan(own: .invalid, busy: false, serves: true))
        XCTAssertFalse(SessionRules.usesOrphan(own: .running, busy: false, serves: true))
        XCTAssertFalse(SessionRules.usesOrphan(own: nil, busy: true, serves: true))
        XCTAssertFalse(SessionRules.usesOrphan(own: nil, busy: false, serves: false))
    }
}
