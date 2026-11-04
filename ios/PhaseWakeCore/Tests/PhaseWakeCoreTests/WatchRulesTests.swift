import XCTest
import PhaseWakeCore

/// Ausstehende Nacht der Apple Watch (`WatchRules`): Ein Neuplanen kurz nach der Zielzeit ersetzt den Rückfall einer
/// Nacht, deren Sitzung nicht geweckt hat, nicht durch den Plan des nächsten Tages. Nur Apple.
final class WatchRulesTests: XCTestCase {
    private var zurich: Calendar {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "Europe/Zurich")!
        return c
    }

    /// Wie `SmartAlarm.ringLimit` und `SmartAlarm.fallbackDelay`.
    private let ringLimit: TimeInterval = 3600
    private let delay: TimeInterval = 60

    private func at(_ d: Int, _ h: Int, _ mi: Int, _ s: Int = 0) -> Date {
        zurich.date(from: DateComponents(year: 2026, month: 10, day: d, hour: h, minute: mi, second: s))!
    }

    private let daily = try! AlarmSpec(id: "a", hour: 7, minute: 0, days: Set(Weekday.allCases))

    /// Plan des täglichen Weckers für Mittwoch, 28.10.2026, 07:00.
    private var today: WakePlan { AlarmSchedule.plan(daily, target: at(28, 7, 0)) }

    private func pending(_ current: WakePlan?, now: Date, specs: [AlarmSpec]? = nil, done: [String: Date] = [:],
                         holidays: Set<String> = []) -> WakePlan? {
        WatchRules.pendingNight(current, specs: specs ?? [daily], now: now, doneUntil: done, ringLimit: ringLimit, calendar: zurich,
                                holidays: holidays)
    }

    func testPendingFromTargetUntilRingLimit() {
        XCTAssertNil(pending(today, now: at(28, 6, 59)), "Zielzeit noch nicht da")
        XCTAssertEqual(pending(today, now: at(28, 7, 0)), today)
        XCTAssertEqual(pending(today, now: at(28, 7, 0, 30)), today, "Rückfall noch nicht fällig")
        XCTAssertEqual(pending(today, now: at(28, 7, 59)), today)
        XCTAssertNil(pending(today, now: at(28, 8, 0)), "Zeitgrenze erreicht")
        XCTAssertNil(pending(nil, now: at(28, 7, 0, 30)))
    }

    func testPendingEndsWhenNightDoneOrAlarmChanged() {
        let now = at(28, 7, 0, 30)
        XCTAssertNil(pending(today, now: now, done: ["a": at(28, 7, 0)]), "Nacht erledigt")
        XCTAssertEqual(pending(today, now: now, done: ["a": at(27, 7, 0)]), today, "nur die Nacht davor erledigt")
        XCTAssertNil(pending(today, now: now, specs: []), "Wecker gelöscht")
        XCTAssertNil(pending(today, now: now, specs: [daily.with(enabled: false)]), "ausgeschaltet")
        XCTAssertNil(pending(today, now: now, specs: [try! AlarmSpec(id: "a", hour: 7, minute: 30, days: Set(Weekday.allCases))]),
                     "Zeit geändert")
        XCTAssertNil(pending(today, now: now, specs: [daily.with(skipHolidays: true)], holidays: ["2026-10-28"]), "Feiertag")
    }

    func testTransientNeverPending() {
        let nap = WakePlan(alarmId: Transient.napPrefix + "1", windowStart: at(28, 6, 55), target: at(28, 7, 0))
        XCTAssertNil(pending(nap, now: at(28, 7, 0, 30)))
    }

    func testPendingKeepsPlanUnchanged() {
        // Der Plan des Zustands bleibt gleich, auch wenn das Fenster inzwischen anders eingestellt ist.
        let changedWindow = try! AlarmSpec(id: "a", hour: 7, minute: 0, days: Set(Weekday.allCases), windowMinutes: 10)
        XCTAssertEqual(pending(today, now: at(28, 7, 0, 30), specs: [changedWindow]), today)
    }

    func testUnwokenNightWithoutStateOnlyUntilFallback() {
        func unwoken(_ now: Date, specs: [AlarmSpec]? = nil, done: [String: Date] = [:]) -> WakePlan? {
            WatchRules.unwokenNight(specs: specs ?? [daily], now: now, doneUntil: done, delay: delay, calendar: zurich)
        }
        XCTAssertNil(unwoken(at(28, 6, 59, 59)))
        XCTAssertEqual(unwoken(at(28, 7, 0)), today)
        XCTAssertEqual(unwoken(at(28, 7, 0, 59)), today)
        XCTAssertNil(unwoken(at(28, 7, 1)), "Rückfall fällig: ohne Zustand kein Nachholen mehr")
        XCTAssertNil(unwoken(at(28, 7, 0, 30), done: ["a": at(28, 7, 0)]), "erledigt")
        // Zwei Wecker: der frühere; gleiche Zielzeit: die kleinere Kennung.
        let b = try! AlarmSpec(id: "b", hour: 7, minute: 0, days: Set(Weekday.allCases))
        XCTAssertEqual(unwoken(at(28, 7, 0, 30), specs: [b, daily])?.alarmId, "a")
        let nap = try! AlarmSpec(id: Transient.napPrefix + "1", hour: 7, minute: 0)
        XCTAssertNil(unwoken(at(28, 7, 0, 30), specs: [nap]), "nie ein vorübergehender Plan")
    }

    func testFallbackKeptForPendingNight() {
        // Ausstehend: Rückfall bleibt 60 s nach der Zielzeit; ist er vorbei, wird er nicht neu gestellt.
        XCTAssertEqual(WatchRules.fallbackDate(today, pending: true, now: at(28, 7, 0, 30), delay: delay), at(28, 7, 1))
        XCTAssertNil(WatchRules.fallbackDate(today, pending: true, now: at(28, 7, 1), delay: delay))
        XCTAssertNil(WatchRules.fallbackDate(today, pending: true, now: at(28, 7, 20), delay: delay))
        // Nachgeholt (nicht ausstehend): Frist ab jetzt; vor der Zielzeit wie immer 60 s danach.
        XCTAssertEqual(WatchRules.fallbackDate(today, pending: false, now: at(28, 7, 20), delay: delay), at(28, 7, 21))
        XCTAssertEqual(WatchRules.fallbackDate(today, pending: false, now: at(28, 6, 0), delay: delay), at(28, 7, 1))
    }

    func testSessionOnlyBeforeFallbackForPendingNight() {
        XCTAssertTrue(WatchRules.startsSession(today, pending: true, now: at(28, 7, 0, 30), delay: delay))
        XCTAssertFalse(WatchRules.startsSession(today, pending: true, now: at(28, 7, 1), delay: delay))
        XCTAssertTrue(WatchRules.startsSession(today, pending: false, now: at(28, 7, 20), delay: delay))
    }

    /// Reise: Nur ein anderer Abstand zu UTC an der Zielzeit zählt als verschoben.
    func testZoneShiftedOnlyWithOtherOffset() {
        let target = at(28, 7, 0)
        func shifted(_ planned: String?, _ now: String, target: Date? = target) -> Bool {
            WatchRules.zoneShifted(plannedZone: planned, target: target, zone: TimeZone(identifier: now)!)
        }
        XCTAssertFalse(shifted("Europe/Zurich", "Europe/Zurich"))
        XCTAssertFalse(shifted("Europe/Zurich", "Europe/Berlin"), "gleicher Abstand")
        XCTAssertTrue(shifted("Europe/Zurich", "America/New_York"))
        XCTAssertTrue(shifted("Europe/Zurich", "Europe/London"))
        // Sommerzeit derselben Zone (25.10.2026 endet sie): kein Wechsel.
        XCTAssertFalse(shifted("Europe/Zurich", "Europe/Zurich", target: at(24, 7, 0)))
        XCTAssertFalse(shifted(nil, "America/New_York"), "nichts gemerkt")
        XCTAssertFalse(shifted("Europe/Zurich", "America/New_York", target: nil))
        XCTAssertFalse(shifted("Nowhere/Unknown", "America/New_York"), "unbekannte Zone")
    }
}
