import XCTest
@testable import PhaseWakeCore

/// Phasen-Summen, Anteile, Einordnung und Kennzahlen. Gleiche Fälle wie `StageStatsTest` (Kotlin).
final class StageStatsTests: XCTestCase {
    /// 2026-10-27T22:00:00Z.
    private let t0 = Date(timeIntervalSince1970: 1_793_138_400)

    private func at(_ minute: Int) -> Date { t0.addingTimeInterval(Double(minute * 60)) }

    private func span(_ from: Int, _ to: Int, _ stage: SleepStage) -> StageSpan { StageSpan(start: at(from), end: at(to), stage: stage) }

    /// Bereich von `from` bis `to` (ohne Angabe: bis zum spätesten Ende).
    private func of(_ spans: StageSpan..., from: Int = 0, to: Int? = nil) -> NightStages? {
        let end = to.map(at) ?? spans.map(\.end).max() ?? at(from)
        return StageStats.of(spans, from: at(from), to: end)
    }

    private func percents(_ s: NightStages) -> [Int] { s.totals.map(\.percent) }

    func testBaseInstant() {
        XCTAssertEqual(ISO8601DateFormatter().string(from: t0), "2026-10-27T22:00:00Z")
    }

    func testEmptyOrInvalidIsNil() {
        XCTAssertNil(StageStats.of([], from: at(0), to: at(60)))
        XCTAssertNil(StageStats.of([span(0, 30, .light)], from: at(0), to: at(0)))
        XCTAssertNil(of(span(0, 60, .unknown)))
    }

    func testMergesTouchingBlocksOfTheSameStage() {
        let s = of(span(0, 30, .light), span(30, 50, .light), span(50, 80, .deep))!
        XCTAssertEqual(s.blocks, [StageBlock(stage: .light, start: at(0), end: at(50)), StageBlock(stage: .deep, start: at(50), end: at(80))])
        let gap = of(span(0, 30, .light), span(31, 40, .light))!
        XCTAssertEqual(gap.blocks.count, 2)
    }

    func testPercentsAddUpToHundred() {
        let s = of(span(0, 10, .awake), span(10, 20, .rem), span(20, 30, .light))!
        XCTAssertEqual(percents(s), [34, 33, 33, 0])
        XCTAssertNil(s.verdict)
    }

    func testTypicalNight() {
        let s = of(span(0, 30, .awake), span(30, 150, .light), span(150, 240, .deep),
                   span(240, 300, .rem), span(300, 420, .light), span(420, 480, .rem))!
        XCTAssertEqual(s.totals.map(\.minutes), [30, 120, 240, 90])
        XCTAssertEqual(percents(s), [6, 25, 50, 19])
        XCTAssertEqual(s.totals.map(\.rating), Array(repeating: StageRating.typical, count: 4))
        XCTAssertEqual(s.verdict, .typical)
        XCTAssertEqual(s.latencyMinutes, 30)
        XCTAssertEqual(s.awakenings, 0)
        XCTAssertEqual(s.longestDeepMinutes, 90)
        XCTAssertEqual(s.asleepMinutes, 450)
        XCTAssertEqual(s.efficiencyPercent, 94)
        XCTAssertEqual(s.total(.rem)?.range, StageRange(low: 20, high: 25))
        XCTAssertNil(s.total(.unknown))
    }

    func testCountsAwakeningsOnlyBetweenSleep() {
        let s = of(span(0, 10, .awake), span(10, 100, .light), span(100, 105, .awake),
                   span(105, 200, .deep), span(200, 210, .awake), span(210, 260, .rem),
                   span(260, 270, .awake))!
        XCTAssertEqual(s.awakenings, 2)
        XCTAssertEqual(s.latencyMinutes, 10)
    }

    func testVerdictFollowsTheFixedOrder() {
        let deepAbove = of(span(0, 200, .light), span(200, 320, .deep), span(320, 400, .rem))!
        XCTAssertEqual(percents(deepAbove), [0, 20, 50, 30])
        XCTAssertEqual(deepAbove.verdict, .deepAbove)
        let awakeAndLowDeep = of(span(0, 100, .awake), span(100, 400, .light), span(400, 420, .deep))!
        XCTAssertEqual(awakeAndLowDeep.verdict, .awakeAbove)
        let lowRem = of(span(0, 260, .light), span(260, 340, .deep), span(340, 380, .rem), span(380, 400, .awake))!
        XCTAssertEqual(percents(lowRem), [5, 10, 65, 20])
        XCTAssertEqual(lowRem.verdict, .remBelow)
    }

    func testTooLittleDataHasNoVerdict() {
        let s = of(span(0, 100, .light))!
        XCTAssertNil(s.verdict)
        XCTAssertEqual(percents(s), [0, 0, 100, 0])
    }

    func testEfficiencyCountsUnknownAsSleepAndGapsAsRange() {
        let s = of(span(0, 40, .light), span(40, 60, .unknown), span(80, 100, .awake), to: 100)!
        XCTAssertEqual(s.efficiencyPercent, 60)
        XCTAssertEqual(s.asleepMinutes, 60)
        XCTAssertNil(s.latencyMinutes)
        XCTAssertEqual(s.awakenings, 0)
    }

    func testClipsToTheRange() {
        let s = StageStats.of([span(-30, 30, .deep)], from: at(0), to: at(60))!
        XCTAssertEqual(s.total(.deep)?.minutes, 30)
        XCTAssertEqual(s.blocks, [StageBlock(stage: .deep, start: at(0), end: at(30))])
    }

    func testBlockAtFindsTheBlock() {
        let s = of(span(0, 30, .light), span(30, 50, .light), span(50, 80, .deep))!
        XCTAssertEqual(s.blockAt(at(49))?.stage, .light)
        XCTAssertEqual(s.blockAt(at(50))?.stage, .deep)
        XCTAssertNil(s.blockAt(at(80)))
    }

    func testForNightUsesTheHypnogramRange() {
        let night = NightRecord(alarmId: "a", windowStart: at(420), target: at(450), wokeAt: at(440), reason: .lightSleep, snoozes: 0, epochs: [])
        let stages = [span(0, 200, .light), span(200, 300, .deep), span(300, 445, .rem)]
        let s = StageStats.forNight(stages, night: night)!
        XCTAssertEqual(s.start, at(0))
        XCTAssertEqual(s.end, at(445))
        XCTAssertNil(StageStats.forNight([], night: night))
    }

    func testForSessionUsesTheWholeSession() {
        let session = HealthSession(start: at(0), end: at(100), stages: [span(10, 90, .light)])
        let s = StageStats.forSession(session)!
        XCTAssertEqual(s.efficiencyPercent, 80)
        XCTAssertNil(StageStats.forSession(HealthSession(start: at(0), end: at(100))))
    }

    func testWeekAverageIsTrailing() {
        XCTAssertEqual(ChartData.weekAverage([1, nil, 3, nil, nil, nil, nil, nil, nil]), [1, 1, 2, 2, 2, 2, 2, 3, 3])
        XCTAssertEqual(ChartData.weekAverage([nil]), [nil])
        XCTAssertEqual(ChartData.periods, [7, 14, 30])
    }

    func testHistoryKeysAndLookup() {
        let night = NightRecord(alarmId: "a", windowStart: Date(timeIntervalSince1970: 1), target: Date(timeIntervalSince1970: 2),
                                wokeAt: Date(timeIntervalSince1970: 1.5), reason: .awake, snoozes: 0, epochs: [])
        let date = "2026-10-26"
        let own = HistoryItem(kind: .phaseWake, date: date, night: night)
        let health = HistoryItem(kind: .health, date: date, session: HealthSession(start: at(0), end: at(60)))
        XCTAssertEqual(own.key, "n:a:1000")
        XCTAssertEqual(health.key, "h:2026-10-26")
        XCTAssertEqual(HealthHistory.itemFor([own, health], date: date), own)
        XCTAssertNil(HealthHistory.itemFor([own], date: NightDate.adding(1, to: date)))
    }
}
