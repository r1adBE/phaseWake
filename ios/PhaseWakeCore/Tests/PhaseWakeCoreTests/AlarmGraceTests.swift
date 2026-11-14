import XCTest
import PhaseWakeCore

/// Nachfrist (`AlarmSchedule.inGrace`) und Nachholen (`AlarmSchedule.missedPlan`); gleiche Fälle wie `AlarmGraceTest` (Kotlin).
final class AlarmGraceTests: XCTestCase {

    private var zurich: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "Europe/Zurich")!
        return c
    }()

    private let grace: TimeInterval = 120

    private func at(_ y: Int, _ mo: Int, _ d: Int, _ h: Int, _ mi: Int) -> Date {
        zurich.date(from: DateComponents(year: y, month: mo, day: d, hour: h, minute: mi))!
    }

    private func alarm(hour: Int = 7, minute: Int = 0, days: Set<Weekday> = [], id: String = "a") -> AlarmSpec {
        try! AlarmSpec(id: id, hour: hour, minute: minute, days: days)
    }

    func testGraceKeepsPlannedBackup() {
        let plan = AlarmSchedule.inGrace(alarm(), plannedTarget: at(2026, 10, 28, 7, 0), now: at(2026, 10, 28, 7, 1), grace: grace,
                                         calendar: zurich)
        XCTAssertEqual(plan?.target, at(2026, 10, 28, 7, 0))
        XCTAssertEqual(plan?.windowStart, at(2026, 10, 28, 6, 40))
        XCTAssertEqual(plan?.alarmId, "a")
        // Genau zur Zielzeit zählt schon, das Ende der Nachfrist nicht mehr.
        XCTAssertEqual(AlarmSchedule.inGrace(alarm(), plannedTarget: at(2026, 10, 28, 7, 0), now: at(2026, 10, 28, 7, 0), grace: grace,
                                             calendar: zurich), plan)
        XCTAssertNil(AlarmSchedule.inGrace(alarm(), plannedTarget: at(2026, 10, 28, 7, 0), now: at(2026, 10, 28, 7, 2), grace: grace,
                                           calendar: zurich))
    }

    func testGraceOnlyAfterTarget() {
        XCTAssertNil(AlarmSchedule.inGrace(alarm(), plannedTarget: at(2026, 10, 28, 7, 0), now: at(2026, 10, 28, 6, 59), grace: grace,
                                           calendar: zurich))
        XCTAssertNil(AlarmSchedule.inGrace(alarm(), plannedTarget: nil, now: at(2026, 10, 28, 7, 1), grace: grace, calendar: zurich))
        // Ohne Uhr (Nachfrist 0) klingelt das Handy pünktlich; es gibt keine Nachfrist zu behalten.
        XCTAssertNil(AlarmSchedule.inGrace(alarm(), plannedTarget: at(2026, 10, 28, 7, 0), now: at(2026, 10, 28, 7, 0), grace: 0,
                                           calendar: zurich))
    }

    func testGraceDropsInvalidOccurrence() {
        let planned = at(2026, 10, 28, 7, 0)
        let now = at(2026, 10, 28, 7, 1)
        XCTAssertNil(AlarmSchedule.inGrace(alarm(minute: 30), plannedTarget: planned, now: now, grace: grace, calendar: zurich), "Zeit geändert")
        XCTAssertNil(AlarmSchedule.inGrace(alarm().with(enabled: false), plannedTarget: planned, now: now, grace: grace, calendar: zurich),
                     "ausgeschaltet")
        let daily = alarm(days: Set(Weekday.allCases))
        XCTAssertNotNil(AlarmSchedule.inGrace(daily, plannedTarget: planned, now: now, grace: grace, calendar: zurich))
        let skipped = daily.with(skipUntil: AlarmSchedule.wallMillis(planned, calendar: zurich))
        XCTAssertNil(AlarmSchedule.inGrace(skipped, plannedTarget: planned, now: now, grace: grace, calendar: zurich), "übersprungen")
        XCTAssertNil(AlarmSchedule.inGrace(daily.with(skipHolidays: true), plannedTarget: planned, now: now, grace: grace, calendar: zurich,
                                           holidays: ["2026-10-28"]), "Feiertag")
        // Mittwoch 28.10.: ein Wecker nur am Donnerstag hatte heute keinen Termin.
        XCTAssertNil(AlarmSchedule.inGrace(alarm(days: [.thursday]), plannedTarget: planned, now: now, grace: grace, calendar: zurich),
                     "kein Termin")
    }

    func testGraceRespectsSuppression() {
        let planned = at(2026, 10, 28, 7, 0)
        let now = at(2026, 10, 28, 7, 1)
        XCTAssertNil(AlarmSchedule.inGrace(alarm(), plannedTarget: planned, now: now, grace: grace, calendar: zurich,
                                           suppressedUntil: planned.addingTimeInterval(grace)))
        XCTAssertNil(AlarmSchedule.inGrace(alarm(), plannedTarget: planned, now: now, grace: grace, calendar: zurich, suppressedUntil: planned))
        XCTAssertNotNil(AlarmSchedule.inGrace(alarm(), plannedTarget: planned, now: now, grace: grace, calendar: zurich,
                                              suppressedUntil: at(2026, 10, 27, 7, 2)))
    }

    /// Wecker a 06:40 führt die Nacht (Fenster ab 06:20, beendet 07:10); b 07:00 wurde dabei fällig.
    private var a: AlarmSpec { alarm(hour: 6, minute: 40, id: "a") }
    private var b: AlarmSpec { alarm(id: "b") }
    private var window: CatchUpWindow {
        AlarmSchedule.catchUpAfter(nil, windowStart: at(2026, 10, 28, 6, 20), until: at(2026, 10, 28, 7, 10), specs: [a, b], calendar: zurich)
    }

    func testCatchUpCollectsDueAlarms() {
        XCTAssertEqual(window, CatchUpWindow(from: at(2026, 10, 28, 6, 20), until: at(2026, 10, 28, 7, 10), alarmIds: ["a", "b"]))
        let later = alarm(hour: 7, minute: 30, id: "c")
        XCTAssertEqual(AlarmSchedule.catchUpAfter(nil, windowStart: at(2026, 10, 28, 6, 20), until: at(2026, 10, 28, 6, 50),
                                                  specs: [a, b, later], calendar: zurich).alarmIds, ["a"])
    }

    func testMissedAlarmRingsRightAfter() {
        let done = ["a": at(2026, 10, 28, 6, 40)]
        let plan = AlarmSchedule.missedPlan([a, b], catchUp: window, now: at(2026, 10, 28, 7, 10), doneUntil: done, calendar: zurich)
        XCTAssertEqual(plan?.alarmId, "b")
        XCTAssertEqual(plan?.target, at(2026, 10, 28, 7, 0))
        // Gleich danach erledigt: nichts mehr nachzuholen.
        var both = done
        both["b"] = at(2026, 10, 28, 7, 0)
        XCTAssertNil(AlarmSchedule.missedPlan([a, b], catchUp: window, now: at(2026, 10, 28, 7, 11), doneUntil: both, calendar: zurich))
    }

    func testMissedOnlyWhileValidAndKnown() {
        let done = ["a": at(2026, 10, 28, 6, 40)]
        XCTAssertNotNil(AlarmSchedule.missedPlan([a, b], catchUp: window, now: at(2026, 10, 28, 7, 20), doneUntil: done, calendar: zurich))
        XCTAssertNil(AlarmSchedule.missedPlan([a, b], catchUp: window, now: at(2026, 10, 28, 7, 21), doneUntil: done, calendar: zurich),
                     "zu spät")
        XCTAssertFalse(window.isValid(now: at(2026, 10, 28, 7, 21)))
        XCTAssertNil(AlarmSchedule.missedPlan([a, b], catchUp: nil, now: at(2026, 10, 28, 7, 10), calendar: zurich))
        // Erst nach dem Ende der Nacht angelegt: klingelt nicht nachträglich.
        let fresh = alarm(hour: 6, minute: 50, id: "d")
        XCTAssertNil(AlarmSchedule.missedPlan([a, fresh], catchUp: window, now: at(2026, 10, 28, 7, 10), doneUntil: done, calendar: zurich))
        // Zielzeit nach dem Ende der Nacht: gewohnter Plan, kein Nachholen.
        let narrow = AlarmSchedule.catchUpAfter(nil, windowStart: at(2026, 10, 28, 6, 20), until: at(2026, 10, 28, 6, 50), specs: [a, b],
                                                calendar: zurich)
        XCTAssertNil(AlarmSchedule.missedPlan([a, b], catchUp: narrow, now: at(2026, 10, 28, 6, 55), doneUntil: done, calendar: zurich))
    }

    func testMissedNeedsTargetWithinNightAndNotAhead() {
        // Audit BE-4: Zielzeit genau am Ende der Nacht und genau jetzt: wird nachgeholt (beide Grenzen eingeschlossen).
        let edge = CatchUpWindow(from: at(2026, 10, 28, 6, 20), until: at(2026, 10, 28, 7, 0), alarmIds: ["b"])
        XCTAssertEqual(AlarmSchedule.missedPlan([b], catchUp: edge, now: at(2026, 10, 28, 7, 0), calendar: zurich)?.target, at(2026, 10, 28, 7, 0))
        // Nach dem Ende der Nacht fällig, auch wenn schon vorbei: kein Nachholen (gewohnter Plan).
        let early = CatchUpWindow(from: at(2026, 10, 28, 6, 20), until: at(2026, 10, 28, 6, 59), alarmIds: ["b"])
        XCTAssertNil(AlarmSchedule.missedPlan([b], catchUp: early, now: at(2026, 10, 28, 7, 0), calendar: zurich))
        // In der Nacht, aber noch nicht fällig: kein Nachholen.
        let long = CatchUpWindow(from: at(2026, 10, 28, 6, 20), until: at(2026, 10, 28, 7, 10), alarmIds: ["b"])
        XCTAssertNil(AlarmSchedule.missedPlan([b], catchUp: long, now: at(2026, 10, 28, 6, 59), calendar: zurich))
        // Zielzeit genau am Ende der Nacht zählt als fällig geworden.
        XCTAssertEqual(AlarmSchedule.catchUpAfter(nil, windowStart: at(2026, 10, 28, 6, 20), until: at(2026, 10, 28, 7, 0), specs: [a, b],
                                                  calendar: zurich).alarmIds, ["a", "b"])
        XCTAssertEqual(AlarmSchedule.catchUpAfter(nil, windowStart: at(2026, 10, 28, 6, 20), until: at(2026, 10, 28, 6, 59), specs: [a, b],
                                                  calendar: zurich).alarmIds, ["a"])
        XCTAssertEqual(CatchUpWindow.valid, 10 * 60)
    }

    func testCatchUpExtendsAdjoiningNight() {
        let c = alarm(hour: 7, minute: 15, id: "c")
        let next = AlarmSchedule.catchUpAfter(window, windowStart: at(2026, 10, 28, 7, 10), until: at(2026, 10, 28, 7, 20), specs: [a, b, c],
                                              calendar: zurich)
        XCTAssertEqual(next, CatchUpWindow(from: at(2026, 10, 28, 6, 20), until: at(2026, 10, 28, 7, 20), alarmIds: ["a", "b", "c"]))
        let apart = AlarmSchedule.catchUpAfter(window, windowStart: at(2026, 10, 28, 8, 0), until: at(2026, 10, 28, 8, 30), specs: [a, b, c],
                                               calendar: zurich)
        XCTAssertEqual(apart, CatchUpWindow(from: at(2026, 10, 28, 8, 0), until: at(2026, 10, 28, 8, 30), alarmIds: []))
    }
}
