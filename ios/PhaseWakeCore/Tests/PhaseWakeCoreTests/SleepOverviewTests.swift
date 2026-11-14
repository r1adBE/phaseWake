import XCTest
@testable import PhaseWakeCore

/// Übersicht «Tage / Wochen / Monate» und Pulsverlauf. Gleiche Fälle wie `SleepOverviewTest` (Kotlin).
final class SleepOverviewTests: XCTestCase {
    private var zurich: Calendar {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "Europe/Zurich")!
        return c
    }

    /// Uhrzeit am Kalendertag `day` («yyyy-MM-dd») in Zürich.
    private func at(_ day: String, _ hour: Int, _ minute: Int) -> Date {
        let parts = day.split(separator: "-").compactMap { Int($0) }
        return zurich.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2], hour: hour, minute: minute))!
    }

    /// Schlaf am Abend von `night` (Nachtdatum) um `bedHour`:`bedMinute` bis am Morgen danach um `wakeHour`:`wakeMinute`.
    private func sleep(_ night: String, _ bedHour: Int, _ bedMinute: Int, wakeHour: Int = 6, wakeMinute: Int = 30) -> SleepSummary {
        let start = at(night, bedHour, bedMinute)
        let end = at(NightDate.adding(1, to: night), wakeHour, wakeMinute)
        return SleepSummary(start: start, end: end, asleepMinutes: Int(end.timeIntervalSince(start) / 60))
    }

    /// Sonntag, 8.11.2026 (Morgen Montag 9.11.).
    private let today = "2026-11-08"

    private func day(_ back: Int) -> String { NightDate.adding(-back, to: today) }

    func testDaysAreTheLastSevenNightsWithAverages() {
        let sleeps = [sleep(today, 23, 0), sleep(day(1), 22, 30), sleep(day(10), 22, 0)]
        let o = SleepOverviews.of(sleeps, calendar: zurich, today: today, goalMinutes: 450, unit: .days)!
        XCTAssertEqual(o.bars.count, 7)
        XCTAssertEqual(o.bars.last?.first, today)
        XCTAssertEqual(o.bars.first?.first, day(6))
        // 7 Std. 30 Min und 8 Std.; die Nacht vor 10 Tagen liegt ausserhalb.
        XCTAssertEqual(o.bars.last?.asleepMinutes, 450)
        XCTAssertEqual(o.bars[5].asleepMinutes, 480)
        XCTAssertNil(o.bars[0].asleepMinutes)
        XCTAssertEqual(o.counted, 2)
        // 450 erreicht das Ziel 450, 480 auch.
        XCTAssertEqual(o.reachedNights, 2)
        XCTAssertEqual(o.averageMinutes, 465)
        // 23:00 und 22:30 → 22:45 = 645 Min ab Mittag; Aufwachen 06:30 = 1110.
        XCTAssertEqual(o.bedtimeMinute, 645)
        XCTAssertEqual(o.wakeMinute, 1110)
        XCTAssertEqual(o.scaleMinutes, 480)
        XCTAssertEqual(o.bars.last?.reached, true)
    }

    func testAverageTimesDoNotJumpAtNoon() {
        // Audit W-1: Aufwachen 11:56 und 12:50 → Ø 12:23 (23 Min ab Mittag), nicht 00:23 (743).
        let late = [sleep(today, 23, 0, wakeHour: 11, wakeMinute: 56), sleep(day(1), 23, 0, wakeHour: 12, wakeMinute: 50)]
        let o = SleepOverviews.of(late, calendar: zurich, today: today, goalMinutes: 450, unit: .days)!
        XCTAssertEqual(o.wakeMinute, 23)
        XCTAssertEqual(o.bedtimeMinute, 11 * 60)
        // Einschlafen 11:30 und 12:30 (Tagschläfer) → Ø 12:00 (0), nicht 00:00 (720).
        let daySleepers = [sleep(today, 11, 30), sleep(day(1), 12, 30)]
        XCTAssertEqual(SleepOverviews.of(daySleepers, calendar: zurich, today: today, goalMinutes: 450, unit: .days)!.bedtimeMinute, 0)
        // 11:59 und 12:00 → Ø 11:59:30 rundet auf 12:00 und bleibt in 0 ≤ m < 1440.
        XCTAssertEqual(Regularity.meanMinute([1439, 0]), 0)
    }

    func testWeeksGoByTheMorningAndTheFirstDayOfTheWeek() {
        let ranges = SleepOverviews.ranges(last: today, unit: .weeks, firstDay: .monday)
        XCTAssertEqual(ranges.count, 8)
        // Morgen 9.11. (Montag): Woche 9.–15.11., als Nachtdaten 8.–14.11.
        XCTAssertEqual(ranges.last?.first, "2026-11-08")
        XCTAssertEqual(ranges.last?.last, "2026-11-14")
        XCTAssertEqual(ranges[6].first, "2026-11-01")
        XCTAssertEqual(ranges[6].last, "2026-11-07")
        // Woche ab Sonntag: Morgen 9.11. gehört zur Woche ab 8.11.
        let sunday = SleepOverviews.ranges(last: today, unit: .weeks, firstDay: .sunday).last
        XCTAssertEqual(sunday?.first, "2026-11-07")
        XCTAssertEqual(sunday?.last, "2026-11-13")
    }

    func testMonthsGoByTheMorning() {
        let ranges = SleepOverviews.ranges(last: "2026-10-31", unit: .months)
        // Nacht 31.10. → Morgen 1.11.: November, als Nachtdaten 31.10.–29.11.
        XCTAssertEqual(ranges.last?.first, "2026-10-31")
        XCTAssertEqual(ranges.last?.last, "2026-11-29")
        XCTAssertEqual(ranges.first?.first, "2026-07-31")
        XCTAssertEqual(ranges.first?.last, "2026-08-30")
    }

    func testMonthsAcrossTheYear() {
        // Nur Swift (eigene Monatsrechnung): Morgen 1.1.2027 → Januar; davor Dezember bis Oktober 2026.
        let ranges = SleepOverviews.ranges(last: "2026-12-31", unit: .months)
        XCTAssertEqual(ranges.last?.first, "2026-12-31")
        XCTAssertEqual(ranges.last?.last, "2027-01-30")
        XCTAssertEqual(ranges[2].first, "2026-11-30")
        XCTAssertEqual(ranges[2].last, "2026-12-30")
        XCTAssertEqual(ranges.first?.first, "2026-09-30")
    }

    func testWeekBarIsTheAverageOfItsNights() {
        // Zwei Nächte in der Woche vom 2.–8.11. (Nachtdaten 1.–7.11.): 7 Std. 30 und 6 Std. 30.
        // Die zweite nach Mitternacht eingeschlafen: Nachtdatum über die Mitte bleibt der 3.11.
        let late = SleepSummary(start: at("2026-11-04", 0, 0), end: at("2026-11-04", 6, 30), asleepMinutes: 390)
        let sleeps = [sleep("2026-11-02", 23, 0), late]
        let o = SleepOverviews.of(sleeps, calendar: zurich, today: today, goalMinutes: 450, unit: .weeks)!
        // Heute (8.11.) noch ohne Daten: Die jüngste Säule ist die Woche der Nacht davor (Morgen So 8.11.).
        let week = o.bars.last!
        XCTAssertEqual(week.first, "2026-11-01")
        XCTAssertEqual(week.nights, 2)
        XCTAssertEqual(week.asleepMinutes, 420)
        XCTAssertFalse(week.reached)
        XCTAssertNil(o.bars[6].asleepMinutes)
    }

    func testNoDataInRangeIsNil() {
        XCTAssertNil(SleepOverviews.of([], calendar: zurich, today: today, goalMinutes: 450, unit: .months))
        XCTAssertNil(SleepOverviews.of([sleep(day(30), 23, 0)], calendar: zurich, today: today, goalMinutes: 450, unit: .days))
    }

    func testPulseCurveInFiveMinuteBuckets() {
        let start = Date(timeIntervalSince1970: 1_794_175_200) // 2026-11-08T22:00:00Z
        // 8 Stücke zu 10 Epochen; Stück 1 ohne Puls, Stück 0 mit 50–59, die übrigen konstant 60 + Stück.
        let epochs = (0..<80).map { i -> Epoch in
            let bucket = i / 10
            let pulse: Double? = bucket == 0 ? 50.0 + Double(i) : bucket == 1 ? nil : 60.0 + Double(bucket)
            return Epoch(motion: 0.01, heartRate: pulse)
        }
        let p = SleepOverviews.pulse(epochs, start: start)!
        XCTAssertEqual(p.buckets.count, 8)
        XCTAssertEqual(p.buckets[0], 54.5)
        XCTAssertNil(p.buckets[1])
        XCTAssertEqual(p.buckets[7], 67.0)
        // (545 + 10 · (62 + 63 + 64 + 65 + 66 + 67)) / 70 = 63.07 → 63.
        XCTAssertEqual(p.average, 63)
        XCTAssertEqual(p.low, 54.5)
        XCTAssertEqual(p.high, 67.0)
        XCTAssertEqual(p.end, start.addingTimeInterval(80 * 30))
        XCTAssertNil(SleepOverviews.pulse(Array(repeating: Epoch(motion: nil, heartRate: 60), count: 9), start: start))
    }

    func testPulseNeedsSixBucketsWithValues() {
        let start = Date(timeIntervalSince1970: 1_794_175_200)
        // Weckfenster 30 Min mit Puls nur in 5 Stücken: keine Kurve; in 6 Stücken: Kurve.
        XCTAssertNil(SleepOverviews.pulse((0..<60).map { Epoch(motion: 0.01, heartRate: $0 < 10 ? nil : 60) }, start: start))
        XCTAssertEqual(SleepOverviews.pulse(Array(repeating: Epoch(motion: 0.01, heartRate: 60), count: 60), start: start)?.buckets.count, 6)
    }

    func testNightWithOnlyAwakeHasNoStageSplit() {
        // Audit W-5: Quelle ohne Phasendetail (nur «wach» bekannt) gibt eine neutrale Säule, keine Säule «ganz wach».
        let night = sleep(today, 23, 0)
        let t = night.start
        let stages = [StageSpan(start: t, end: t.addingTimeInterval(200 * 60), stage: .unknown),
                      StageSpan(start: t.addingTimeInterval(200 * 60), end: t.addingTimeInterval(230 * 60), stage: .awake)]
        XCTAssertEqual(SleepOverviews.stageAverage([night], stages: stages), [:])
        let withLight = stages + [StageSpan(start: t.addingTimeInterval(230 * 60), end: t.addingTimeInterval(300 * 60), stage: .light)]
        XCTAssertEqual(SleepOverviews.stageAverage([night], stages: withLight)[.light], 70)
    }

    func testBarsCarryTheAverageStageMinutes() {
        let night = sleep(today, 23, 0)
        let other = sleep(day(1), 23, 0)
        let t = night.start
        func m(_ minutes: Int) -> Date { t.addingTimeInterval(Double(minutes * 60)) }
        let stages = [
            StageSpan(start: t, end: m(60), stage: .deep),
            StageSpan(start: m(60), end: m(300), stage: .light),
            StageSpan(start: m(300), end: m(450), stage: .rem),
            // Ausserhalb der Nacht: zählt nicht.
            StageSpan(start: m(-180), end: m(-120), stage: .deep),
        ]
        let o = SleepOverviews.of([other, night], calendar: zurich, today: today, goalMinutes: 450, unit: .weeks, firstDay: .monday, stages: stages)!
        // Beide Nächte in der Woche ab Montag 9.11. bzw. 2.11.: die heutige allein in der letzten Säule.
        XCTAssertEqual(o.bars.last?.stageMinutes, [.awake: 0, .rem: 150, .light: 240, .deep: 60])
        XCTAssertEqual(o.bars[6].stageMinutes, [:])
        XCTAssertEqual(SleepOverviews.stageAverage([], stages: stages), [:])
        // Monat: beide Nächte in einer Säule, nur eine mit Phasen → die Säule zeigt nur die Dauer (neutral).
        let month = SleepOverviews.of([other, night], calendar: zurich, today: today, goalMinutes: 450, unit: .months, firstDay: .monday, stages: stages)!
        XCTAssertEqual(month.bars.last?.nights, 2)
        XCTAssertEqual(month.bars.last?.stageMinutes, [:])
        // Beide Nächte mit Phasen: Durchschnitt über beide.
        let o2 = other.start
        let both = stages + [
            StageSpan(start: o2, end: o2.addingTimeInterval(90 * 60), stage: .deep),
            StageSpan(start: o2.addingTimeInterval(90 * 60), end: o2.addingTimeInterval(450 * 60), stage: .light),
        ]
        let all = SleepOverviews.of([other, night], calendar: zurich, today: today, goalMinutes: 450, unit: .months, firstDay: .monday, stages: both)!
        // Tief (60 + 90) / 2 = 75, leicht (240 + 360) / 2 = 300, REM 150 / 2 = 75.
        XCTAssertEqual(all.bars.last?.stageMinutes, [.awake: 0, .rem: 75, .light: 300, .deep: 75])
    }
}
