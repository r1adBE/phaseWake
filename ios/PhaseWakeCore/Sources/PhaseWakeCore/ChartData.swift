import Foundation

/// Ein Abend im Schlaf-Streifen: Einschlafen bis Aufwachen als Uhrzeit in Minuten ab Mittag (wie
/// `Regularity.minutesFromNoon`; `endMinute` liegt über 1440, wenn bis nach Mittag geschlafen wurde).
/// Ohne Daten beides `nil`. Identisch zu `SleepBar` (Kotlin).
public struct SleepBar: Equatable, Sendable {
    public let date: String
    public let startMinute: Int?
    public let endMinute: Int?

    public init(date: String, startMinute: Int?, endMinute: Int?) {
        self.date = date
        self.startMinute = startMinute
        self.endMinute = endMinute
    }

    public var hasData: Bool { startMinute != nil && endMinute != nil }
}

/// Schlaf-Streifen über `ChartData.nights` Nachtdaten, älteste zuerst. Achse in Minuten ab Mittag (ganze
/// Stunden); `bedtimeMinute` = Bettzeit vor dem nächsten Wecker (`Bedtime.bedtime`), `nil` ohne Wecker;
/// `regular` von `regularCounted` laut `Regularity.count`.
public struct SleepBars: Equatable, Sendable {
    public let bars: [SleepBar]
    public let axisStart: Int
    public let axisEnd: Int
    public let bedtimeMinute: Int?
    public let regular: Int
    public let regularCounted: Int
}

/// Schlafdauer eines Nachtdatums; `nil` ohne Daten.
public struct DurationBar: Equatable, Sendable {
    public let date: String
    public let asleepMinutes: Int?
    public let reached: Bool

    public init(date: String, asleepMinutes: Int?, reached: Bool) {
        self.date = date
        self.asleepMinutes = asleepMinutes
        self.reached = reached
    }
}

/// Schlafdauer pro Nacht, älteste zuerst. `averageMinutes` = Durchschnitt der Nächte mit Daten (ganze
/// Minuten, abgerundet); `reached` von `counted`; `scaleMinutes` = das Grössere von Ziel und längster Nacht.
public struct Durations: Equatable, Sendable {
    public let bars: [DurationBar]
    public let goalMinutes: Int
    public let averageMinutes: Int
    public let reached: Int
    public let counted: Int
    public let scaleMinutes: Int
}

/// Wodurch geweckt wurde, für Farbe und Form des Punkts.
public enum WakeKind: String, Sendable {
    case light = "LIGHT", awake = "AWAKE", target = "TARGET", late = "LATE"
}

/// Weckmoment einer Uhr-Nacht: `minutesBefore` höchstens `windowMinutes` (0 = zur Zielzeit oder später),
/// `minutesAfter` nach Schlummern.
public struct WakeMoment: Equatable, Sendable {
    public let date: String
    public let wokeAt: Date
    public let minutesBefore: Int
    public let minutesAfter: Int
    public let windowMinutes: Int
    public let kind: WakeKind
    public let reason: WakeReason
}

/// Weckmomente der letzten Uhr-Nächte, älteste zuerst. `scaleMinutes` = längstes Weckfenster,
/// `averageBefore` = Durchschnitt von `minutesBefore`, gerundet.
public struct WakeMoments: Equatable, Sendable {
    public let points: [WakeMoment]
    public let scaleMinutes: Int
    public let averageBefore: Int
}

/// Eine Stufe im Schlafprofil, in Minuten ab `Hypnogram.start`.
public struct HypnoStep: Equatable, Sendable {
    public let fromMinute: Double
    public let toMinute: Double
    public let stage: SleepStage

    public init(fromMinute: Double, toMinute: Double, stage: SleepStage) {
        self.fromMinute = fromMinute
        self.toMinute = toMinute
        self.stage = stage
    }
}

/// Schlafprofil einer Nacht aus der Gesundheits-App mit Weckfenster und Weckmoment (alles in Minuten ab
/// `start`). `stageAtWake` = Phase beim Wecken (`StageCheck.stageAtWake`). Identisch zu Kotlin.
public struct Hypnogram: Equatable, Sendable {
    public let start: Date
    public let end: Date
    public let steps: [HypnoStep]
    public let windowFrom: Double
    public let windowTo: Double
    public let wokeAt: Double
    public let stageAtWake: SleepStage?

    public var totalMinutes: Double { end.timeIntervalSince(start) / 60 }

    /// Minuten je Phase, ganze Minuten abgerundet.
    public func minutesOf(_ stage: SleepStage) -> Int {
        Int(steps.filter { $0.stage == stage }.reduce(0) { $0 + $1.toMinute - $1.fromMinute })
    }
}

/// Befinden eines Nachtdatums (1–5 oder `nil`) und die Notizen dieser Nacht (Kennungen).
public struct FeelingPoint: Equatable, Sendable {
    public let date: String
    public let feeling: Int?
    public let notes: [String]

    public init(date: String, feeling: Int?, notes: [String]) {
        self.date = date
        self.feeling = feeling
        self.notes = notes
    }
}

/// Befinden über `ChartData.nights` Nachtdaten, älteste zuerst; `thisWeek` = Durchschnitt der jüngsten 7,
/// `lastWeek` der 7 davor (`nil` ohne Angabe).
public struct FeelingSeries: Equatable, Sendable {
    public let points: [FeelingPoint]
    public let thisWeek: Double?
    public let lastWeek: Double?
}

/// Daten für die Diagramme im Verlauf: reine Rechnung, die Plattformen zeichnen nur. Jede Funktion gibt
/// `nil` zurück, wenn es nichts zu zeigen gibt (das Diagramm bleibt dann verborgen). Nachtdaten wie bei
/// den Notizen (`SleepNotes.nightDate`). Identisch zu `ChartData` (Kotlin).
public enum ChartData {
    public static let nights = 14
    public static let shortNights = 7
    /// Standard-Zeitachse 21:00–11:00 als Minuten ab Mittag.
    public static let axisStart = 9 * 60
    public static let axisEnd = 23 * 60

    /// Stufe im Schlafprofil von oben nach unten: Wach 0, REM 1, Leicht 2, Tief 3; unbekannt `nil`.
    public static func level(_ stage: SleepStage) -> Int? {
        switch stage {
        case .awake: 0
        case .rem: 1
        case .light: 2
        case .deep: 3
        case .unknown: nil
        }
    }

    /// Jüngstes Nachtdatum der Diagramme: `today` (`SleepNotes.nightDate` von «jetzt»), wenn es dafür schon
    /// Daten gibt, sonst die Nacht davor (am Abend ist die kommende Nacht noch leer).
    public static func lastDate(_ dates: Set<String>, today: String) -> String {
        dates.contains(today) ? today : NightDate.adding(-1, to: today)
    }

    private static func range(_ last: String, _ count: Int) -> [String] {
        (0..<count).reversed().map { NightDate.adding(-$0, to: last) }
    }

    /// Schlaf-Streifen der `nights` Nachtdaten bis `lastDate`. Je Nachtdatum die längste Nacht der
    /// Gesundheits-App. Achse 21:00–11:00, erweitert auf ganze Stunden, damit alle Balken und die Bettzeit
    /// Platz haben (höchstens 24 Stunden). `nextTarget` = Zielzeit des nächsten Weckers für die Bettzeit.
    public static func sleepBars(_ sleeps: [SleepSummary], calendar: Calendar, today: String, nextTarget: Date?,
                                 goalMinutes: Int, nights: Int = ChartData.nights) -> SleepBars? {
        var longest: [String: SleepSummary] = [:]
        for sleep in sleeps {
            let date = SleepNotes.nightDate(sleep.end, calendar: calendar)
            // Wie `maxBy` in Kotlin: bei Gleichstand der erste.
            if let current = longest[date], current.asleepMinutes >= sleep.asleepMinutes { continue }
            longest[date] = sleep
        }
        let last = lastDate(Set(longest.keys), today: today)
        let bars = range(last, nights).map { date -> SleepBar in
            guard let sleep = longest[date] else { return SleepBar(date: date, startMinute: nil, endMinute: nil) }
            // Uhrzeiten (nicht Dauer), damit eine Zeitumstellung den Balken nicht verschiebt.
            let start = Int(Regularity.minutesFromNoon(sleep.start, calendar: calendar))
            let end = Int(Regularity.minutesFromNoon(sleep.end, calendar: calendar))
            return SleepBar(date: date, startMinute: start, endMinute: end <= start ? end + 24 * 60 : end)
        }
        guard bars.contains(where: \.hasData) else { return nil }
        let bedtime = nextTarget.map { Int(Regularity.minutesFromNoon(Bedtime.bedtime(nextAlarmTarget: $0, goalMinutes: goalMinutes), calendar: calendar)) }
        let starts = bars.compactMap(\.startMinute) + (bedtime.map { [$0] } ?? [])
        let ends = bars.compactMap(\.endMinute) + (bedtime.map { [$0] } ?? [])
        let lo = min(axisStart, starts.min()! / 60 * 60)
        let hi = min(max(axisEnd, (ends.max()! + 59) / 60 * 60), lo + 24 * 60)
        let regular = Regularity.count(Regularity.bedtimes(of: sleeps, calendar: calendar), calendar: calendar, until: last)
        return SleepBars(bars: bars, axisStart: lo, axisEnd: hi, bedtimeMinute: bedtime, regular: regular.regular, regularCounted: regular.total)
    }

    /// Schlafdauer der `nights` Nachtdaten bis `lastDate` (je Nachtdatum die längste Nacht).
    public static func durations(_ sleeps: [SleepSummary], calendar: Calendar, today: String, goalMinutes: Int,
                                 nights: Int = ChartData.shortNights) -> Durations? {
        let asleep = NoteEffect.asleepByNight(sleeps, calendar: calendar)
        let last = lastDate(Set(asleep.keys), today: today)
        let bars = range(last, nights).map { date in
            let minutes = asleep[date]
            return DurationBar(date: date, asleepMinutes: minutes, reached: minutes.map { SleepGoal.reached(asleepMinutes: $0, goalMinutes: goalMinutes) } ?? false)
        }
        let values = bars.compactMap(\.asleepMinutes)
        guard let longest = values.max() else { return nil }
        return Durations(
            bars: bars,
            goalMinutes: goalMinutes,
            averageMinutes: values.reduce(0, +) / values.count,
            reached: bars.filter(\.reached).count,
            counted: values.count,
            scaleMinutes: max(goalMinutes, longest)
        )
    }

    /// Weckmomente der jüngsten `count` Uhr-Nächte (nach Weckzeit).
    public static func wakeMoments(_ nights: [NightRecord], calendar: Calendar, count: Int = ChartData.nights) -> WakeMoments? {
        let recent = nights.sorted { $0.wokeAt < $1.wokeAt }.suffix(count)
        guard !recent.isEmpty else { return nil }
        let points = recent.map { night -> WakeMoment in
            let window = max(0, Int(night.target.timeIntervalSince(night.windowStart) / 60))
            let after = night.minutesAfterTarget
            let kind: WakeKind
            if after > 0 {
                kind = .late
            } else {
                switch night.reason {
                case .lightSleep: kind = .light
                case .awake: kind = .awake
                case .deadline: kind = .target
                }
            }
            return WakeMoment(date: SleepNotes.nightDate(of: night, calendar: calendar), wokeAt: night.wokeAt,
                              minutesBefore: min(night.minutesBeforeTarget, window), minutesAfter: after,
                              windowMinutes: window, kind: kind, reason: night.reason)
        }
        // Wie `Math.round` in Kotlin (nur positive Werte).
        let average = Int((Double(points.reduce(0) { $0 + $1.minutesBefore }) / Double(points.count)).rounded())
        return WakeMoments(points: points, scaleMinutes: max(1, points.map(\.windowMinutes).max() ?? 1), averageBefore: average)
    }

    /// Schlafprofil der Nacht von `night`: die Phasen der Gesundheits-App, die mit Lücken unter
    /// `SleepSummary.newNightGap` zusammenhängen und das Weckfenster bis zum Wecken berühren. Der Bereich
    /// reicht mindestens über Weckfenster und Weckmoment. `nil` ohne bekannte Phasen.
    public static func hypnogram(_ stages: [StageSpan], night: NightRecord) -> Hypnogram? {
        var groups: [[StageSpan]] = []
        for span in stages.sorted(by: { $0.start < $1.start }) {
            if let group = groups.last, let groupEnd = group.map(\.end).max(), span.start.timeIntervalSince(groupEnd) < SleepSummary.newNightGap {
                groups[groups.count - 1].append(span)
            } else {
                groups.append([span])
            }
        }
        let until = max(night.wokeAt, night.target)
        guard let group = groups.last(where: { g in g.map(\.start).min()! <= until && g.map(\.end).max()! >= night.windowStart }) else { return nil }
        let known = group.filter { level($0.stage) != nil }
        guard !known.isEmpty else { return nil }
        let start = min(group.map(\.start).min()!, night.windowStart)
        let end = max(group.map(\.end).max()!, until)
        func minutes(_ at: Date) -> Double { at.timeIntervalSince(start) / 60 }
        return Hypnogram(
            start: start,
            end: end,
            steps: known.map { HypnoStep(fromMinute: minutes($0.start), toMinute: minutes($0.end), stage: $0.stage) },
            windowFrom: minutes(night.windowStart),
            windowTo: minutes(night.target),
            wokeAt: minutes(night.wokeAt),
            stageAtWake: StageCheck.stageAtWake(group, wokeAt: night.wokeAt)
        )
    }

    /// Befinden der `days` Nachtdaten bis `lastDate`: je Nachtdatum das der zuletzt geweckten Nacht (wie bei
    /// den Notizen), dazu die Notizen. `nil`, wenn in dieser Zeit kein Befinden angegeben ist.
    public static func feelingSeries(_ nights: [NightRecord], notes: [String: [String]], calendar: Calendar, today: String,
                                     days: Int = ChartData.nights) -> FeelingSeries? {
        var latest: [String: NightRecord] = [:]
        for night in nights where night.feeling != nil {
            let date = SleepNotes.nightDate(of: night, calendar: calendar)
            if let current = latest[date], current.wokeAt >= night.wokeAt { continue }
            latest[date] = night
        }
        let last = lastDate(Set(latest.keys), today: today)
        let points = range(last, days).map { FeelingPoint(date: $0, feeling: latest[$0]?.feeling, notes: notes[$0] ?? []) }
        guard points.contains(where: { $0.feeling != nil }) else { return nil }
        func average(_ list: ArraySlice<FeelingPoint>) -> Double? {
            let values = list.compactMap(\.feeling)
            return values.isEmpty ? nil : Double(values.reduce(0, +)) / Double(values.count)
        }
        return FeelingSeries(
            points: points,
            thisWeek: average(points.suffix(shortNights)),
            lastWeek: average(points.dropLast(shortNights).suffix(shortNights))
        )
    }
}
