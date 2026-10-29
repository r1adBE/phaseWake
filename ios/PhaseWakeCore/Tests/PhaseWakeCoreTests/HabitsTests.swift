import XCTest
@testable import PhaseWakeCore

/// Gleiche Fälle wie `HabitsTest` (Kotlin), ohne die Rechenaufgabe (nur Android).
final class HabitsTests: XCTestCase {
    private var zurich: Calendar {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "Europe/Zurich")!
        return c
    }

    private func at(_ text: String) -> Date {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "Europe/Zurich")!
        f.dateFormat = "yyyy-MM-dd'T'HH:mm"
        return f.date(from: text)!
    }

    /// Mittwoch, 28. Oktober 2026.
    private let today = "2026-10-28"

    private func minus(_ days: Int, _ date: String) -> String { NightDate.adding(-days, to: date) }

    /// Bettzeit am Abend des Nachtdatums um `hhmm` (nach Mitternacht: am Folgetag).
    private func bedtime(_ date: String, _ hhmm: String) -> (date: String, start: Date) {
        let hour = Int(hhmm.prefix(2))!
        let day = hour < 12 ? NightDate.adding(1, to: date) : date
        return (date: date, start: at(day + "T" + hhmm))
    }

    private func assertCount(_ result: (regular: Int, total: Int), _ regular: Int, _ total: Int, line: UInt = #line) {
        XCTAssertEqual(result.regular, regular, line: line)
        XCTAssertEqual(result.total, total, line: line)
    }

    func testNightDateArithmetic() {
        XCTAssertEqual(NightDate.adding(1, to: "2026-10-31"), "2026-11-01")
        XCTAssertEqual(NightDate.adding(-1, to: "2026-03-01"), "2026-02-28")
        XCTAssertEqual(NightDate.adding(1, to: "kaputt"), "kaputt")
        XCTAssertFalse(NightDate.isValid("2026-02-30"))
        XCTAssertEqual(NightDate.weekday("2026-10-31"), .saturday)
    }

    func testRegularityAgainstMedianOfFourteenNights() {
        let until = "2026-10-27"
        // 14 Nächte um 23:00, die letzten 7 davon: 23:20, 23:30, 23:31, 00:10, 22:45, 23:00, 22:29.
        let older = (7..<14).map { bedtime(minus($0, until), "23:00") }
        let week = ["23:20", "23:30", "23:31", "00:10", "22:45", "23:00", "22:29"].enumerated().map { bedtime(minus(6 - $0.offset, until), $0.element) }
        // Median aller 14: 23:00 → innerhalb ± 30 Min: 23:20, 23:30, 22:45, 23:00 = 4.
        assertCount(Regularity.count(older + week, calendar: zurich), 4, 7)
        assertCount(Regularity.count(older + week, calendar: zurich, until: until), 4, 7)
        // Lücken zählen nicht mit; doppeltes Datum: die früheste Bettzeit.
        let sparse = [bedtime(until, "23:40"), bedtime(until, "23:00"), bedtime(minus(1, until), "23:10")]
        assertCount(Regularity.count(sparse, calendar: zurich), 2, 2)
        assertCount(Regularity.count(sparse, calendar: zurich, until: minus(1, until)), 1, 1)
        assertCount(Regularity.count([], calendar: zurich), 0, 0)
        // Mitternacht dazwischen: 23:50 und 00:15 liegen 25 Min auseinander.
        XCTAssertEqual(Regularity.minutesFromNoon(at("2026-10-28T03:00"), calendar: zurich), 15 * 60)
        assertCount(Regularity.count([bedtime(until, "23:50"), bedtime(minus(1, until), "00:15")], calendar: zurich), 2, 2)
    }

    func testStreakCurrentAndLongest() {
        var reached: [String: Bool] = [
            minus(1, today): true, minus(2, today): true, minus(3, today): true,
            minus(4, today): false,
            minus(5, today): true, minus(6, today): true, minus(7, today): true, minus(8, today): true,
            // Lücke (keine Daten) bei −9.
            minus(10, today): true,
        ]
        // Abend: heute noch ohne Daten → ab gestern.
        XCTAssertEqual(Streak.current(reached, today: today), 3)
        reached[today] = false
        XCTAssertEqual(Streak.current(reached, today: today), 0)
        reached[today] = true
        XCTAssertEqual(Streak.current(reached, today: today), 4)
        reached[today] = nil
        XCTAssertEqual(Streak.longest(reached, today: today), 4)
        // Ausserhalb von 90 Tagen zählt nicht.
        let old = Dictionary(uniqueKeysWithValues: (100..<110).map { (minus($0, today), true) })
        XCTAssertEqual(Streak.longest(old, today: today), 0)
        XCTAssertEqual(Streak.current([:], today: today), 0)
    }

    func testStreakFromSleeps() {
        let sleeps = [
            SleepSummary(start: at("2026-10-26T23:00"), end: at("2026-10-27T07:00"), asleepMinutes: 470),
            SleepSummary(start: at("2026-10-27T23:00"), end: at("2026-10-28T06:00"), asleepMinutes: 400),
        ]
        XCTAssertEqual(Streak.reachedByNight(sleeps, calendar: zurich, goalMinutes: 450), ["2026-10-26": true, "2026-10-27": false])
    }

    func testWeeklyWindowAndNotificationTime() {
        // Sonntag 25. 10.: Woche Sonntag 18. bis Samstag 24. (Nachtdaten); Montag 26.: dieselbe.
        XCTAssertEqual(WeeklySummary.weekEnd(today: "2026-10-25"), "2026-10-24")
        XCTAssertEqual(WeeklySummary.weekEnd(today: "2026-10-26"), "2026-10-24")
        XCTAssertTrue(WeeklySummary.cardVisible(today: "2026-10-25"))
        XCTAssertTrue(WeeklySummary.cardVisible(today: "2026-10-26"))
        XCTAssertFalse(WeeklySummary.cardVisible(today: today))
        XCTAssertEqual(WeeklySummary.nextNotification(after: at("2026-10-28T12:00"), calendar: zurich), at("2026-11-01T18:00"))
        XCTAssertEqual(WeeklySummary.nextNotification(after: at("2026-10-25T18:00"), calendar: zurich), at("2026-11-01T18:00"))
        XCTAssertEqual(WeeklySummary.nextNotification(after: at("2026-10-25T17:59"), calendar: zurich), at("2026-10-25T18:00"))
    }

    private func night(woke: Date, feeling: Int) -> NightRecord {
        NightRecord(alarmId: "a", windowStart: woke.addingTimeInterval(-1800), target: woke, wokeAt: woke,
                    reason: .lightSleep, snoozes: 0, epochs: [], feeling: feeling)
    }

    func testWeeklySummaryOfLastWeek() {
        let end = "2026-10-24"
        // Sieben Nächte (18.–24.) um 23:00, abwechselnd 8 h und 7 h; dazu eine Nacht der Folgewoche.
        let sleeps = (0..<7).map { i -> SleepSummary in
            let start = at(minus(i, end) + "T23:00")
            let minutes = i % 2 == 0 ? 480 : 420
            return SleepSummary(start: start, end: start.addingTimeInterval(Double(minutes + 10) * 60), asleepMinutes: minutes)
        } + [SleepSummary(start: at("2026-10-25T23:00"), end: at("2026-10-26T07:00"), asleepMinutes: 480)]
        let nights = [
            night(woke: at("2026-10-20T06:30"), feeling: 4),
            night(woke: at("2026-10-22T06:30"), feeling: 2),
            night(woke: at("2026-10-27T06:30"), feeling: 1),
        ]
        let summary = WeeklySummary.of(nights: nights, sleeps: sleeps, notes: [:], calendar: zurich, goalMinutes: 450, weekEnd: end)
        XCTAssertEqual(summary.from, "2026-10-18")
        XCTAssertEqual(summary.asleepAverage, (4 * 480 + 3 * 420) / 7)
        XCTAssertEqual(summary.goalReached, 4)
        XCTAssertEqual(summary.nightsWithSleep, 7)
        XCTAssertEqual(summary.regular, 7)
        XCTAssertEqual(summary.regularCounted, 7)
        XCTAssertEqual(summary.feelingAverage!, 3, accuracy: 1e-9)
        XCTAssertNil(summary.strongestNote)
        XCTAssertTrue(summary.hasData)
        let empty = WeeklySummary.of(nights: [], sleeps: [], notes: [:], calendar: zurich, goalMinutes: 450, weekEnd: end)
        XCTAssertFalse(empty.hasData)
        XCTAssertNil(empty.asleepAverage)
    }

    func testRoutineItemsAndAverage() {
        XCTAssertEqual(Routine.decodeItems(nil), Routine.defaults)
        XCTAssertEqual(Routine.decodeItems(""), [])
        let items = Routine.defaults + [RoutineItem(id: Routine.customId("1"), text: "Buch | lesen")]
        let text = Routine.encodeItems(items)
        XCTAssertEqual(text, "PHONE|\nLIGHT|\nWATER|\ncustom:1|Buch   lesen")
        XCTAssertEqual(Routine.decodeItems(text).map(\.id), ["PHONE", "LIGHT", "WATER", "custom:1"])
        // Unbekannte und leere eigene Punkte fallen weg, höchstens 8.
        XCTAssertEqual(Routine.decodeItems("PHONE|\nFOO|x\ncustom:2|").map(\.id), ["PHONE"])
        XCTAssertEqual(Routine.decodeItems((1...10).map { "custom:\($0)|P\($0)" }.joined(separator: "\n")).count, 8)

        var ticks: [String: [String]] = [:]
        XCTAssertNil(Routine.average(ticks, items: Routine.defaults, today: today))
        ticks = Routine.toggle(ticks, date: today, id: Routine.phone)
        ticks = Routine.toggle(ticks, date: today, id: Routine.light)
        ticks = Routine.toggle(ticks, date: minus(1, today), id: Routine.phone)
        XCTAssertNil(Routine.average(ticks, items: Routine.defaults, today: today))
        ticks = Routine.toggle(ticks, date: minus(2, today), id: Routine.phone)
        ticks = Routine.toggle(ticks, date: minus(2, today), id: Routine.water)
        ticks = Routine.toggle(ticks, date: minus(2, today), id: Routine.light)
        // (2 + 1 + 3) / 3
        XCTAssertEqual(Routine.average(ticks, items: Routine.defaults, today: today)!, 2, accuracy: 1e-9)
        // Abhaken rückgängig; ohne Haken fällt das Datum weg.
        XCTAssertEqual(Routine.toggle(ticks, date: today, id: Routine.phone)[today], [Routine.light])
        XCTAssertNil(Routine.toggle(Routine.toggle(ticks, date: today, id: Routine.phone), date: today, id: Routine.light)[today])
        XCTAssertEqual(Routine.decodeTicks(Routine.encodeTicks(ticks)), ticks)
        // Gelöschter Punkt zählt nicht mehr.
        XCTAssertNil(Routine.average(ticks, items: [RoutineItem(id: Routine.water)], today: today))
    }

    /// Hilfen der Abendroutine und der Regelmässigkeit (Randfälle).
    func testRoutineHelpersAndMinutesFromNoon() {
        XCTAssertTrue(Routine.isBuiltIn(Routine.water))
        XCTAssertFalse(Routine.isBuiltIn("custom:1"))
        XCTAssertEqual(Routine.cleanText("  Buch\nlesen|jetzt  "), "Buch lesen jetzt")
        XCTAssertEqual(Routine.cleanText(String(repeating: "a", count: 50)).count, Routine.maxTextLength)
        var ticks: [String: [String]] = [:]
        for day in 0..<100 { ticks[minus(day, today)] = [Routine.phone] }
        ticks["2026-10-29"] = []
        let kept = Routine.trim(ticks)
        XCTAssertEqual(kept.count, Routine.maxDates)
        XCTAssertNotNil(kept[today])
        XCTAssertNil(kept[minus(90, today)])
        XCTAssertEqual(Regularity.minutesFromNoon(at("2026-10-28T12:00"), calendar: zurich), 0)
        XCTAssertEqual(Regularity.minutesFromNoon(at("2026-10-28T23:30"), calendar: zurich), 690)
        XCTAssertEqual(Regularity.minutesFromNoon(at("2026-10-29T00:30"), calendar: zurich), 750)
        XCTAssertEqual(Regularity.minutesFromNoon(at("2026-10-29T11:59"), calendar: zurich), 1439)
        XCTAssertEqual(NightDate.today(at("2026-10-29T00:30"), calendar: zurich), "2026-10-29")
        XCTAssertFalse(NightDate.isValid("2026-02-30"))
        XCTAssertNil(NightDate.weekday("kaputt"))
    }
}
