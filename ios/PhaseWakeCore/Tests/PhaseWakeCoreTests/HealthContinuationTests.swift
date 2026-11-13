import XCTest
import PhaseWakeCore

/// Weiterschlafen nach dem Wecken (`HealthHistory.continuationGap`) mit der echten Nacht vom 12. November 2026
/// (Galaxy Watch 8, Samsung Health → Health Connect). Gegenstück zu `HealthContinuationTest` (Kotlin), gleiche Fälle.
///
/// Samsung zeigt: im Bett 01:36–11:56, Schlaf 03:11–09:57 und 09:58–11:56 (zwei Sitzungen, getrennt durch den
/// Wecker um 09:57), Schlafzeit 8 h 44, tatsächlicher Schlaf 7 h 18, Einschlafen 1 h 35, wach 1 h 26.
/// Europe/Zurich im November: UTC+1.
final class HealthContinuationTests: XCTestCase {
    private let calendar: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "Europe/Zurich")!
        return c
    }()

    private let samsung = "com.sec.android.app.shealth"

    private func t(_ text: String) -> Date { ISO8601DateFormatter().date(from: text)! }

    private func span(_ from: String, _ to: String, _ stage: SleepStage) -> StageSpan {
        StageSpan(start: t("2026-11-12T\(from):00Z"), end: t("2026-11-12T\(to):00Z"), stage: stage)
    }

    private func merge(_ sessions: [HealthSession], nights: [NightRecord] = []) -> [HistoryItem] {
        HealthHistory.merge(nights: nights, sessions: sessions, clearedAt: nil, calendar: calendar)
    }

    private func summaries(_ sessions: [HealthSession]) -> [SleepSummary] {
        HealthHistory.summaries(sessions, clearedAt: nil, calendar: calendar)
    }

    /// Erste Sitzung 01:36–09:57 Ortszeit: 95 Min. bis zum Einschlafen, 60 Min. wach, 346 Min. Schlaf.
    private var night: HealthSession {
        HealthSession(start: t("2026-11-12T00:36:00Z"), end: t("2026-11-12T08:57:00Z"), stages: [
            span("00:36", "02:11", .awake),
            span("02:11", "04:00", .light),
            span("04:00", "05:00", .deep),
            span("05:00", "06:00", .awake),
            span("06:00", "08:57", .rem),
        ], source: samsung)
    }

    /// Zweite Sitzung 09:58–11:56 Ortszeit (nach dem Wecker um 09:57): 26 Min. wach, 92 Min. Schlaf.
    private var sleptOn: HealthSession {
        HealthSession(start: t("2026-11-12T08:58:00Z"), end: t("2026-11-12T10:56:00Z"),
                      stages: [span("08:58", "09:24", .awake), span("09:24", "10:56", .light)], source: samsung)
    }

    /// Echtes Nickerchen 14:00–15:00 Ortszeit, über eine Stunde nach dem Ende der Nacht.
    private var nap: HealthSession {
        HealthSession(start: t("2026-11-12T13:00:00Z"), end: t("2026-11-12T14:00:00Z"), source: samsung)
    }

    func testRealNightCountsBothParts() throws {
        // Für sich allein wäre die zweite Sitzung ein Nickerchen (kurz, ganz am Tag).
        XCTAssertTrue(sleptOn.isNap(calendar: calendar))
        let sessions = [nap, sleptOn, night]
        // Verlauf und Detailseite: eine Nacht im Bett 01:36–11:56, Schlaf 7 h 18, Einschlafen um 03:11.
        let items = merge(sessions)
        XCTAssertEqual(items.map(\.kind), [.health])
        XCTAssertEqual(items.map(\.date), ["2026-11-11"])
        let session = try XCTUnwrap(items.first?.session)
        XCTAssertEqual(session.start, night.start)
        XCTAssertEqual(session.end, sleptOn.end)
        XCTAssertEqual(session.durationMinutes, 620)
        XCTAssertEqual(session.asleepMinutes, 438)
        XCTAssertEqual(session.awakeMinutes, 181)
        XCTAssertEqual(session.asleepAt, t("2026-11-12T02:11:00Z"))
        XCTAssertEqual(session.source, samsung)
        XCTAssertEqual(StageStats.forSession(session)?.asleepMinutes, 438)
        // Diagramme, Trends, Schlafziel: 03:11–11:56 (8 h 45 mit der Minute Lücke), Schlaf beider Teile.
        let sleeps = summaries(sessions)
        XCTAssertEqual(sleeps, [SleepSummary(start: t("2026-11-12T02:11:00Z"), end: sleptOn.end, asleepMinutes: 438)])
        XCTAssertEqual(sleeps.first.map { $0.end.timeIntervalSince($0.start) }, TimeInterval(525 * 60))
        let goal = SleepGoal.nightsOf(sleeps, calendar: calendar)
        XCTAssertEqual(goal.map { $0.date }, ["2026-11-11"])
        XCTAssertEqual(goal.map { $0.asleepMinutes }, [438])
        let durations = ChartData.durations(sleeps, calendar: calendar, today: "2026-11-12", goalMinutes: 480)
        XCTAssertEqual(durations?.bars.last?.date, "2026-11-11")
        XCTAssertEqual(durations?.bars.last?.asleepMinutes, 438)
        let bars = ChartData.sleepBars(sleeps, calendar: calendar, today: "2026-11-12", nextTarget: nil, goalMinutes: 480)
        XCTAssertEqual(bars?.bars.last?.endMinute, Int(Regularity.minutesFromNoon(sleptOn.end, calendar: calendar)))
        // Phasen für eigene Nächte: beide Teile, das Nickerchen nicht.
        XCTAssertEqual(HealthHistory.stages(sessions, clearedAt: nil, calendar: calendar), night.stages + sleptOn.stages)
    }

    func testOwnNightIncludesSleepingOn() {
        // Phase Wake weckte um 09:57 (Fenster 09:27–09:57 Ortszeit): Schlafprofil und Werte bis 11:56.
        let own = NightRecord(alarmId: "a", windowStart: t("2026-11-12T08:27:00Z"), target: t("2026-11-12T08:57:00Z"),
                              wokeAt: t("2026-11-12T08:57:00Z"), reason: .lightSleep, snoozes: 0, epochs: [])
        let sessions = [night, sleptOn]
        XCTAssertEqual(merge(sessions, nights: [own]).map(\.kind), [.phaseWake])
        let stages = HealthHistory.stages(sessions, clearedAt: nil, calendar: calendar)
        XCTAssertEqual(ChartData.hypnogram(stages, night: own)?.end, sleptOn.end)
        XCTAssertEqual(StageStats.forNight(stages, night: own)?.asleepMinutes, 438)
    }

    func testRealNapStaysNap() {
        // Mehr als 60 Minuten nach dem Ende der Nacht: Nickerchen, zählt nirgends.
        let late = HealthSession(start: night.end.addingTimeInterval(61 * 60), end: sleptOn.end, source: samsung)
        XCTAssertTrue(late.isNap(calendar: calendar))
        XCTAssertEqual(merge([night, late]).map(\.session), [night])
        XCTAssertEqual(summaries([night, late]).map(\.asleepMinutes), [346])
        XCTAssertEqual(HealthHistory.stages([night, late], clearedAt: nil, calendar: calendar), night.stages)
        // Genau 60 Minuten: setzt die Nacht noch fort (einschliesslich).
        let edge = HealthSession(start: night.end.addingTimeInterval(60 * 60), end: night.end.addingTimeInterval(90 * 60))
        XCTAssertEqual(merge([night, edge]).map(\.session?.end), [edge.end])
        XCTAssertEqual(HealthHistory.continuationGap, 60 * 60)
    }

    func testChainOfShortSessions() {
        // Zwei kurze Sitzungen nacheinander (je höchstens 60 Min. Lücke) gehören beide zur Nacht; ohne Phasen
        // zählt jede über ihre Dauer, die Lücken nicht.
        let second = HealthSession(start: t("2026-11-12T09:30:00Z"), end: t("2026-11-12T10:00:00Z"))
        let third = HealthSession(start: t("2026-11-12T10:45:00Z"), end: t("2026-11-12T11:15:00Z"))
        let items = merge([third, night, second])
        XCTAssertEqual(items.count, 1)
        XCTAssertEqual(items.first?.session?.start, night.start)
        XCTAssertEqual(items.first?.session?.end, third.end)
        XCTAssertEqual(items.first?.session?.asleepMinutes, 346 + 30 + 30)
        XCTAssertEqual(items.first?.session?.stages.count, night.stages.count + 2)
        XCTAssertEqual(summaries([third, night, second]).map(\.asleepMinutes), [406])
        // Ein Nickerchen ohne Nacht davor setzt nichts fort.
        XCTAssertTrue(merge([second, third]).isEmpty)
    }

    func testOverlappingShortSessionIsNoContinuation() {
        // Eine andere App schreibt einen kurzen Teil, der sich mit der Nacht überschneidet: Doppel, zählt nicht.
        let other = HealthSession(start: t("2026-11-12T08:30:00Z"), end: t("2026-11-12T09:30:00Z"), source: "other")
        XCTAssertEqual(merge([night, other]).map(\.session), [night])
        // Zwei Apps schreiben dasselbe Weiterschlafen: nur einmal gezählt.
        let copy = HealthSession(start: sleptOn.start, end: sleptOn.end, source: "other")
        XCTAssertEqual(summaries([night, sleptOn, copy]).map(\.asleepMinutes), [438])
    }

    func testShortOverlapIsStillAContinuation() throws {
        // Audit 2c82e15: Teile oft mit einer Sekunde Überlappung geschrieben – trotzdem Weiterschlafen.
        let from = night.end.addingTimeInterval(-1)
        let overlapping = HealthSession(start: from, end: sleptOn.end,
                                        stages: [StageSpan(start: from, end: t("2026-11-12T09:24:00Z"), stage: .awake), span("09:24", "10:56", .light)],
                                        source: samsung)
        let session = try XCTUnwrap(merge([night, overlapping]).first?.session)
        XCTAssertEqual(merge([night, overlapping]).count, 1)
        XCTAssertEqual(session.start, night.start)
        XCTAssertEqual(session.end, overlapping.end)
        XCTAssertEqual(session.asleepMinutes, 438)
        XCTAssertEqual(summaries([night, overlapping]).map(\.asleepMinutes), [438])
        let long = HealthSession(start: from, end: night.end.addingTimeInterval(4 * 3600), source: samsung)
        XCTAssertEqual(merge([night, long]).first?.session?.end, night.end.addingTimeInterval(4 * 3600))
    }
}
