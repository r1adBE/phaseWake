import XCTest
import PhaseWakeCore

/// Untergrenze für kurze Nächte und Plan bei «Genau»; Gegenstück: `ShortNightTest` (Kotlin).
final class ShortNightTests: XCTestCase {

    private var zurich: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "Europe/Zurich")!
        return c
    }()

    private func at(_ d: Int, _ h: Int, _ mi: Int) -> Date {
        zurich.date(from: DateComponents(year: 2026, month: 10, day: d, hour: h, minute: mi))!
    }

    func testUnknownOnsetNeverBlocks() {
        XCTAssertFalse(ShortNight.blocksEarly(onset: nil, now: at(29, 6, 40), sleepGoalMinutes: SleepGoal.defaultMinutes))
    }

    func testBlocksBelowGoalMinusOneHour() {
        let onset = at(29, 0, 30)
        // Ziel 7½ h: Grenze 6½ h nach dem Einschlafen, also 07:00.
        XCTAssertTrue(ShortNight.blocksEarly(onset: onset, now: at(29, 6, 40), sleepGoalMinutes: 450))
        XCTAssertTrue(ShortNight.blocksEarly(onset: onset, now: at(29, 6, 59), sleepGoalMinutes: 450))
        XCTAssertFalse(ShortNight.blocksEarly(onset: onset, now: at(29, 7, 0), sleepGoalMinutes: 450))
        XCTAssertFalse(ShortNight.blocksEarly(onset: onset, now: at(29, 7, 10), sleepGoalMinutes: 450))
        // Ziel 6 h: Grenze 5 h.
        XCTAssertFalse(ShortNight.blocksEarly(onset: onset, now: at(29, 5, 30), sleepGoalMinutes: 360))
    }

    func testLastTenMinutesBeforeTheTargetAreNotBlocked() {
        // Fall r1AD 10.11.2026: eingeschlafen 01:51, Zielzeit 07:57, Ziel 7½ h (Grenze 08:21).
        let nov: (Int, Int) -> Date = { h, mi in
            self.zurich.date(from: DateComponents(year: 2026, month: 11, day: 10, hour: h, minute: mi))!
        }
        let onset = nov(1, 51)
        let target = nov(7, 57)
        XCTAssertEqual(ShortNight.lateMinutes, 10)
        XCTAssertTrue(ShortNight.blocksEarly(onset: onset, now: nov(7, 32), sleepGoalMinutes: 450, target: target))
        XCTAssertTrue(ShortNight.blocksEarly(onset: onset, now: nov(7, 46), sleepGoalMinutes: 450, target: target))
        XCTAssertFalse(ShortNight.blocksEarly(onset: onset, now: nov(7, 47), sleepGoalMinutes: 450, target: target))
        XCTAssertFalse(ShortNight.blocksEarly(onset: onset, now: nov(7, 55), sleepGoalMinutes: 450, target: target))
        // Ohne Zielzeit wie bisher bis zum Schluss gesperrt.
        XCTAssertTrue(ShortNight.blocksEarly(onset: onset, now: nov(7, 50), sleepGoalMinutes: 450))
        XCTAssertFalse(ShortNight.blocksEarly(onset: nil, now: nov(7, 32), sleepGoalMinutes: 450, target: target))
    }

    func testMeasuredButUnknownOnsetBlocksUntilTheLastTenMinutes() {
        // Fail-closed (Entscheid r1AD 10.11.2026): gemessen, aber ohne Einschlafzeit (aufgegeben, gescheitert, ohne Werte).
        let nov: (Int, Int, Int) -> Date = { d, h, mi in
            self.zurich.date(from: DateComponents(year: 2026, month: 11, day: d, hour: h, minute: mi))!
        }
        let target = nov(10, 7, 0)
        XCTAssertTrue(ShortNight.blocksEarly(onset: nil, now: nov(10, 6, 35), sleepGoalMinutes: 450, target: target, measured: true))
        XCTAssertTrue(ShortNight.blocksEarly(onset: nil, now: nov(10, 6, 49), sleepGoalMinutes: 450, target: target, measured: true))
        XCTAssertFalse(ShortNight.blocksEarly(onset: nil, now: nov(10, 6, 50), sleepGoalMinutes: 450, target: target, measured: true))
        // Ohne Zielzeit bis zum Schluss gesperrt; ohne Messung nie.
        XCTAssertTrue(ShortNight.blocksEarly(onset: nil, now: nov(10, 6, 55), sleepGoalMinutes: 450, measured: true))
        XCTAssertFalse(ShortNight.blocksEarly(onset: nil, now: nov(10, 6, 35), sleepGoalMinutes: 450, target: target, measured: false))
        // Eine bekannte Einschlafzeit geht vor: lange Nacht, nicht gesperrt.
        XCTAssertFalse(ShortNight.blocksEarly(onset: nov(9, 22, 30), now: nov(10, 6, 35), sleepGoalMinutes: 450, target: target, measured: true))
    }

    func testAutoOnsetStartsOneHourBeforeBedtime() {
        let target = at(29, 7, 0)
        // Bettzeit 23:30 (Ziel 7½ h), Messung ab 22:30.
        XCTAssertEqual(ShortNight.autoOnsetStart(target: target, sleepGoalMinutes: 450, sleepDurationMinutes: nil), at(28, 22, 30))
        // Einschlaf-Modus: Schlafdauer statt Ziel.
        XCTAssertEqual(ShortNight.autoOnsetStart(target: target, sleepGoalMinutes: 450, sleepDurationMinutes: 360), at(29, 0, 0))
    }

    func testExactPlanMeasuresThirtyMinutesButIsFlagged() throws {
        let exact = try AlarmSpec(id: "a", hour: 7, minute: 0, windowMinutes: AlarmSpec.exactWindow)
        let plan = try XCTUnwrap(AlarmSchedule.nextPlan(exact, now: at(29, 1, 0), calendar: zurich))
        XCTAssertEqual(plan.target, at(29, 7, 0))
        XCTAssertEqual(plan.windowStart, at(29, 6, 30))
        XCTAssertTrue(plan.exact)
        let twenty = try AlarmSpec(id: "a", hour: 7, minute: 0, windowMinutes: 20)
        let normal = try XCTUnwrap(AlarmSchedule.nextPlan(twenty, now: at(29, 1, 0), calendar: zurich))
        XCTAssertEqual(normal.windowStart, at(29, 6, 40))
        XCTAssertFalse(normal.exact)
        XCTAssertEqual(AlarmSchedule.plan(twenty, target: at(29, 7, 0)), normal)
    }

    func testNewReasonsCountAsTarget() {
        XCTAssertTrue(WakeReason.exact.atTarget)
        XCTAssertTrue(WakeReason.shortNight.atTarget)
        XCTAssertTrue(WakeReason.deadline.atTarget)
        XCTAssertFalse(WakeReason.lightSleep.atTarget)
        XCTAssertFalse(WakeReason.awake.atTarget)
    }
}
