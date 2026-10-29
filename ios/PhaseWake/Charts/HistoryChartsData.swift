import PhaseWakeCore
import SwiftUI

/// Trends-Zeilen, die ein Diagramm schon zeigt (dort weggelassen). Wie `TrendLine` (Android).
enum TrendLine: Hashable { case asleep, goal, regular, feeling }

/// Diagramme eines Zeitraums (7, 14 oder 30 Nächte); jedes `nil` = in diesem Zeitraum nichts zu zeigen.
struct PeriodCharts {
    var bars: SleepBars?
    var durations: Durations?
    var wake: WakeMoments?
    var feeling: FeelingSeries?
}

/// Daten der Diagramme im Verlauf, je Zeitraum aus ``ChartData/periods`` einmal gerechnet. Ein Diagramm erscheint,
/// wenn der längste Zeitraum etwas zeigt. Wie `HistoryCharts` (Android).
struct HistoryChartsData {
    /// Diagramme je Zeitraum (Schlüssel: Anzahl Nächte).
    var periods: [Int: PeriodCharts] = [:]
    var hypnogram: Hypnogram?
    var hypnogramNight: NightRecord?

    /// Diagramme des Zeitraums `nights`; leer, wenn er nicht gerechnet wurde.
    func period(_ nights: Int) -> PeriodCharts { periods[nights] ?? PeriodCharts() }

    /// Was der längste Zeitraum zeigt: entscheidet, welche Karten erscheinen.
    var longest: PeriodCharts { period(ChartData.monthNights) }

    var isEmpty: Bool {
        let all = longest
        return all.bars == nil && all.durations == nil && all.wake == nil && hypnogram == nil && all.feeling == nil
    }

    /// Was die Trends-Karte nicht mehr wiederholen muss (wie bisher nach den Diagrammen der letzten Woche bzw. 14 Nächte).
    var covered: Set<TrendLine> {
        var out: Set<TrendLine> = []
        if period(ChartData.shortNights).durations != nil { out.formUnion([.asleep, .goal]) }
        if period(ChartData.nights).bars != nil { out.insert(.regular) }
        if period(ChartData.nights).feeling != nil { out.insert(.feeling) }
        return out
    }

    /// Schlafprofil: die jüngste der letzten 14 Uhr-Nächte mit Schlafphasen. Befinden auch zu Nächten aus anderen Apps (`health`).
    static func of(nights: [NightRecord], sleeps: [SleepSummary], stages: [StageSpan], notes: [String: [String]],
                   nextTarget: Date?, goalMinutes: Int, now: Date, calendar: Calendar, health: [HealthFeeling] = []) -> HistoryChartsData {
        let today = SleepNotes.nightDate(now, calendar: calendar)
        let night = stages.isEmpty ? nil : nights.sorted { $0.wokeAt > $1.wokeAt }.prefix(ChartData.nights)
            .first { ChartData.hypnogram(stages, night: $0) != nil }
        var periods: [Int: PeriodCharts] = [:]
        for count in ChartData.periods {
            periods[count] = PeriodCharts(
                bars: ChartData.sleepBars(sleeps, calendar: calendar, today: today, nextTarget: nextTarget, goalMinutes: goalMinutes, nights: count),
                durations: ChartData.durations(sleeps, calendar: calendar, today: today, goalMinutes: goalMinutes, nights: count),
                wake: ChartData.wakeMoments(nights, calendar: calendar, count: count),
                feeling: ChartData.feelingSeries(nights, notes: notes, calendar: calendar, today: today, days: count, health: health)
            )
        }
        return HistoryChartsData(
            periods: periods,
            hypnogram: night.flatMap { ChartData.hypnogram(stages, night: $0) },
            hypnogramNight: night
        )
    }
}
