import XCTest
@testable import PhaseWakeCore

/// Welcher Plan gilt (echter Wecker oder vorübergehender) und wann ein Probewecker/Powernap abgelehnt wird.
/// Gegenstück zu `TransientPlanningTest` (Kotlin).
final class TransientPlanningTests: XCTestCase {
    private var zurich: Calendar {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "Europe/Zurich")!
        return c
    }

    /// Donnerstag, 29. Oktober 2026, 05:45 in Zürich (04:45 UTC).
    private let now = ISO8601DateFormatter().date(from: "2026-10-29T04:45:00Z")!
    private let alarm = try! AlarmSpec(id: "a", hour: 7, minute: 0, days: [.thursday], windowMinutes: 30)
    private var real: WakePlan { AlarmSchedule.nextPlan(alarm, now: now, calendar: zurich)! }

    func testNapNeverDisplacesEarlierAlarm() {
        // Nap von 90 Min bis 7:15, Wecker 7:00: der Wecker gilt.
        let nap = Transient.nap(base: nil, now: now, calendar: zurich, minutes: 90, uuid: "1")
        XCTAssertEqual(Transient.choose(real: real, transient: nap, now: now), real)
        let short = Transient.nap(base: nil, now: now, calendar: zurich, minutes: 20, uuid: "2")
        XCTAssertEqual(Transient.choose(real: real, transient: short, now: now), short.plan)
        XCTAssertEqual(Transient.choose(real: nil, transient: short, now: now), short.plan)
        XCTAssertEqual(Transient.choose(real: real, transient: nil, now: now), real)
        XCTAssertNil(Transient.choose(real: nil, transient: nil, now: now))
    }

    func testStaleTransientIsNotPlannedAgain() {
        // Uhr war aus und kommt 1,5 h nach dem Ende des Naps zurück: nicht mehr klingeln.
        let nap = Transient.nap(base: nil, now: now, calendar: zurich, minutes: 20, uuid: "1")
        let target = nap.plan.target
        XCTAssertTrue(Transient.isPlannable(nap, now: target))
        XCTAssertTrue(Transient.isPlannable(nap, now: target.addingTimeInterval(Transient.planAfterTarget - 0.001)))
        XCTAssertFalse(Transient.isPlannable(nap, now: target.addingTimeInterval(Transient.planAfterTarget)))
        XCTAssertNil(Transient.choose(real: nil, transient: nap, now: target.addingTimeInterval(90 * 60)))
        // Die Weckart gilt dagegen noch (Klingeln, Stopp).
        XCTAssertTrue(Transient.isActive(nap, now: target.addingTimeInterval(90 * 60)))
    }

    func testConflictWhileAlarmRunsOrBeforeWindow() {
        let test = Transient.test(base: nil, now: now, calendar: zurich, uuid: "1")
        XCTAssertNil(Transient.conflict(test, next: real, nightActive: false, now: now))
        XCTAssertNil(Transient.conflict(test, next: nil, nightActive: false, now: now))
        XCTAssertEqual(Transient.conflict(test, next: nil, nightActive: true, now: now), .alarmRunning)
        // Im Fenster 6:30–7:00.
        let inWindow = real.windowStart.addingTimeInterval(600)
        let later = Transient.test(base: nil, now: inWindow, calendar: zurich, uuid: "2")
        XCTAssertEqual(Transient.conflict(later, next: real, nightActive: false, now: inWindow), .alarmRunning)
        XCTAssertEqual(Transient.conflict(later, next: real, nightActive: false, now: real.windowStart), .alarmRunning)
        // Nap bis 7:15 überdeckt das Fenster ab 6:30.
        let nap = Transient.nap(base: nil, now: now, calendar: zurich, minutes: 90, uuid: "3")
        XCTAssertEqual(Transient.conflict(nap, next: real, nightActive: false, now: now), .alarmSoon)
        // Nap genau bis zum Fensterbeginn: erlaubt.
        let start = real.windowStart.addingTimeInterval(-45 * 60)
        let fits = Transient.nap(base: nil, now: start, calendar: zurich, minutes: 45, uuid: "4")
        XCTAssertNil(Transient.conflict(fits, next: real, nightActive: false, now: start))
        // Nach der Zielzeit (Nacht erledigt, nächster Plan ist morgen): erlaubt.
        let after = Transient.test(base: nil, now: real.target, calendar: zurich, uuid: "5")
        XCTAssertNil(Transient.conflict(after, next: AlarmSchedule.nextPlan(alarm, now: real.target, calendar: zurich),
                                        nightActive: false, now: real.target))
    }
}
