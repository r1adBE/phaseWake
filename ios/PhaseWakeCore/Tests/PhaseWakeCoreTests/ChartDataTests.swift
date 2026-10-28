import XCTest
@testable import PhaseWakeCore

/// Gleiche Fälle wie `ChartDataTest` (Kotlin).
final class ChartDataTests: XCTestCase {
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

    /// Nacht am Abend `night`: Einschlafen um `from` (nach Mitternacht am Folgetag), Aufwachen um `to` am Folgetag.
    private func sleep(_ night: String, _ from: String, _ to: String, _ asleep: Int) -> SleepSummary {
        let startDay = Int(from.prefix(2))! < 12 ? NightDate.adding(1, to: night) : night
        return SleepSummary(start: at(startDay + "T" + from), end: at(NightDate.adding(1, to: night) + "T" + to), asleepMinutes: asleep)
    }

    /// Uhr-Nacht am Abend `night`: Fenster 06:30–07:00 am Folgetag, geweckt `offset` Minuten nach Fensterbeginn.
    private func watchNight(_ night: String, _ offset: Int, _ reason: WakeReason, feeling: Int? = nil, snoozes: Int = 0) -> NightRecord {
        let start = at(NightDate.adding(1, to: night) + "T06:30")
        return NightRecord(alarmId: "a", windowStart: start, target: start.addingTimeInterval(1800), wokeAt: start.addingTimeInterval(Double(offset * 60)),
                           reason: reason, snoozes: snoozes, epochs: [], feeling: feeling)
    }

    private var sleeps: [SleepSummary] {
        [
            sleep("2026-10-27", "23:00", "06:30", 420),
            sleep("2026-10-26", "23:30", "07:00", 465),
            sleep("2026-10-25", "00:15", "07:10", 450),
            sleep("2026-10-20", "21:30", "05:00", 400),
        ]
    }

    func testSleepBarsFourteenNightsWithAxisAndBedtime() {
        let today = SleepNotes.nightDate(at("2026-10-28T07:20"), calendar: zurich)
        let bars = ChartData.sleepBars(sleeps, calendar: zurich, today: today, nextTarget: at("2026-10-29T07:00"), goalMinutes: 450)!
        XCTAssertEqual(bars.bars.count, 14)
        XCTAssertEqual(bars.bars.first?.date, "2026-10-14")
        XCTAssertEqual(bars.bars.last, SleepBar(date: "2026-10-27", startMinute: 11 * 60, endMinute: 18 * 60 + 30))
        XCTAssertEqual(bars.bars[11], SleepBar(date: "2026-10-25", startMinute: 735, endMinute: 1150))
        XCTAssertEqual(bars.bars[10], SleepBar(date: "2026-10-24", startMinute: nil, endMinute: nil))
        XCTAssertEqual(bars.bars.filter(\.hasData).count, 4)
        XCTAssertEqual(bars.axisStart, ChartData.axisStart)
        XCTAssertEqual(bars.axisEnd, ChartData.axisEnd)
        XCTAssertEqual(bars.bedtimeMinute, 11 * 60 + 30)
        XCTAssertEqual(bars.regular, 2)
        XCTAssertEqual(bars.regularCounted, 3)
        XCTAssertNil(ChartData.sleepBars(sleeps, calendar: zurich, today: today, nextTarget: nil, goalMinutes: 450)!.bedtimeMinute)
    }

    func testSleepBarsAxisGrowsAndEveningUsesNightBefore() {
        let evening = SleepNotes.nightDate(at("2026-10-28T21:00"), calendar: zurich)
        XCTAssertEqual(evening, "2026-10-28")
        XCTAssertEqual(ChartData.sleepBars(sleeps, calendar: zurich, today: evening, nextTarget: nil, goalMinutes: 450)!.bars.last?.date, "2026-10-27")
        let wide = ChartData.sleepBars([sleep("2026-10-27", "19:40", "11:50", 900)], calendar: zurich, today: evening, nextTarget: nil, goalMinutes: 450)!
        XCTAssertEqual(wide.axisStart, 7 * 60)
        XCTAssertEqual(wide.axisEnd, 24 * 60)
        XCTAssertEqual(wide.bars.last, SleepBar(date: "2026-10-27", startMinute: 7 * 60 + 40, endMinute: 23 * 60 + 50))
        let split = [sleep("2026-10-27", "22:00", "02:00", 200), sleep("2026-10-27", "23:00", "07:00", 450)]
        XCTAssertEqual(ChartData.sleepBars(split, calendar: zurich, today: evening, nextTarget: nil, goalMinutes: 450)!.bars.last?.startMinute, 11 * 60)
        let dst = ChartData.sleepBars([sleep("2026-10-24", "23:00", "07:00", 480)], calendar: zurich, today: "2026-10-24", nextTarget: nil, goalMinutes: 450)!
        XCTAssertEqual(dst.bars.last, SleepBar(date: "2026-10-24", startMinute: 660, endMinute: 1140))
        XCTAssertNil(ChartData.sleepBars([], calendar: zurich, today: evening, nextTarget: nil, goalMinutes: 450))
        XCTAssertNil(ChartData.sleepBars([sleep("2026-09-01", "23:00", "07:00", 480)], calendar: zurich, today: evening, nextTarget: nil, goalMinutes: 450))
    }

    func testDurationsSevenAndFourteenNights() {
        let week = ChartData.durations(sleeps, calendar: zurich, today: "2026-10-28", goalMinutes: 450)!
        XCTAssertEqual(week.bars.count, 7)
        XCTAssertEqual(week.bars.first?.date, "2026-10-21")
        XCTAssertEqual(week.bars.last, DurationBar(date: "2026-10-27", asleepMinutes: 420, reached: false))
        XCTAssertEqual(week.bars[4], DurationBar(date: "2026-10-25", asleepMinutes: 450, reached: true))
        XCTAssertEqual(week.bars[3], DurationBar(date: "2026-10-24", asleepMinutes: nil, reached: false))
        XCTAssertEqual(week.averageMinutes, 445)
        XCTAssertEqual(week.reached, 2)
        XCTAssertEqual(week.counted, 3)
        XCTAssertEqual(week.scaleMinutes, 465)
        let two = ChartData.durations(sleeps, calendar: zurich, today: "2026-10-28", goalMinutes: 450, nights: ChartData.nights)!
        XCTAssertEqual(two.bars.count, 14)
        XCTAssertEqual(two.averageMinutes, 433)
        XCTAssertEqual(two.reached, 2)
        XCTAssertEqual(two.counted, 4)
        XCTAssertEqual(ChartData.durations(sleeps, calendar: zurich, today: "2026-10-28", goalMinutes: 600)!.scaleMinutes, 600)
        XCTAssertNil(ChartData.durations([], calendar: zurich, today: "2026-10-28", goalMinutes: 450))
    }

    func testWakeMomentsKindsAndAverage() {
        let nights = [
            watchNight("2026-10-27", 33, .deadline, snoozes: 1),
            watchNight("2026-10-25", 30, .deadline),
            watchNight("2026-10-24", 18, .lightSleep),
            watchNight("2026-10-26", 25, .awake),
        ]
        let moments = ChartData.wakeMoments(nights, calendar: zurich)!
        XCTAssertEqual(moments.points.map(\.minutesBefore), [12, 0, 5, 0])
        XCTAssertEqual(moments.points.map(\.minutesAfter), [0, 0, 0, 3])
        XCTAssertEqual(moments.points.map(\.kind), [.light, .target, .awake, .late])
        XCTAssertEqual(moments.points.last?.date, "2026-10-27")
        XCTAssertEqual(moments.scaleMinutes, 30)
        XCTAssertEqual(moments.averageBefore, 4)
        let many = (1...20).map { watchNight(NightDate.adding($0, to: "2026-10-01"), 20, .lightSleep) }
        let last = ChartData.wakeMoments(many, calendar: zurich)!
        XCTAssertEqual(last.points.count, 14)
        XCTAssertEqual(last.points.last?.date, "2026-10-21")
        XCTAssertNil(ChartData.wakeMoments([], calendar: zurich))
    }

    func testHypnogramOfTheNight() {
        let night = NightRecord(alarmId: "a", windowStart: at("2026-10-28T06:30"), target: at("2026-10-28T07:00"), wokeAt: at("2026-10-28T06:42"),
                                reason: .lightSleep, snoozes: 0, epochs: [])
        let stages = [
            StageSpan(start: at("2026-10-26T23:00"), end: at("2026-10-27T06:00"), stage: .light),
            StageSpan(start: at("2026-10-27T23:00"), end: at("2026-10-27T23:20"), stage: .awake),
            StageSpan(start: at("2026-10-27T23:20"), end: at("2026-10-28T01:00"), stage: .light),
            StageSpan(start: at("2026-10-28T01:00"), end: at("2026-10-28T02:00"), stage: .deep),
            StageSpan(start: at("2026-10-28T02:00"), end: at("2026-10-28T05:00"), stage: .unknown),
            StageSpan(start: at("2026-10-28T05:00"), end: at("2026-10-28T06:20"), stage: .rem),
            StageSpan(start: at("2026-10-28T06:20"), end: at("2026-10-28T06:50"), stage: .light),
        ]
        let h = ChartData.hypnogram(stages.reversed(), night: night)!
        XCTAssertEqual(h.start, at("2026-10-27T23:00"))
        XCTAssertEqual(h.end, at("2026-10-28T07:00"))
        XCTAssertEqual(h.totalMinutes, 480)
        XCTAssertEqual(h.steps.count, 5)
        XCTAssertEqual(h.steps.first, HypnoStep(fromMinute: 0, toMinute: 20, stage: .awake))
        XCTAssertEqual(h.steps.last, HypnoStep(fromMinute: 440, toMinute: 470, stage: .light))
        XCTAssertEqual(h.windowFrom, 450)
        XCTAssertEqual(h.windowTo, 480)
        XCTAssertEqual(h.wokeAt, 462)
        XCTAssertEqual(h.stageAtWake, .light)
        XCTAssertEqual(h.minutesOf(.light), 130)
        XCTAssertEqual(h.minutesOf(.deep), 60)
        XCTAssertEqual([SleepStage.awake, .rem, .light, .deep, .unknown].map(ChartData.level), [0, 1, 2, 3, nil])
        XCTAssertNil(ChartData.hypnogram([StageSpan(start: at("2026-10-28T00:00"), end: at("2026-10-28T07:00"), stage: .unknown)], night: night))
        XCTAssertNil(ChartData.hypnogram(Array(stages.prefix(1)), night: night))
        XCTAssertNil(ChartData.hypnogram([], night: night))
    }

    func testFeelingSeriesWithNotesAndWeeks() {
        let nights = [
            watchNight("2026-10-27", 20, .lightSleep, feeling: 4),
            watchNight("2026-10-26", 10, .lightSleep, feeling: 3),
            watchNight("2026-10-26", 20, .lightSleep, feeling: 5),
            watchNight("2026-10-25", 20, .lightSleep),
            watchNight("2026-10-20", 20, .lightSleep, feeling: 2),
            watchNight("2026-10-15", 20, .lightSleep, feeling: 1),
            watchNight("2026-10-01", 20, .lightSleep, feeling: 5),
        ]
        let notes = ["2026-10-26": ["CAFFEINE"], "2026-10-28": ["STRESS"]]
        let series = ChartData.feelingSeries(nights, notes: notes, calendar: zurich, today: SleepNotes.nightDate(at("2026-10-28T07:20"), calendar: zurich))!
        XCTAssertEqual(series.points.count, 14)
        XCTAssertEqual(series.points.first?.date, "2026-10-14")
        XCTAssertEqual(series.points.last, FeelingPoint(date: "2026-10-27", feeling: 4, notes: []))
        XCTAssertEqual(series.points[12], FeelingPoint(date: "2026-10-26", feeling: 5, notes: ["CAFFEINE"]))
        XCTAssertEqual(series.points[11], FeelingPoint(date: "2026-10-25", feeling: nil, notes: []))
        XCTAssertEqual(series.thisWeek, 4.5)
        XCTAssertEqual(series.lastWeek, 1.5)
        let evening = ChartData.feelingSeries(nights, notes: notes, calendar: zurich, today: "2026-10-28")!
        XCTAssertEqual(evening.points.last?.date, "2026-10-27")
        XCTAssertFalse(evening.points.contains { $0.notes.contains("STRESS") })
        let one = ChartData.feelingSeries([nights[0]], notes: [:], calendar: zurich, today: "2026-10-27")!
        XCTAssertEqual(one.thisWeek, 4)
        XCTAssertNil(one.lastWeek)
        XCTAssertNil(ChartData.feelingSeries(nights.filter { $0.feeling == nil }, notes: notes, calendar: zurich, today: "2026-10-27"))
        XCTAssertNil(ChartData.feelingSeries([], notes: notes, calendar: zurich, today: "2026-10-27"))
    }
}
