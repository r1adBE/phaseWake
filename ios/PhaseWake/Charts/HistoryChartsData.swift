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

    /// Was die Trends-Karte nicht mehr wiederholen muss (wie bisher nach den Diagrammen der letzten Woche bzw. 14
    /// Nächte).
    var covered: Set<TrendLine> {
        var out: Set<TrendLine> = []
        // Ø Schlaf und erreichtes Ziel zeigt die Übersicht («Tage»: die letzten 7 Nächte), sobald sie dort Daten hat.
        if overview[.days] != nil { out.formUnion([.asleep, .goal]) }
        if period(ChartData.nights).bars != nil { out.insert(.regular) }
        if period(ChartData.nights).feeling != nil { out.insert(.feeling) }
        return out
    }

    /// Eingaben von ``of(_:now:calendar:)``: eigene Nächte, Schlaf und Phasen aus Apple Health, Notizen, Befinden
    /// auch zu Nächten aus anderen Apps (`health`). `nextTarget`, `goalMinutes` und `latencyMinutes` (gelernte
    /// Einschlafdauer) ergeben die Bettzeit-Linie; `firstDay`: erster Tag der Woche für die Übersicht. `monthSleeps`:
    /// Nächte aus Apple Health über den ganzen Verlauf für die Übersicht «Monate», deren älteste Säule vor die 90 Tage
    /// von `sleeps` reicht; `nil`: `sleeps`. Wie `ChartSource.monthSleeps` (Android).
    struct Input {
        var nights: [NightRecord] = []
        var sleeps: [SleepSummary] = []
        var monthSleeps: [SleepSummary]?
        var stages: [StageSpan] = []
        var notes: [String: [String]] = [:]
        var health: [HealthFeeling] = []
        var nextTarget: Date?
        var goalMinutes: Int
        var latencyMinutes: Int?
        var firstDay: Weekday = .monday
    }

    /// Schlafprofil: die jüngste der letzten 14 Uhr-Nächte mit Schlafphasen.
    static func of(_ input: Input, now: Date, calendar: Calendar) -> HistoryChartsData {
        let nights = input.nights
        let sleeps = input.sleeps
        let stages = input.stages
        let today = SleepNotes.nightDate(now, calendar: calendar)
        let night = stages.isEmpty ? nil : nights.sorted { $0.wokeAt > $1.wokeAt }.prefix(ChartData.nights)
            .first { ChartData.hypnogram(stages, night: $0) != nil }
        var periods: [Int: PeriodCharts] = [:]
        for count in ChartData.periods {
            periods[count] = PeriodCharts(
                bars: ChartData.sleepBars(sleeps, calendar: calendar, today: today, nextTarget: input.nextTarget,
                                          goalMinutes: input.goalMinutes, nights: count,
                                          latencyMinutes: input.latencyMinutes),
                wake: ChartData.wakeMoments(nights, calendar: calendar, count: count),
                feeling: ChartData.feelingSeries(nights, notes: input.notes, calendar: calendar, today: today,
                                                 days: count, health: input.health)
            )
        }
        // «Monate» mit allen Nächten der gezeigten Monate, «Tage» und «Wochen» wie die übrigen Kennzahlen.
        var overview: [OverviewUnit: SleepOverview] = [:]
        for unit in OverviewUnit.allCases {
            let source = unit == .months ? input.monthSleeps ?? sleeps : sleeps
            overview[unit] = SleepOverviews.of(source, calendar: calendar, today: today, goalMinutes: input.goalMinutes,
                                               unit: unit, firstDay: input.firstDay, stages: stages)
        }
        return HistoryChartsData(
            periods: periods,
            hypnogram: night.flatMap { ChartData.hypnogram(stages, night: $0) },
            hypnogramNight: night,
            overview: overview
        )
    }
}
