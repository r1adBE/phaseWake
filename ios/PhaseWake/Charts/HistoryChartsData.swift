import PhaseWakeCore
import SwiftUI

/// Trends-Zeilen, die ein Diagramm schon zeigt (dort weggelassen). Wie `TrendLine` (Android).
enum TrendLine: Hashable { case asleep, goal, regular, feeling }

/// Daten der Diagramme im Verlauf; jedes `nil` = Diagramm verborgen. Wie `HistoryCharts` (Android).
struct HistoryChartsData {
    var bars: SleepBars?
    var week: Durations?
    var twoWeeks: Durations?
    var wake: WakeMoments?
    var hypnogram: Hypnogram?
    var hypnogramNight: NightRecord?
    var feeling: FeelingSeries?

    var isEmpty: Bool { bars == nil && twoWeeks == nil && wake == nil && hypnogram == nil && feeling == nil }

    /// Was die Trends-Karte nicht mehr wiederholen muss.
    var covered: Set<TrendLine> {
        var out: Set<TrendLine> = []
        if week != nil { out.formUnion([.asleep, .goal]) }
        if bars != nil { out.insert(.regular) }
        if feeling != nil { out.insert(.feeling) }
        return out
    }

    /// Schlafprofil: die jüngste der letzten 14 Uhr-Nächte mit Schlafphasen.
    static func of(nights: [NightRecord], sleeps: [SleepSummary], stages: [StageSpan], notes: [String: [String]],
                   nextTarget: Date?, goalMinutes: Int, now: Date, calendar: Calendar) -> HistoryChartsData {
        let today = SleepNotes.nightDate(now, calendar: calendar)
        let night = stages.isEmpty ? nil : nights.sorted { $0.wokeAt > $1.wokeAt }.prefix(ChartData.nights)
            .first { ChartData.hypnogram(stages, night: $0) != nil }
        return HistoryChartsData(
            bars: ChartData.sleepBars(sleeps, calendar: calendar, today: today, nextTarget: nextTarget, goalMinutes: goalMinutes),
            week: ChartData.durations(sleeps, calendar: calendar, today: today, goalMinutes: goalMinutes, nights: ChartData.shortNights),
            twoWeeks: ChartData.durations(sleeps, calendar: calendar, today: today, goalMinutes: goalMinutes, nights: ChartData.nights),
            wake: ChartData.wakeMoments(nights, calendar: calendar),
            hypnogram: night.flatMap { ChartData.hypnogram(stages, night: $0) },
            hypnogramNight: night,
            feeling: ChartData.feelingSeries(nights, notes: notes, calendar: calendar, today: today)
        )
    }
}
