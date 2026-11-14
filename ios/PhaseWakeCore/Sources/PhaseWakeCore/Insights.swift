import Foundation

/// Wie gut die Messung einer Nacht war. Identisch zu `DataQuality` (Kotlin).
public enum DataQuality: String, Sendable {
    case full = "FULL", motionOnly = "MOTION_ONLY", partial = "PARTIAL", none = "NONE"
}

/// Identisch zu `NightQuality` (Kotlin).
public enum NightQuality {
    /// Mindestanteil der Epochen mit Bewegung (80 %), damit eine Nacht nicht als lückenhaft gilt.
    public static let completeShare = 0.8
    /// Mindestanteil der Epochen mit Puls (50 %), damit eine Nacht als vollständig gemessen gilt.
    public static let heartShare = 0.5

    /// Bewertet die Messung einer Nacht ohne die Aufwärm-Epochen: `none` ohne Epochen oder ohne jede Bewegung, `partial` unter 80 % Bewegung, sonst `full` mit Puls in mindestens der Hälfte, sonst `motionOnly`.
    public static func of(_ night: NightRecord, warmupEpochs: Int = DetectorConfig.defaultWarmupEpochs) -> DataQuality {
        let epochs = night.epochs.dropFirst(warmupEpochs)
        guard !epochs.isEmpty else { return .none }
        let motion = Double(epochs.filter { $0.motion != nil }.count) / Double(epochs.count)
        let heart = Double(epochs.filter { $0.heartRate != nil }.count) / Double(epochs.count)
        if motion == 0 { return .none }
        if motion < completeShare { return .partial }
        return heart >= heartShare ? .full : .motionOnly
    }
}

/// Eine ganze Nacht laut Gesundheits-App. Identisch zu `SleepSummary` (Kotlin).
public struct SleepSummary: Equatable, Sendable {
    /// Einschlafbeginn: Beginn der ersten Phase, die nicht «wach» ist.
    public let start: Date
    /// Ende der letzten Phase der Nacht (auch «wach»).
    public let end: Date
    /// Geschlafene Minuten (alle Phasen ausser «wach»; Millisekunden summiert, dann auf ganze Minuten abgerundet).
    public let asleepMinutes: Int

    /// Erzeugt eine Nacht; `start` liegt vor `end`.
    public init(start: Date, end: Date, asleepMinutes: Int) {
        self.start = start
        self.end = end
        self.asleepMinutes = asleepMinutes
    }

    /// Lücke zwischen zwei Phasen, die noch zur selben Nacht gehört (einschliesslich); darüber beginnt eine neue. Eine
    /// Regel für «eine Nacht» überall: gleich wie ``HealthHistory/continuationGap`` (60 Min; Entscheid r1AD 14.11.2026,
    /// Audit W-2 – vorher 3 Std.).
    public static let newNightGap: TimeInterval = HealthHistory.continuationGap
    /// Fasst Phasen zu Nächten zusammen (Lücke höchstens ``newNightGap``), älteste zuerst; Strecken ganz ohne Schlaf
    /// fallen weg. Ohne Mindestdauer: Welche Sitzung eine Nacht ist (Nickerchen nur am Tag), entscheidet vorher
    /// `HealthHistory` – wie im Verlauf.
    public static func group(_ spans: [StageSpan]) -> [SleepSummary] {
        var groups: [[StageSpan]] = []
        for span in spans.sorted(by: { $0.start < $1.start }) {
            if let last = groups.last, let lastEnd = last.map(\.end).max(), span.start.timeIntervalSince(lastEnd) <= newNightGap {
                groups[groups.count - 1].append(span)
            } else {
                groups.append([span])
            }
        }
        return groups.compactMap { of($0) }
    }

    /// Eine Nacht aus `spans`: Beginn der ersten Schlafphase bis Ende der letzten Phase, Schlaf aller Phasen ausser
    /// «wach». `nil` ohne Schlaf. Für die Nächte des Verlaufs je Kette (``HealthHistory/summaries(_:clearedAt:calendar:)``).
    public static func of(_ spans: [StageSpan]) -> SleepSummary? {
        let asleep = spans.filter { $0.stage != .awake }
        guard let start = asleep.map(\.start).min(), let end = spans.map(\.end).max() else { return nil }
        // Millisekunden erst summieren, dann abrunden – wie die Detailseite (`HealthSession.asleepMinutes`);
        // je Phase abgerundet fehlten bei vielen Phasen bis zu einer Minute je Phase.
        let minutes = StageStats.minutes(asleep.reduce(Int64(0)) { $0 + StageStats.millis($1.start, $1.end) })
        return SleepSummary(start: start, end: end, asleepMinutes: minutes)
    }
}

/// Wochenvergleich für den Verlauf. Identisch zu `Trends` (Kotlin).
public struct Trends: Equatable, Sendable {
    /// Durchschnittlicher Schlaf der letzten 7 Tage in Minuten; `nil` unter 3 Nächten.
    public let asleepThisWeek: Int?
    /// Durchschnittlicher Schlaf der 7 Tage davor in Minuten; `nil` unter 3 Nächten.
    public let asleepLastWeek: Int?
    /// Mittlere Abweichung der Einschlafzeit vom Mittel der letzten 14 Tage in Minuten; `nil` unter 5 Nächten.
    public let bedtimeSpreadMinutes: Int?
    /// Durchschnittliches Befinden (1–5) der letzten 7 Tage; `nil` unter 3 Angaben.
    public let feelingThisWeek: Double?
    /// Durchschnittliches Befinden (1–5) der 7 Tage davor; `nil` unter 3 Angaben.
    public let feelingLastWeek: Double?

    /// Schlaf dieser minus letzter Woche in Minuten; `nil`, wenn eine Woche fehlt.
    public var asleepChange: Int? {
        guard let a = asleepThisWeek, let b = asleepLastWeek else { return nil }
        return a - b
    }

    /// Mindestens so viele Nächte je Woche für einen Durchschnitt; weniger wäre Zufall.
    public static let minNightsPerWeek = 3
    /// Mindestens so viele Nächte für die Streuung der Einschlafzeit.
    public static let minNightsForSpread = 5

    /// Rechnet den Wochenvergleich: Woche = 7 × 24 Stunden vor `now`, die Woche davor die 7 × 24 Stunden davor. Schlaf zählt nach dem Ende der Nacht, Befinden nach dem Weckmoment,
    /// Befinden zu Nächten aus anderen Apps (`health`) nach dem Ende ihrer Sitzung.
    public static func compute(nights: [NightRecord], sleeps: [SleepSummary], now: Date, calendar: Calendar,
                               health: [HealthFeeling] = []) -> Trends {
        let weekAgo = now.addingTimeInterval(-7 * 86_400)
        let twoWeeksAgo = now.addingTimeInterval(-14 * 86_400)
        func asleep(_ from: Date, _ to: Date) -> Int? {
            let list = sleeps.filter { $0.end > from && $0.end <= to }
            guard list.count >= minNightsPerWeek else { return nil }
            return list.reduce(0) { $0 + $1.asleepMinutes } / list.count
        }
        func feeling(_ from: Date, _ to: Date) -> Double? {
            let own: [Int] = nights.filter { $0.wokeAt > from && $0.wokeAt <= to }.compactMap(\.feeling)
            let list = own + health.filter { $0.at > from && $0.at <= to }.map(\.feeling)
            guard list.count >= minNightsPerWeek else { return nil }
            return Double(list.reduce(0, +)) / Double(list.count)
        }
        // Einschlafzeit als Minuten ab Mittag, damit 23:30 und 00:30 nahe beieinander liegen; ohne Sprung um Mittag
        // (`Regularity.unwrap`, Audit W-1).
        let bedtimes = Regularity.unwrap(sleeps.filter { $0.end > twoWeeksAgo && $0.end <= now }.map {
            Regularity.minutesFromNoon($0.start, calendar: calendar)
        })
        var spread: Int?
        if bedtimes.count >= minNightsForSpread {
            let mean = bedtimes.reduce(0, +) / Double(bedtimes.count)
            spread = Int(bedtimes.map { abs($0 - mean) }.reduce(0, +) / Double(bedtimes.count))
        }
        return Trends(
            asleepThisWeek: asleep(weekAgo, now),
            asleepLastWeek: asleep(twoWeeksAgo, weekAgo),
            bedtimeSpreadMinutes: spread,
            feelingThisWeek: feeling(weekAgo, now),
            feelingLastWeek: feeling(twoWeeksAgo, weekAgo)
        )
    }
}

/// Hinweis nach mehreren Nächten ohne leichten Schlaf trotz vollem Fenster. Identisch zu `MissHint` (Kotlin).
public enum MissHint: String, Sendable {
    case gaps = "GAPS", heartMissing = "HEART_MISSING", calm = "CALM"

    /// So viele jüngste Nächte eines Weckers müssen alle zur Zielzeit geweckt haben.
    public static let nights = 3

    /// Hinweis für den Wecker `spec`, oder `nil`: Nur bei vollem Fenster (30 Min) und wenn die letzten 3 Nächte alle zur Zielzeit weckten. Die Mehrheit entscheidet über den Grund: Lücken, fehlender Puls, sonst ruhiger Schlaf.
    public static func of(_ spec: AlarmSpec?, nights: [NightRecord]) -> MissHint? {
        guard let spec, spec.windowMinutes >= AlarmSpec.maxWindowMinutes else { return nil }
        let recent = Array(nights.filter { $0.alarmId == spec.id }.sorted { $0.windowStart < $1.windowStart }.suffix(Self.nights))
        guard recent.count >= Self.nights, recent.allSatisfy({ $0.reason == .deadline }) else { return nil }
        let qualities = recent.map { NightQuality.of($0) }
        let majority = Self.nights / 2 + 1
        if qualities.filter({ $0 == .partial || $0 == .none }).count >= majority { return .gaps }
        if qualities.filter({ $0 == .motionOnly }).count >= majority { return .heartMissing }
        return .calm
    }
}
