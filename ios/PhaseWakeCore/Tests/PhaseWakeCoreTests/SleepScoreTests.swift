import XCTest
@testable import PhaseWakeCore

/// Schlafwert: Punkte je Faktor, Gewichtung, fehlende Faktoren, vorläufiger Wert, Bewertung in Worten
/// (PHASENERKENNUNG.md 11). Gleiche Fälle wie `SleepScoreTest` (Kotlin).
final class SleepScoreTests: XCTestCase {
    /// 2026-11-08T22:00:00Z.
    private let t0 = Date(timeIntervalSince1970: 1_794_175_200)

    private func at(_ minute: Int) -> Date { t0.addingTimeInterval(Double(minute * 60)) }

    func testBaseInstant() {
        XCTAssertEqual(ISO8601DateFormatter().string(from: t0), "2026-11-08T22:00:00Z")
    }

    func testWeightsAddUpToHundred() {
        XCTAssertEqual(SleepScore.weights.values.reduce(0, +), 100)
        XCTAssertEqual(Set(SleepScore.weights.keys), Set(ScoreFactor.allCases))
    }

    func testBandsHaveTheDocumentedLimits() {
        XCTAssertEqual(SleepScore.band(100), .excellent)
        XCTAssertEqual(SleepScore.band(85), .excellent)
        XCTAssertEqual(SleepScore.band(84), .good)
        XCTAssertEqual(SleepScore.band(70), .good)
        XCTAssertEqual(SleepScore.band(69), .fair)
        XCTAssertEqual(SleepScore.band(50), .fair)
        XCTAssertEqual(SleepScore.band(49), .poor)
        XCTAssertEqual(SleepScore.band(0), .poor)
    }

    func testDurationIsFullFromTheGoalAndZeroAtHalf() {
        XCTAssertEqual(SleepScore.durationPoints(450, goalMinutes: 450), 100)
        XCTAssertEqual(SleepScore.durationPoints(600, goalMinutes: 450), 100)
        XCTAssertEqual(SleepScore.durationPoints(225, goalMinutes: 450), 0)
        XCTAssertEqual(SleepScore.durationPoints(10, goalMinutes: 450), 0)
        // 405 von 450: (405 − 225) / 225 = 80 %.
        XCTAssertEqual(SleepScore.durationPoints(405, goalMinutes: 450), 80)
        // 337.5 wäre genau 50: 338 → 50.2 → 50.
        XCTAssertEqual(SleepScore.durationPoints(338, goalMinutes: 450), 50)
    }

    func testDeepAndRemFromTheLowerTypicalLimit() {
        // Tief üblich ab 13 %, REM ab 20 %.
        XCTAssertEqual(SleepScore.atLeastPoints(13, low: 13), 100)
        XCTAssertEqual(SleepScore.atLeastPoints(30, low: 13), 100)
        XCTAssertEqual(SleepScore.atLeastPoints(10, low: 13), 77)
        XCTAssertEqual(SleepScore.atLeastPoints(10, low: 20), 50)
        XCTAssertEqual(SleepScore.atLeastPoints(0, low: 20), 0)
    }

    func testAwakeIsFullUpToNinePercentAndZeroFromThirty() {
        XCTAssertEqual(SleepScore.awakePoints(0), 100)
        XCTAssertEqual(SleepScore.awakePoints(9), 100)
        // (30 − 15) / 21 = 71.4 %.
        XCTAssertEqual(SleepScore.awakePoints(15), 71)
        XCTAssertEqual(SleepScore.awakePoints(30), 0)
        XCTAssertEqual(SleepScore.awakePoints(70), 0)
    }

    func testLatencyIsFullUpToTwentyMinutesAndZeroFromSixty() {
        XCTAssertEqual(SleepScore.latencyPoints(0), 100)
        XCTAssertEqual(SleepScore.latencyPoints(20), 100)
        XCTAssertEqual(SleepScore.latencyPoints(40), 50)
        XCTAssertEqual(SleepScore.latencyPoints(60), 0)
        XCTAssertEqual(SleepScore.latencyPoints(90), 0)
    }

    func testAllFactorsAreWeighted() {
        let r = SleepScore.of(ScoreInput(asleepMinutes: 405, goalMinutes: 450, deepPercent: 10, remPercent: 20, awakePercent: 15, latencyMinutes: 40))!
        XCTAssertEqual(r.factors.map(\.points), [80, 77, 100, 71, 50])
        // (80·35 + 77·20 + 100·20 + 71·15 + 50·10) / 100 = 79.05.
        XCTAssertEqual(r.score, 79)
        XCTAssertEqual(r.band, .good)
        XCTAssertEqual(r.factor(.latency)?.band, .fair)
    }

    func testMissingFactorsAreLeftOutAndTheRestReweighted() {
        // Nur Schlafdauer und Einschlafdauer: (80·35 + 50·10) / 45 = 73.3.
        let r = SleepScore.of(ScoreInput(asleepMinutes: 405, goalMinutes: 450, latencyMinutes: 40))!
        XCTAssertEqual(r.factors.map(\.factor), [.duration, .latency])
        XCTAssertEqual(r.score, 73)
        XCTAssertNil(r.factor(.deep))
        // Nur Schlafdauer: der Wert ist ihre Punktzahl.
        XCTAssertEqual(SleepScore.of(ScoreInput(asleepMinutes: 405, goalMinutes: 450))?.score, 80)
    }

    func testWithoutStagesTheScoreIsProvisionalAndAtMostGood() {
        // Ziel erreicht, schnell eingeschlafen: gerechnet 100, ohne Phasen höchstens 84 («Gut»).
        let r = SleepScore.of(ScoreInput(asleepMinutes: 480, goalMinutes: 450, latencyMinutes: 12))!
        XCTAssertEqual(r.score, 84)
        XCTAssertEqual(r.band, .good)
        XCTAssertTrue(r.provisional)
        XCTAssertEqual(r.factors.map(\.points), [100, 100])
        // Unter der Grenze bleibt der Wert, ist aber vorläufig.
        let low = SleepScore.of(ScoreInput(asleepMinutes: 405, goalMinutes: 450, latencyMinutes: 40))!
        XCTAssertEqual(low.score, 73)
        XCTAssertTrue(low.provisional)
        // Eine Phase genügt: nicht vorläufig, keine Grenze.
        let awake = SleepScore.of(ScoreInput(asleepMinutes: 480, goalMinutes: 450, awakePercent: 4))!
        XCTAssertEqual(awake.score, 100)
        XCTAssertFalse(awake.provisional)
    }

    func testNoScoreWithoutDuration() {
        XCTAssertNil(SleepScore.of(ScoreInput(asleepMinutes: nil, goalMinutes: 450, deepPercent: 20, remPercent: 22, awakePercent: 3)))
        XCTAssertNil(SleepScore.of(ScoreInput(asleepMinutes: 400, goalMinutes: 0)))
    }

    func testPerfectNightIsHundred() {
        let r = SleepScore.of(ScoreInput(asleepMinutes: 480, goalMinutes: 450, deepPercent: 18, remPercent: 22, awakePercent: 4, latencyMinutes: 12))!
        XCTAssertEqual(r.score, 100)
        XCTAssertEqual(r.band, .excellent)
        XCTAssertFalse(r.provisional)
    }

    func testInputFromStages() {
        // 10 Min wach, 50 leicht, 30 tief, 20 REM, dann 10 wach: 120 Min, Schlaf 100 Min.
        let spans = [
            StageSpan(start: at(0), end: at(10), stage: .awake),
            StageSpan(start: at(10), end: at(60), stage: .light),
            StageSpan(start: at(60), end: at(90), stage: .deep),
            StageSpan(start: at(90), end: at(110), stage: .rem),
            StageSpan(start: at(110), end: at(120), stage: .awake),
        ]
        let stats = StageStats.of(spans, from: at(0), to: at(120))!
        XCTAssertEqual(SleepScore.inputOf(stats, goalMinutes: 450),
                       ScoreInput(asleepMinutes: 100, goalMinutes: 450, deepPercent: 25, remPercent: 17, awakePercent: 17, latencyMinutes: 10))
    }

    func testInputFromRecording() {
        XCTAssertEqual(SleepScore.inputOf(bedAt: at(0), asleepAt: at(15), end: at(415), goalMinutes: 450),
                       ScoreInput(asleepMinutes: 400, goalMinutes: 450, latencyMinutes: 15))
        // Beginn nach dem Einschlafen: keine Einschlafdauer; ohne Beginn ebenso.
        XCTAssertEqual(SleepScore.inputOf(bedAt: at(20), asleepAt: at(15), end: at(415), goalMinutes: 450),
                       ScoreInput(asleepMinutes: 400, goalMinutes: 450))
        XCTAssertEqual(SleepScore.inputOf(bedAt: nil, asleepAt: at(15), end: at(415), goalMinutes: 450),
                       ScoreInput(asleepMinutes: 400, goalMinutes: 450))
        XCTAssertNil(SleepScore.inputOf(bedAt: nil, asleepAt: at(15), end: at(15), goalMinutes: 450))
    }
}
