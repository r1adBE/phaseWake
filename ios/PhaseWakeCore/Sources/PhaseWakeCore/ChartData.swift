import Foundation

/// Ein Abend im Schlaf-Streifen: Einschlafen bis Aufwachen als Uhrzeit in Minuten ab Mittag (wie
/// `Regularity.minutesFromNoon`; `endMinute` liegt über 1440, wenn bis nach Mittag geschlafen wurde).
/// Ohne Daten beides `nil`. Identisch zu `SleepBar` (Kotlin).
public struct SleepBar: Equatable, Sendable {
    /// Nachtdatum «yyyy-MM-dd» (`SleepNotes.nightDate`).
    public let date: String
    /// Einschlafen in Minuten ab Mittag (0–1439); `nil` ohne Nacht an diesem Datum.
    public let startMinute: Int?
    /// Aufwachen in Minuten ab Mittag; bei Schlaf über Mittag hinaus über 1440, damit der Balken nach rechts weiterläuft.
    public let endMinute: Int?

    /// Erzeugt einen Balken; ohne Daten beide Minuten `nil`.
    public init(date: String, startMinute: Int?, endMinute: Int?) {
        self.date = date
        self.startMinute = startMinute
        self.endMinute = endMinute
    }

    /// Ob der Balken gezeichnet wird: nur mit Beginn und Ende.
    public var hasData: Bool { startMinute != nil && endMinute != nil }
}

/// Schlaf-Streifen über `ChartData.nights` Nachtdaten, älteste zuerst. Achse in Minuten ab Mittag (ganze
/// Stunden); `bedtimeMinute` = Bettzeit vor dem nächsten Wecker (`Bedtime.bedtime`), `nil` ohne Wecker;
/// `regular` von `regularCounted` laut `Regularity.count`.
public struct SleepBars: Equatable, Sendable {
    /// Ein Balken je Nachtdatum, älteste zuerst, auch für Nächte ohne Daten (Lücke im Streifen).
    public let bars: [SleepBar]
    /// Linker Achsenrand in Minuten ab Mittag, auf ganze Stunden abgerundet (höchstens 21:00).
    public let axisStart: Int
    /// Rechter Achsenrand in Minuten ab Mittag, auf ganze Stunden aufgerundet (mindestens 11:00, höchstens 24 Stunden nach `axisStart`).
    public let axisEnd: Int
    /// Bettzeit vor dem nächsten Wecker in Minuten ab Mittag; `nil` ohne aktiven Wecker.
    public let bedtimeMinute: Int?
    /// Regelmässige Abende der jüngsten Woche (siehe `Regularity.count`).
    public let regular: Int
    /// Gezählte Abende der jüngsten Woche; 0 ohne Gesundheitsdaten.
    public let regularCounted: Int
}

/// Schlafdauer eines Nachtdatums; `nil` ohne Daten.
public struct DurationBar: Equatable, Sendable {
    /// Nachtdatum «yyyy-MM-dd».
    public let date: String
    /// Geschlafene Minuten der längsten Nacht dieses Datums; `nil` ohne Daten.
    public let asleepMinutes: Int?
    /// Ob das Schlafziel erreicht ist (`SleepGoal.reached`); ohne Daten `false`.
    public let reached: Bool

    /// Erzeugt einen Balken; ohne Daten `asleepMinutes` `nil` und `reached` `false`.
    public init(date: String, asleepMinutes: Int?, reached: Bool) {
        self.date = date
        self.asleepMinutes = asleepMinutes
        self.reached = reached
    }
}

/// Schlafdauer pro Nacht, älteste zuerst. `averageMinutes` = Durchschnitt der Nächte mit Daten (ganze
/// Minuten, abgerundet); `reached` von `counted`; `scaleMinutes` = das Grössere von Ziel und längster Nacht.
public struct Durations: Equatable, Sendable {
    /// Ein Balken je Nachtdatum, älteste zuerst, auch ohne Daten.
    public let bars: [DurationBar]
    /// Schlafziel in Minuten, für die Ziellinie.
    public let goalMinutes: Int
    /// Durchschnitt der Nächte mit Daten in ganzen Minuten, abgerundet (ganzzahlig wie Kotlin).
    public let averageMinutes: Int
    /// Nächte mit erreichtem Ziel.
    public let reached: Int
    /// Nächte mit Daten; Nenner zu `reached`.
    public let counted: Int
    /// Höhe der Skala in Minuten: das Grössere von Ziel und längster Nacht, damit beide Platz haben.
    public let scaleMinutes: Int
}

/// Wodurch geweckt wurde, für Farbe und Form des Punkts.
public enum WakeKind: String, Sendable {
    case light = "LIGHT", awake = "AWAKE", target = "TARGET", late = "LATE"
}

/// Weckmoment einer Uhr-Nacht: `minutesBefore` höchstens `windowMinutes` (0 = zur Zielzeit oder später),
/// `minutesAfter` nach Schlummern.
public struct WakeMoment: Equatable, Sendable {
    /// Nachtdatum der Uhr-Nacht (`SleepNotes.nightDate(of:)`).
    public let date: String
    /// Zeitpunkt des Weckens.
    public let wokeAt: Date
    /// Minuten vor der Zielzeit, auf `windowMinutes` begrenzt; 0 zur Zielzeit oder später.
    public let minutesBefore: Int
    /// Minuten nach der Zielzeit (nach Schlummern); sonst 0.
    public let minutesAfter: Int
    /// Länge des Weckfensters dieser Nacht in Minuten (nie negativ).
    public let windowMinutes: Int
    /// Art des Punkts; `late` hat Vorrang vor dem Weckgrund.
    public let kind: WakeKind
    /// Gespeicherter Weckgrund der Nacht.
    public let reason: WakeReason
}

/// Weckmomente der letzten Uhr-Nächte, älteste zuerst. `scaleMinutes` = längstes Weckfenster,
/// `averageBefore` = Durchschnitt von `minutesBefore`, gerundet.
public struct WakeMoments: Equatable, Sendable {
    /// Weckmomente, älteste zuerst.
    public let points: [WakeMoment]
    /// Längstes Weckfenster in Minuten, mindestens 1, damit die Skala nie durch 0 teilt.
    public let scaleMinutes: Int
    /// Durchschnitt von `minutesBefore` in Minuten, kaufmännisch gerundet.
    public let averageBefore: Int
}

/// Eine Stufe im Schlafprofil, in Minuten ab `Hypnogram.start`.
public struct HypnoStep: Equatable, Sendable {
    /// Beginn in Minuten ab `Hypnogram.start`.
    public let fromMinute: Double
    /// Ende in Minuten ab `Hypnogram.start`.
    public let toMinute: Double
    /// Schlafphase der Stufe; nie `.unknown` (unbekannte Abschnitte fallen weg).
    public let stage: SleepStage

    /// Erzeugt eine Stufe; Minuten ab `Hypnogram.start`.
    public init(fromMinute: Double, toMinute: Double, stage: SleepStage) {
        self.fromMinute = fromMinute
        self.toMinute = toMinute
        self.stage = stage
    }
}

/// Schlafprofil einer Nacht aus der Gesundheits-App mit Weckfenster und Weckmoment (alles in Minuten ab
/// `start`). `stageAtWake` = Phase beim Wecken (`StageCheck.stageAtWake`). Identisch zu Kotlin.
public struct Hypnogram: Equatable, Sendable {
    /// Beginn des gezeigten Bereichs (früheste Phase oder Fensterbeginn).
    public let start: Date
    /// Ende des gezeigten Bereichs (späteste Phase oder Weckmoment bzw. Zielzeit).
    public let end: Date
    /// Bekannte Phasen in zeitlicher Reihenfolge.
    public let steps: [HypnoStep]
    /// Beginn des Weckfensters in Minuten ab `start`.
    public let windowFrom: Double
    /// Zielzeit (Ende des Weckfensters) in Minuten ab `start`.
    public let windowTo: Double
    /// Weckmoment in Minuten ab `start`.
    public let wokeAt: Double
    /// Phase beim Wecken; `nil`, wenn die Gesundheits-App für diesen Moment keine Phase kennt.
    public let stageAtWake: SleepStage?

    /// Länge des gezeigten Bereichs in Minuten.
    public var totalMinutes: Double { end.timeIntervalSince(start) / 60 }

    /// Minuten je Phase, ganze Minuten abgerundet.
    public func minutesOf(_ stage: SleepStage) -> Int {
        Int(steps.filter { $0.stage == stage }.reduce(0) { $0 + $1.toMinute - $1.fromMinute })
    }
}

/// Befinden eines Nachtdatums (1–5 oder `nil`) und die Notizen dieser Nacht (Kennungen).
public struct FeelingPoint: Equatable, Sendable {
    /// Nachtdatum «yyyy-MM-dd».
    public let date: String
    /// Befinden 1–5 der zuletzt geweckten Nacht dieses Datums; `nil` ohne Angabe.
    public let feeling: Int?
    /// Kennungen der Notizen dieser Nacht; leer ohne Notizen.
    public let notes: [String]

    /// Erzeugt einen Punkt; ohne Angabe `feeling` `nil`.
    public init(date: String, feeling: Int?, notes: [String]) {
        self.date = date
        self.feeling = feeling
        self.notes = notes
    }
}

/// Befinden über `ChartData.nights` Nachtdaten, älteste zuerst; `thisWeek` = Durchschnitt der jüngsten 7,
/// `lastWeek` der 7 davor (`nil` ohne Angabe).
public struct FeelingSeries: Equatable, Sendable {
    /// Ein Punkt je Nachtdatum, älteste zuerst, auch ohne Befinden.
    public let points: [FeelingPoint]
    /// Durchschnitt der jüngsten 7 Nachtdaten (1–5); `nil` ohne Angabe.
    public let thisWeek: Double?
    /// Durchschnitt der 7 Nachtdaten davor (1–5); `nil` ohne Angabe.
    public let lastWeek: Double?
}

/// Daten für die Diagramme im Verlauf: reine Rechnung, die Plattformen zeichnen nur. Jede Funktion gibt
/// `nil` zurück, wenn es nichts zu zeigen gibt (das Diagramm bleibt dann verborgen). Nachtdaten wie bei
/// den Notizen (`SleepNotes.nightDate`). Identisch zu `ChartData` (Kotlin).
public enum ChartData {
    /// Nachtdaten im Schlaf-Streifen, bei den Weckmomenten und beim Befinden: zwei Wochen.
    public static let nights = 14
    /// Nachtdaten bei der Schlafdauer und für einen Wochendurchschnitt: eine Woche.
    public static let shortNights = 7
    /// Standard-Zeitachse 21:00–11:00 als Minuten ab Mittag.
    public static let axisStart = 9 * 60
    /// Ende der Standard-Zeitachse (11:00) als Minuten ab Mittag.
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
