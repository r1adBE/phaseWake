import XCTest
import PhaseWakeCore

/// Verlauf mit Nächten aus der Gesundheits-App. Gegenstück zu `HealthHistoryTest` (Kotlin), gleiche Fälle.
final class HealthHistoryTests: XCTestCase {
    private let calendar: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "Europe/Zurich")!
        return c
    }()

    private func t(_ text: String) -> Date { ISO8601DateFormatter().date(from: text)! }

    private func span(_ from: String, _ to: String, _ stage: SleepStage) -> StageSpan { StageSpan(start: t(from), end: t(to), stage: stage) }

    private func night(_ windowStart: String, _ wokeAt: String, _ alarmId: String = "a") -> NightRecord {
        NightRecord(alarmId: alarmId, windowStart: t(windowStart), target: t(windowStart).addingTimeInterval(30 * 60), wokeAt: t(wokeAt),
                    reason: .lightSleep, snoozes: 0, epochs: [])
    }

    private var full: HealthSession {
        HealthSession(start: t("2026-10-01T21:00:00Z"), end: t("2026-10-02T05:00:00Z"), stages: [
            span("2026-10-01T21:00:00Z", "2026-10-01T21:15:00Z", .awake),
            span("2026-10-01T21:15:00Z", "2026-10-02T01:00:00Z", .light),
            span("2026-10-02T01:00:00Z", "2026-10-02T02:00:00Z", .deep),
            span("2026-10-02T02:00:00Z", "2026-10-02T04:30:00Z", .rem),
            span("2026-10-02T04:30:00Z", "2026-10-02T05:00:00Z", .awake),
        ], source: "com.sec.android.app.shealth")
    }

    private func merge(_ nights: [NightRecord], _ sessions: [HealthSession], _ clearedAt: Date? = nil) -> [HistoryItem] {
        HealthHistory.merge(nights: nights, sessions: sessions, clearedAt: clearedAt, calendar: calendar)
    }

    /// Samsung-Health-Nacht vom 1. November 02:42–12:50 Ortszeit (CET): 11 Min. wach, 60 Phasen à 9 Min. 30 s
    /// (570 Min. Schlaf), 27 Min. wach. Je Phase abgerundet hätte das Diagramm 30 Minuten weniger gezeigt.
    private var lateNight: HealthSession {
        let first = t("2026-11-01T01:53:00Z")
        let kinds: [SleepStage] = [.light, .deep, .light, .rem]
        let middle = (0..<60).map { i in
            StageSpan(start: first.addingTimeInterval(Double(i) * 570), end: first.addingTimeInterval(Double(i + 1) * 570), stage: kinds[i % kinds.count])
        }
        return HealthSession(start: t("2026-11-01T01:42:00Z"), end: t("2026-11-01T11:50:00Z"),
                             stages: [span("2026-11-01T01:42:00Z", "2026-11-01T01:53:00Z", .awake)] + middle
                                 + [span("2026-11-01T11:23:00Z", "2026-11-01T11:50:00Z", .awake)],
                             source: "com.sec.android.app.shealth")
    }

    func testLateWakeUpSameNightAndTotalEverywhere() {
        XCTAssertEqual(lateNight.asleepMinutes, 570)
        XCTAssertEqual(StageStats.forSession(lateNight)?.asleepMinutes, 570)
        let sleeps = HealthHistory.summaries([lateNight], clearedAt: nil, calendar: calendar)
        XCTAssertEqual(sleeps.map(\.asleepMinutes), [570])
        // Verlauf und Detailseite: Nacht vom 31. Oktober (Morgen 1. November).
        XCTAssertEqual(merge([], [lateNight]).map(\.date), ["2026-10-31"])
        // Diagramme: dasselbe Nachtdatum, dieselbe Dauer («today» = Nachtdatum am Morgen des 2. November).
        let durations = ChartData.durations(sleeps, calendar: calendar, today: "2026-11-01", goalMinutes: 480)
        XCTAssertEqual(durations?.bars.last?.date, "2026-10-31")
        XCTAssertEqual(durations?.bars.last?.asleepMinutes, 570)
        let bars = ChartData.sleepBars(sleeps, calendar: calendar, today: "2026-11-01", nextTarget: nil, goalMinutes: 480)
        XCTAssertEqual(bars?.bars.last?.date, "2026-10-31")
        XCTAssertEqual(bars?.bars.last?.hasData, true)
        XCTAssertEqual(NoteEffect.asleepByNight(sleeps, calendar: calendar), ["2026-10-31": 570])
        XCTAssertEqual(SleepGoal.nightsOf(sleeps, calendar: calendar).map(\.date), ["2026-10-31"])
        XCTAssertEqual(SleepGoal.nightsOf(sleeps, calendar: calendar).map(\.asleepMinutes), [570])
        XCTAssertEqual(Regularity.bedtimes(of: sleeps, calendar: calendar).map(\.date), ["2026-10-31"])
    }

    func testSessionMetrics() {
        XCTAssertEqual(full.durationMinutes, 480)
        XCTAssertEqual(full.asleepMinutes, 435)
        XCTAssertEqual(full.awakeMinutes, 45)
        XCTAssertEqual(full.asleepAt, t("2026-10-01T21:15:00Z"))
        XCTAssertEqual(full.minutesOf(.deep), 60)
        XCTAssertTrue(full.hasStages)
        XCTAssertFalse(full.isNap(calendar: calendar))
        XCTAssertEqual(full.steps.count, 5)
        XCTAssertEqual(full.steps[1], HypnoStep(fromMinute: 15, toMinute: 240, stage: .light))
    }

    func testSessionWithoutStages() {
        let s = HealthSession(start: t("2026-10-01T22:00:00Z"), end: t("2026-10-02T06:00:00Z"))
        XCTAssertEqual(s.asleepMinutes, 480)
        XCTAssertNil(s.awakeMinutes)
        XCTAssertFalse(s.hasStages)
        XCTAssertEqual(s.asleepAt, s.start)
        XCTAssertTrue(s.steps.isEmpty)
    }

    func testOnlyUnknownStages() {
        let s = HealthSession(start: t("2026-10-01T22:00:00Z"), end: t("2026-10-02T06:00:00Z"),
                              stages: [span("2026-10-01T22:00:00Z", "2026-10-02T06:00:00Z", .unknown)])
        XCTAssertFalse(s.hasStages)
        XCTAssertNil(s.awakeMinutes)
        XCTAssertEqual(s.asleepMinutes, 480)
        XCTAssertTrue(s.steps.isEmpty)
    }

    func testStagesClippedToSession() {
        let s = HealthSession(start: t("2026-10-01T22:00:00Z"), end: t("2026-10-02T06:00:00Z"),
                              stages: [span("2026-10-01T21:00:00Z", "2026-10-01T23:00:00Z", .light)])
        XCTAssertEqual(s.minutesOf(.light), 60)
        XCTAssertEqual(s.asleepAt, t("2026-10-01T22:00:00Z"))
        XCTAssertEqual(s.awakeMinutes, 0)
        XCTAssertEqual(HealthSession(start: s.end, end: s.start).durationMinutes, 0)
    }

    func testMinutesRoundDownLikeKotlin() {
        // HealthKit-Zeiten sind nicht immer ganze Millisekunden: 179 min 59,9996 s bleiben 179 Minuten (ein Nap).
        let start = t("2026-10-02T12:00:00Z")
        let almost = HealthSession(start: start, end: start.addingTimeInterval(3 * 3600 - 0.0004))
        XCTAssertEqual(almost.durationMinutes, 179)
        XCTAssertEqual(HealthSession(start: start, end: start.addingTimeInterval(3 * 3600)).durationMinutes, 180)
    }

    func testNapIsDropped() {
        let nap = HealthSession(start: t("2026-10-02T12:00:00Z"), end: t("2026-10-02T14:59:00Z"))
        let three = HealthSession(start: t("2026-10-03T12:00:00Z"), end: t("2026-10-03T15:00:00Z"))
        XCTAssertTrue(nap.isNap(calendar: calendar))
        XCTAssertFalse(three.isNap(calendar: calendar))
        XCTAssertEqual(merge([], [nap, three]).map(\.session), [three])
        // Ende nicht nach Beginn: keine Nacht.
        XCTAssertTrue(merge([], [HealthSession(start: three.end, end: three.start)]).isEmpty)
    }

    func testNapOnlyInDaytime() {
        // Europe/Zurich im Oktober: UTC+2. Kurz und ganz zwischen 09:00 und 19:00 Ortszeit: Nickerchen (Grenzen einschliesslich).
        XCTAssertTrue(HealthSession(start: t("2026-10-02T07:00:00Z"), end: t("2026-10-02T09:00:00Z")).isNap(calendar: calendar))
        XCTAssertTrue(HealthSession(start: t("2026-10-02T15:00:00Z"), end: t("2026-10-02T17:00:00Z")).isNap(calendar: calendar))
        // Beginn 08:59 oder Ende 19:01: Nacht.
        XCTAssertFalse(HealthSession(start: t("2026-10-02T06:59:00Z"), end: t("2026-10-02T08:59:00Z")).isNap(calendar: calendar))
        XCTAssertFalse(HealthSession(start: t("2026-10-02T15:01:00Z"), end: t("2026-10-02T17:01:00Z")).isNap(calendar: calendar))
        // Über Mitternacht (23:00–01:00) und kurz in der Nacht (01:00–03:00): Nacht, im Verlauf.
        XCTAssertFalse(HealthSession(start: t("2026-10-01T21:00:00Z"), end: t("2026-10-01T23:00:00Z")).isNap(calendar: calendar))
        let short = HealthSession(start: t("2026-10-01T23:00:00Z"), end: t("2026-10-02T01:00:00Z"))
        XCTAssertFalse(short.isNap(calendar: calendar))
        let items = merge([], [short])
        XCTAssertEqual(items.map(\.session), [short])
        XCTAssertEqual(items.first?.date, "2026-10-01")
        // Diagramme und Trends nach derselben Regel: kurze Nacht zählt, Nickerchen am Tag nicht.
        let dayNap = HealthSession(start: t("2026-10-02T10:00:00Z"), end: t("2026-10-02T12:00:00Z"))
        XCTAssertEqual(HealthHistory.summaries([short, dayNap], clearedAt: nil, calendar: calendar).map(\.asleepMinutes), [120])
        XCTAssertEqual(HealthHistory.napEarliestHour, 9)
        XCTAssertEqual(HealthHistory.napLatestHour, 19)
    }

    func testPhaseWakeNightWins() {
        let own = night("2026-10-02T04:30:00Z", "2026-10-02T04:48:00Z")
        let items = merge([own], [full])
        XCTAssertEqual(items.count, 1)
        XCTAssertEqual(items[0].kind, .phaseWake)
        XCTAssertEqual(items[0].night, own)
        XCTAssertEqual(items[0].date, "2026-10-01")
    }

    func testOverlapDifferentDateDropped() {
        // Eigene Nacht 11:30–12:30 Ortszeit (Nachtdatum 1.10.), Sitzung bis 12:10 Ortszeit (Nachtdatum 2.10.).
        let own = night("2026-10-02T09:30:00Z", "2026-10-02T10:30:00Z")
        let s = HealthSession(start: t("2026-10-02T03:00:00Z"), end: t("2026-10-02T10:10:00Z"))
        XCTAssertEqual(merge([own], [s]).map(\.kind), [.phaseWake])
    }

    func testLongestPerDate() {
        let a = HealthSession(start: t("2026-10-01T21:00:00Z"), end: t("2026-10-02T05:00:00Z"), source: "a")
        let b = HealthSession(start: t("2026-10-01T22:00:00Z"), end: t("2026-10-02T05:30:00Z"), source: "b")
        let c = HealthSession(start: t("2026-10-01T21:30:00Z"), end: t("2026-10-02T05:30:00Z"), source: "c")
        XCTAssertEqual(merge([], [b, c, a]).map(\.session), [a])
        let unnamed = HealthSession(start: a.start, end: a.end, stages: a.stages, source: nil)
        XCTAssertEqual(merge([], [a, unnamed]).map(\.session), [unnamed])
    }

    func testClearedAtHides() {
        let own = night("2026-09-20T04:30:00Z", "2026-09-20T04:48:00Z")
        XCTAssertEqual(merge([own], [full], full.end).map(\.kind), [.phaseWake])
        let visible = merge([own], [full], full.end.addingTimeInterval(-0.001))
        XCTAssertEqual(visible.map(\.kind), [.health, .phaseWake])
        XCTAssertEqual(HealthHistory.visible([full], clearedAt: nil), [full])
    }

    func testSortedNewestFirst() {
        let first = night("2026-10-02T04:30:00Z", "2026-10-02T04:48:00Z")
        let session = HealthSession(start: t("2026-10-02T21:00:00Z"), end: t("2026-10-03T05:00:00Z"))
        let third = night("2026-10-04T04:30:00Z", "2026-10-04T04:50:00Z")
        let items = merge([first, third], [session])
        XCTAssertEqual(items.map(\.kind), [.phaseWake, .health, .phaseWake])
        XCTAssertEqual(items.map(\.at), [third.wokeAt, session.end, first.wokeAt])
        XCTAssertEqual(items[1].date, "2026-10-02")
        // Gleiche Weckzeit: späterer Fensterbeginn zuerst, dann Wecker-Kennung.
        let early = night("2026-10-06T04:00:00Z", "2026-10-06T04:40:00Z", "z")
        let late = night("2026-10-06T04:20:00Z", "2026-10-06T04:40:00Z", "y")
        let sameB = night("2026-10-06T04:20:00Z", "2026-10-06T04:40:00Z", "b")
        XCTAssertEqual(merge([early, late, sameB], []).map(\.night), [sameB, late, early])
        XCTAssertEqual(HistoryItem(kind: .health, date: items[1].date).at, Date(timeIntervalSince1970: 0))
    }

    func testSummariesRespectCleared() {
        let plain = HealthSession(start: t("2026-10-03T22:00:00Z"), end: t("2026-10-04T06:00:00Z"))
        XCTAssertEqual(HealthHistory.summaries([full, plain], clearedAt: nil, calendar: calendar).map(\.asleepMinutes), [435, 480])
        XCTAssertEqual(HealthHistory.summaries([full, plain], clearedAt: full.end, calendar: calendar).map(\.asleepMinutes), [480])
        XCTAssertEqual(HealthHistory.stages([plain, full], clearedAt: nil, calendar: calendar).count, 5)
        XCTAssertEqual(HealthHistory.stages([plain, full], clearedAt: nil, calendar: calendar).first, full.stages.first)
        XCTAssertTrue(HealthHistory.stages([full], clearedAt: full.end, calendar: calendar).isEmpty)
    }

    func testOverlappingAppsCountOnce() {
        // Zwei Apps schreiben dieselbe Nacht: die mit Phasen gewinnt, auch wenn die andere länger ist.
        let fit = HealthSession(start: t("2026-10-01T20:30:00Z"), end: t("2026-10-02T06:00:00Z"), source: "com.google.android.apps.fitness")
        XCTAssertEqual(merge([], [fit, full]).map(\.session), [full])
        XCTAssertEqual(HealthHistory.summaries([fit, full], clearedAt: nil, calendar: calendar).map(\.asleepMinutes), [435])
        XCTAssertEqual(HealthHistory.stages([fit, full], clearedAt: nil, calendar: calendar), full.stages)
        // Beide ohne Phasen: die längere.
        let short = HealthSession(start: t("2026-10-01T22:00:00Z"), end: t("2026-10-02T05:00:00Z"))
        XCTAssertEqual(HealthHistory.distinct([short, fit]), [fit])
    }

    func testTouchingSessionsAreKept() {
        let first = HealthSession(start: t("2026-10-01T20:00:00Z"), end: t("2026-10-02T00:00:00Z"))
        let second = HealthSession(start: t("2026-10-02T00:00:00Z"), end: t("2026-10-02T05:00:00Z"))
        XCTAssertEqual(HealthHistory.distinct([second, first]), [first, second])
        // Die zweite schliesst ohne Lücke an (`HealthHistory.continuationGap`): eine Nacht von 20:00 bis 05:00 UTC.
        let joined = merge([], [first, second])
        XCTAssertEqual(joined.count, 1)
        XCTAssertEqual(joined.first?.session?.start, first.start)
        XCTAssertEqual(joined.first?.session?.end, second.end)
        XCTAssertEqual(joined.first?.session?.asleepMinutes, 540)
    }

    func testForeignSessionOnTheDateOfAnOwnNight() {
        // Audit BE-4: Phase-Wake-Nacht 06:30–07:00 (2.10., Nachtdatum 1.10.).
        let own = night("2026-10-02T04:30:00Z", "2026-10-02T05:00:00Z")
        // Fremde Sitzung am selben Nachtdatum ohne Überschneidung (Nachmittag 1.10.): fällt weg.
        let sameDate = HealthSession(start: t("2026-10-01T14:00:00Z"), end: t("2026-10-01T18:00:00Z"))
        XCTAssertEqual(merge([own], [sameDate]).map(\.kind), [.phaseWake])
        // Wecker am Mittag (Fenster 12:30–13:00, Nachtdatum 2.10.): Sitzung 04:30–12:30 (Nachtdatum 1.10.) endet genau am
        // Fensterbeginn und bleibt; eine Sekunde länger überschneidet sie sich und fällt weg.
        let noon = night("2026-10-02T10:30:00Z", "2026-10-02T11:00:00Z")
        XCTAssertEqual(merge([noon], [HealthSession(start: t("2026-10-02T02:30:00Z"), end: t("2026-10-02T10:30:00Z"))]).count, 2)
        XCTAssertEqual(merge([noon], [HealthSession(start: t("2026-10-02T02:30:00Z"), end: t("2026-10-02T10:30:01Z"))]).count, 1)
        // Anderes Nachtdatum, beginnt genau beim Wecken: bleibt; eine Sekunde früher: fällt weg.
        XCTAssertEqual(merge([own], [HealthSession(start: t("2026-10-02T05:00:00Z"), end: t("2026-10-02T20:00:00Z"))]).count, 2)
        XCTAssertEqual(merge([own], [HealthSession(start: t("2026-10-02T04:59:59Z"), end: t("2026-10-02T20:00:00Z"))]).count, 1)
    }

    func testDocumentedNumbersAsLiterals() {
        // Audit BE-3: Zahlen der Regeln für Nächte, Trends und Bettzeit als Wert.
        XCTAssertEqual(HealthHistory.minNightMinutes, 180)
        XCTAssertEqual(HealthHistory.napEarliestHour, 9)
        XCTAssertEqual(HealthHistory.napLatestHour, 19)
        XCTAssertEqual(HealthHistory.continuationGap, 60 * 60)
        XCTAssertEqual(HealthHistory.overlapTolerance, 5 * 60)
        XCTAssertEqual(HealthHistory.days, 90)
        XCTAssertEqual(Trends.minNightsPerWeek, 3)
        XCTAssertEqual(Trends.minNightsForSpread, 5)
        XCTAssertEqual(Regularity.toleranceMinutes, 30)
        XCTAssertEqual(Regularity.weekNights, 7)
        XCTAssertEqual(Regularity.medianNights, 14)
        XCTAssertEqual(Bedtime.leadMinutes, 30)
        XCTAssertEqual(Bedtime.dialMinutes, 720)
    }
}
