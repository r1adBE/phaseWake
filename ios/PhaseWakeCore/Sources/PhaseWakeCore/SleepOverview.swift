import Foundation

/// Einteilung der Übersicht im Verlauf: je Säule eine Nacht, eine Woche oder ein Monat. Identisch zu `OverviewUnit` (Kotlin).
public enum OverviewUnit: String, CaseIterable, Sendable {
    case days = "DAYS"
    case weeks = "WEEKS"
    case months = "MONTHS"

    /// So viele Säulen zeigt die Übersicht.
    public var count: Int {
        switch self {
        case .days: 7
        case .weeks: 8
        case .months: 4
        }
    }
}

/// Eine Säule der Übersicht über die Nachtdaten `first` bis `last` («yyyy-MM-dd», eingeschlossen; bei ``OverviewUnit/days``
/// gleich). Wochen und Monate richten sich nach dem Morgen (Nachtdatum + 1 Tag), wie die Daten im Verlauf.
/// Identisch zu `OverviewBar` (Kotlin).
public struct OverviewBar: Equatable, Sendable {
    /// Erstes Nachtdatum der Säule.
    public let first: String
    /// Letztes Nachtdatum der Säule (eingeschlossen).
    public let last: String
    /// Durchschnittlicher Schlaf der Nächte mit Daten (ganze Minuten, abgerundet); `nil` ohne Daten.
    public let asleepMinutes: Int?
    /// Nächte mit Daten in dieser Säule.
    public let nights: Int
    /// Ob `asleepMinutes` das Schlafziel erreicht (``SleepGoal/reached(asleepMinutes:goalMinutes:)``).
    public let reached: Bool
    /// Durchschnittliche Minuten je bekannter Phase (wach, REM, leicht, tief) über die Nächte dieser Säule; leer, wenn
    /// eine Nacht der Säule keine Phasen hat (dann zeigt die Säule nur die Dauer, neutral). Für die Aufteilung der Säule.
    public let stageMinutes: [SleepStage: Int]
}

/// Übersicht «Tage / Wochen / Monate»: Säulen, älteste zuerst, und die Durchschnitte aller Nächte im gezeigten Bereich.
/// Uhrzeiten als Minuten ab Mittag (``Regularity/minutesFromNoon(_:calendar:)``). Identisch zu `SleepOverview` (Kotlin).
public struct SleepOverview: Equatable, Sendable {
    /// Einteilung der Säulen.
    public let unit: OverviewUnit
    /// Säulen, älteste zuerst (``OverviewUnit/count`` Stück).
    public let bars: [OverviewBar]
    /// Schlafziel in Minuten, für die Ziellinie.
    public let goalMinutes: Int
    /// Durchschnittlicher Schlaf je Nacht (ganze Minuten, abgerundet).
    public let averageMinutes: Int
    /// Durchschnittliche Einschlafzeit (Beginn des Schlafs), Minuten ab Mittag, gerundet.
    public let bedtimeMinute: Int
    /// Durchschnittliche Aufwachzeit (Ende), Minuten ab Mittag, gerundet.
    public let wakeMinute: Int
    /// Nächte mit Daten im ganzen Bereich.
    public let counted: Int
    /// Davon Nächte mit erreichtem Schlafziel.
    public let reachedNights: Int
    /// Höhe der Skala: das Grössere von Ziel und höchster Säule.
    public let scaleMinutes: Int
}

/// Verlauf des Pulses einer Nacht aus den Epochen einer Messung, in Stücken zu ``bucketEpochs`` Epochen.
/// Identisch zu `PulseCurve` (Kotlin).
public struct PulseCurve: Equatable, Sendable {
    /// Beginn der ersten Epoche.
    public let start: Date
    /// Ende der letzten Epoche.
    public let end: Date
    /// Mittel je Stück in Schlägen pro Minute; `nil` ohne Werte im Stück. Stück `i` beginnt bei `start + i · 5 Min`.
    public let buckets: [Double?]
    /// Mittel aller Epochen mit Puls, gerundet.
    public let average: Int
    /// Kleinstes Stück-Mittel (für die Skala).
    public let low: Double
    /// Grösstes Stück-Mittel (für die Skala).
    public let high: Double

    /// Epochen je Stück: 10 × 30 s = 5 Min, damit die Linie ruhig bleibt.
    public static let bucketEpochs = 10
    /// Mindestens so viele Epochen mit Puls, sonst keine Kurve.
    public static let minValues = 10
    /// Mindestens so viele Stücke mit Puls (6 × 5 Min), sonst keine Kurve: Wenige Punkte gäben eine fast flache, nichtssagende Linie.
    public static let minBuckets = 6
}

/// Daten für die Übersicht «Tage / Wochen / Monate» und den Pulsverlauf: reine Rechnung (die Plattform zeichnet nur).
/// Schlaf je Nachtdatum wie in ``ChartData/sleepBars(_:calendar:today:nextTarget:goalMinutes:nights:)``: die längste
/// Nacht der Gesundheits-App. Identisch zu `SleepOverviews` (Kotlin).
public enum SleepOverviews {
    /// Übersicht der letzten ``OverviewUnit/count`` Nächte, Wochen bzw. Monate bis ``ChartData/lastDate(_:today:)``.
    /// Wochen beginnen mit `firstDay` (Sprache des Geräts). `stages`: Phasen aus der Gesundheits-App
    /// (``HealthHistory/stages(_:clearedAt:calendar:)``) für die Aufteilung der Säulen; leer: keine.
    /// `nil`, wenn im ganzen Bereich keine Nacht Daten hat.
    public static func of(_ sleeps: [SleepSummary], calendar: Calendar, today: String, goalMinutes: Int, unit: OverviewUnit,
                          firstDay: Weekday = .monday, stages: [StageSpan] = []) -> SleepOverview? {
        var longest: [String: SleepSummary] = [:]
        for sleep in sleeps {
            let date = SleepNotes.nightDate(of: sleep, calendar: calendar)
            // Wie `maxBy` in Kotlin: bei Gleichstand die erste.
            if let current = longest[date], current.asleepMinutes >= sleep.asleepMinutes { continue }
            longest[date] = sleep
        }
        let last = ChartData.lastDate(Set(longest.keys), today: today)
        let ranges = self.ranges(last: last, unit: unit, firstDay: firstDay)
        guard let from = ranges.first?.first, let to = ranges.last?.last else { return nil }
        let inRange = longest.filter { $0.key >= from && $0.key <= to }.sorted { $0.key < $1.key }.map(\.value)
        guard !inRange.isEmpty else { return nil }
        let bars = ranges.map { range -> OverviewBar in
            let inBar = longest.filter { $0.key >= range.first && $0.key <= range.last }.sorted { $0.key < $1.key }.map(\.value)
            let nights = inBar.map(\.asleepMinutes)
            let average = nights.isEmpty ? nil : nights.reduce(0, +) / nights.count
            let reached = average.map { SleepGoal.reached(asleepMinutes: $0, goalMinutes: goalMinutes) } ?? false
            return OverviewBar(first: range.first, last: range.last, asleepMinutes: average, nights: nights.count, reached: reached,
                               stageMinutes: stageAverage(inBar, stages: stages))
        }
        let minutes = inRange.map(\.asleepMinutes)
        return SleepOverview(
            unit: unit,
            bars: bars,
            goalMinutes: goalMinutes,
            averageMinutes: minutes.reduce(0, +) / minutes.count,
            bedtimeMinute: roundHalfUp(mean(inRange.map { Regularity.minutesFromNoon($0.start, calendar: calendar) })),
            wakeMinute: roundHalfUp(mean(inRange.map { Regularity.minutesFromNoon($0.end, calendar: calendar) })),
            counted: minutes.count,
            reachedNights: minutes.filter { SleepGoal.reached(asleepMinutes: $0, goalMinutes: goalMinutes) }.count,
            scaleMinutes: max(goalMinutes, bars.map { $0.asleepMinutes ?? 0 }.max() ?? 0)
        )
    }

    /// Durchschnittliche Minuten je bekannter Phase über die Nächte in `sleeps` (Phasen auf die Nacht zugeschnitten,
    /// Millisekunden summiert, dann abgerundet). Leer, wenn `sleeps` leer ist oder eine Nacht keine Phasen hat: Ein
    /// Durchschnitt nur über einen Teil der Nächte passte nicht zur Höhe der Säule (Durchschnitt aller Nächte).
    public static func stageAverage(_ sleeps: [SleepSummary], stages: [StageSpan]) -> [SleepStage: Int] {
        let perNight = sleeps.map { sleep in
            StageCheck.clip(stages, from: sleep.start, to: sleep.end).filter { ChartData.level($0.stage) != nil }
        }
        if perNight.isEmpty || perNight.contains(where: \.isEmpty) { return [:] }
        var out: [SleepStage: Int] = [:]
        for stage in StageStats.order {
            let ms = perNight.reduce(Int64(0)) { sum, spans in
                sum + spans.filter { $0.stage == stage }.reduce(Int64(0)) { $0 + StageStats.millis($1.start, $1.end) }
            }
            out[stage] = StageStats.minutes(ms / Int64(perNight.count))
        }
        return out
    }

    /// Bereiche der Säulen als Nachtdaten (erstes, letztes), älteste zuerst; der letzte enthält `last`.
    public static func ranges(last: String, unit: OverviewUnit, firstDay: Weekday = .monday) -> [(first: String, last: String)] {
        // Wochen und Monate über den Morgen: Nachtdatum + 1 Tag.
        let morning = NightDate.adding(1, to: last)
        return (0..<unit.count).reversed().map { back -> (first: String, last: String) in
            switch unit {
            case .days:
                let day = NightDate.adding(-back, to: last)
                return (first: day, last: day)
            case .weeks:
                let weekday = NightDate.weekday(morning) ?? firstDay
                let sinceFirst = (weekday.rawValue - firstDay.rawValue + 7) % 7
                let start = NightDate.adding(-sinceFirst - 7 * back, to: morning)
                return (first: NightDate.adding(-1, to: start), last: NightDate.adding(5, to: start))
            case .months:
                let start = monthStart(morning, back: back)
                let next = monthStart(morning, back: back - 1)
                return (first: NightDate.adding(-1, to: start), last: NightDate.adding(-2, to: next))
            }
        }
    }

    /// Pulsverlauf aus `epochs` ab `start` (Epoche `i` beginnt bei `start + i · 30 s`). `nil` mit weniger als
    /// ``PulseCurve/minValues`` Epochen bzw. ``PulseCurve/minBuckets`` Stücken mit Puls.
    public static func pulse(_ epochs: [Epoch], start: Date) -> PulseCurve? {
        let values = epochs.compactMap(\.heartRate)
        guard values.count >= PulseCurve.minValues else { return nil }
        let buckets: [Double?] = stride(from: 0, to: epochs.count, by: PulseCurve.bucketEpochs).map { i in
            let chunk = epochs[i..<min(i + PulseCurve.bucketEpochs, epochs.count)].compactMap(\.heartRate)
            return chunk.isEmpty ? nil : mean(chunk)
        }
        let known = buckets.compactMap { $0 }
        guard known.count >= PulseCurve.minBuckets, let low = known.min(), let high = known.max() else { return nil }
        return PulseCurve(
            start: start,
            end: start.addingTimeInterval(Double(epochs.count * DetectorConfig.epochSeconds)),
            buckets: buckets,
            average: roundHalfUp(mean(values)),
            low: low,
            high: high
        )
    }

    /// Erster Tag des Monats von `date` («yyyy-MM-dd»), `back` Monate zurück (negativ: vorwärts).
    private static func monthStart(_ date: String, back: Int) -> String {
        let parts = date.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return date }
        let index = parts[0] * 12 + (parts[1] - 1) - back
        let year = index >= 0 ? index / 12 : (index - 11) / 12
        let month = index - year * 12 + 1
        return String(format: "%04d-%02d-01", year, month)
    }

    private static func mean(_ values: [Double]) -> Double { values.reduce(0, +) / Double(values.count) }

    /// Wie `Math.round` (Kotlin/Java): x.5 aufwärts.
    private static func roundHalfUp(_ value: Double) -> Int { Int((value + 0.5).rounded(.down)) }
}
