import XCTest
import PhaseWakeCore

/// Prüfrunde 2, Erkennung (gleiche Fälle wie Kotlin `SnoozeAndOnsetTest`, `OnsetMonitorTest`, `GoodNightOnsetTest`):
/// Puls in der ruhigen Strecke (F-1), Zeit am Ladegerät verschiebt die Zeitgrenze (HG-3), später Start (HG-2), Laden statt
/// Akkugrenze (F-6).
final class OnsetRound2Tests: XCTestCase {
    private let since = Date(timeIntervalSince1970: 1_793_570_400) // 2026-11-01 23:00 Zürich
    private let quiet = 0.0005
    private let busy = 0.5

    private func minutes(_ m: Double) -> Date { since.addingTimeInterval(m * 60) }

    // MARK: F-1

    /// Puls in Epoche 40, ab 44 liegt die Uhr still und misst keinen mehr: vorher «eingeschlafen beim Ablegen» (44).
    func testAStretchNeedsAPulseInsideIt() {
        let motions: [Double?] = (0..<100).map { $0 < 44 ? busy : quiet }
        XCTAssertNil(SleepOnset.find(motions, threshold: 0.002, heartRates: (0..<100).map { $0 == 40 ? 58.0 : nil }))
        // Getragen, Puls nur alle 12 Min, still ab 36: bei 36, nicht erst beim nächsten Puls (48).
        let sleeper: [Double?] = (0..<120).map { $0 < 36 ? busy : quiet }
        XCTAssertEqual(SleepOnset.find(sleeper, threshold: 0.002, heartRates: (0..<120).map { $0 % 24 == 0 ? 58.0 : nil }), 36)
        // Puls alle 15 Min (grösster üblicher Abstand der Apple Watch): ebenso.
        XCTAssertEqual(SleepOnset.find(sleeper, threshold: 0.002, heartRates: (0..<120).map { $0 % 30 == 0 ? 58.0 : nil }), 36)
    }

    /// Ein Puls vor dem Beginn der Messung zählt für die erste Strecke (`heartLead`).
    func testAPulseBeforeTheMeasurementCounts() {
        let motions: [Double?] = Array(repeating: quiet, count: 60)
        let rates: [Double?] = (0..<70).map { [0, 25, 50].contains($0) ? 58.0 : nil }
        XCTAssertEqual(SleepOnset.find(motions, threshold: 0.002, heartRates: rates, heartLead: 10), 0)
        XCTAssertEqual(SleepOnset.find(motions, threshold: 0.002, heartRates: Array(rates.dropFirst(10))), 15)
    }

    /// Epochen am Ladegerät aus den abgetasteten Zeiten.
    func testChargingSpansBecomeEpochs() {
        let away = RecorderOnset.awayEpochs([(from: minutes(1), until: minutes(2))], start: since, count: 6)
        XCTAssertEqual(away, [false, false, true, true, false, false])
        XCTAssertEqual(RecorderOnset.awayEpochs([], start: since, count: 0), [])
    }

    // MARK: HG-3

    /// Messung ab 23:00, Uhr bis 00:00 am Ladegerät, danach still ohne Puls bis 00:55, ab dann getragen (Puls je 5 Min):
    /// Die ruhige Strecke ab 00:55 ist erst um 01:10 vollständig. Vorher gab die Messung um 01:00 auf (2 Std., ruhig, also
    /// keine Verlängerung); jetzt verschiebt die Zeit ohne Tragen (Ladegerät, dann ohne Puls) die Grenze.
    func testTimeOnTheChargerMovesTheLimit() {
        let count = 300
        let motions: [Double?] = Array(repeating: quiet, count: count)
        let away = (0..<count).map { $0 < 120 }
        let cap = RecorderOnset.extendCap(since: since, windowStart: since.addingTimeInterval(8 * 3600))
        let rates: [Double?] = (0..<count).map { $0 >= 230 && $0 % 10 == 0 ? 58.0 : nil }
        let result = RecorderOnset.measure(motions: motions, since: since, threshold: DetectorConfig.defaultLightThreshold,
                                           personalFloor: nil, latest: since.addingTimeInterval(9 * 3600), extendCap: cap,
                                           heartRates: rates, away: away).result
        XCTAssertEqual(result, .asleep(at: minutes(115), index: 230))
        XCTAssertEqual(RecorderOnset.awayUntil(baseEnd: minutes(120), awayEpochs: 120, latestGiveUp: minutes(500), cap: cap), minutes(180))
        XCTAssertEqual(RecorderOnset.awayUntil(baseEnd: minutes(120), awayEpochs: 600, latestGiveUp: minutes(500), cap: cap), cap)
        XCTAssertNil(RecorderOnset.awayUntil(baseEnd: minutes(120), awayEpochs: 0, latestGiveUp: minutes(500), cap: cap))
        XCTAssertNil(RecorderOnset.awayUntil(baseEnd: minutes(120), awayEpochs: 60, latestGiveUp: minutes(100), cap: cap))
    }

    // MARK: HG-2

    /// Entscheid r1AD 14.11.2026: spät bei offener App gestartet, misst ab dem Start; spät im Hintergrund nicht gemessen.
    func testLateStartForegroundOrBackground() {
        let bedAt = minutes(30)
        XCTAssertEqual(RecorderOnset.lateStart(recorderFrom: minutes(0), bedAt: bedAt, foreground: false), .onTime)
        XCTAssertEqual(RecorderOnset.lateStart(recorderFrom: minutes(45), bedAt: bedAt, foreground: true), .foreground)
        XCTAssertEqual(RecorderOnset.lateStart(recorderFrom: minutes(45), bedAt: bedAt, foreground: false), .background)
        XCTAssertEqual(RecorderOnset.lateStart(recorderFrom: bedAt.addingTimeInterval(RecorderOnset.lateStartTolerance), bedAt: bedAt,
                                               foreground: false), .onTime)
    }

    // MARK: F-6

    /// Am Ladegerät nicht wegen des Akkus auslassen (Einschlaf-Messung und Lernphase).
    func testChargingIgnoresTheBatteryLimit() {
        let target = minutes(8 * 60)
        let window = target.addingTimeInterval(-1800)
        XCTAssertEqual(AppleAutoOnset.decide(now: since, target: target, windowStart: window, onsetStart: since, battery: 25),
                       .skip(reason: "battery 25%"))
        XCTAssertEqual(AppleAutoOnset.decide(now: since, target: target, windowStart: window, onsetStart: since, battery: 25, charging: true),
                       .start(until: window))
        XCTAssertEqual(AppleLearnRecord.decide(now: since, target: target, onsetStart: since, battery: 35), .skip(reason: "battery 35%"))
        if case .start = AppleLearnRecord.decide(now: since, target: target, onsetStart: since, battery: 35, charging: true) {} else {
            XCTFail("am Ladegerät zeichnet die Lernphase auf")
        }
    }
}
