import PhaseWakeCore
import SwiftUI

/// Trends-Zeilen, die ein Diagramm schon zeigt (dort weggelassen). Wie `TrendLine` (Android).
enum TrendLine: Hashable { case asleep, goal, regular, feeling }

/// Diagramme eines Zeitraums (7, 14 oder 30 Nächte); jedes `nil` = in diesem Zeitraum nichts zu zeigen.
/// Die Schlafdauer pro Nacht zeigt die Übersicht «Dein Schlaf» (``SleepOverview``).
struct PeriodCharts {
    var bars: SleepBars?
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
    /// Übersicht «Tage / Wochen / Monate» oben im Verlauf; Einteilungen ohne Daten fehlen.
    var overview: [OverviewUnit: SleepOverview] = [:]

    /// Diagramme des Zeitraums `nights`; leer, wenn er nicht gerechnet wurde.
    func period(_ nights: Int) -> PeriodCharts { periods[nights] ?? PeriodCharts() }

    /// Was der längste Zeitraum zeigt: entscheidet, welche Karten erscheinen.
    var longest: PeriodCharts { period(ChartData.monthNights) }

    /// Ob die Diagramme zum Blättern nichts zeigen (die Übersicht steht für sich).
    var isEmpty: Bool {
        let all = longest
        return all.bars == nil && all.wake == nil && hypnogram == nil && all.feeling == nil
    }

    /// Zeitraum der Diagramme zum Blättern je Einteilung oben im Verlauf: «Tage» 7, «Wochen» 14, «Monate» 30 Nächte;
    /// Diagramme je Nacht (Schlafzeiten, Weckmomente, Befinden) bleiben so lesbar. Wie `periodNights` (Android).
    static func nights(_ unit: OverviewUnit) -> Int {
        switch unit {
        case .days: ChartData.shortNights
        case .weeks: ChartData.nights
        case .months: ChartData.monthNights
        }
    }

    /// Was die Trends-Karte nicht mehr wiederholen muss (wie bisher nach den Diagrammen der letzten Woche bzw. 14 Nächte).
    var covered: Set<TrendLine> {
        var out: Set<TrendLine> = []
        // Ø Schlaf und erreichtes Ziel zeigt die Übersicht («Tage»: die letzten 7 Nächte), sobald sie dort Daten hat.
        if overview[.days] != nil { out.formUnion([.asleep, .goal]) }
        if period(ChartData.nights).bars != nil { out.insert(.regular) }
        if period(ChartData.nights).feeling != nil { out.insert(.feeling) }
        return out
    }

    /// Schlafprofil: die jüngste der letzten 14 Uhr-Nächte mit Schlafphasen. Befinden auch zu Nächten aus anderen Apps (`health`).
    /// `firstDay`: erster Tag der Woche für die Übersicht (Sprache des Geräts).
    static func of(nights: [NightRecord], sleeps: [SleepSummary], stages: [StageSpan], notes: [String: [String]],
                   nextTarget: Date?, goalMinutes: Int, now: Date, calendar: Calendar, health: [HealthFeeling] = [],
                   firstDay: Weekday = .monday) -> HistoryChartsData {
        let today = SleepNotes.nightDate(now, calendar: calendar)
        let night = stages.isEmpty ? nil : nights.sorted { $0.wokeAt > $1.wokeAt }.prefix(ChartData.nights)
            .first { ChartData.hypnogram(stages, night: $0) != nil }
        var periods: [Int: PeriodCharts] = [:]
        for count in ChartData.periods {
            periods[count] = PeriodCharts(
                bars: ChartData.sleepBars(sleeps, calendar: calendar, today: today, nextTarget: nextTarget, goalMinutes: goalMinutes, nights: count),
                wake: ChartData.wakeMoments(nights, calendar: calendar, count: count),
                feeling: ChartData.feelingSeries(nights, notes: notes, calendar: calendar, today: today, days: count, health: health)
            )
        }
        var overview: [OverviewUnit: SleepOverview] = [:]
        for unit in OverviewUnit.allCases {
            overview[unit] = SleepOverviews.of(sleeps, calendar: calendar, today: today, goalMinutes: goalMinutes, unit: unit,
                                               firstDay: firstDay, stages: stages)
        }
        return HistoryChartsData(
            periods: periods,
            hypnogram: night.flatMap { ChartData.hypnogram(stages, night: $0) },
            hypnogramNight: night,
            overview: overview
        )
    }
}
