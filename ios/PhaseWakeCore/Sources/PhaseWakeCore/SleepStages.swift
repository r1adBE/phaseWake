import Foundation

/// Schlafphase aus einer Gesundheits-App, nachträglich gelesen. Identisch zu Kotlin.
public enum SleepStage: String, Sendable { case awake = "AWAKE", light = "LIGHT", deep = "DEEP", rem = "REM", unknown = "UNKNOWN" }

/// Zeitspanne einer Schlafphase.
public struct StageSpan: Equatable, Sendable {
    /// Beginn der Phase.
    public let start: Date
    /// Ende der Phase (exklusiv).
    public let end: Date
    /// Art der Phase.
    public let stage: SleepStage

    /// Erzeugt eine Spanne; `start` liegt vor `end`.
    public init(start: Date, end: Date, stage: SleepStage) {
        self.start = start
        self.end = end
        self.stage = stage
    }
}

/// Abgleich des Weckmoments mit den Schlafphasen. Identisch zu `StageCheck` (Kotlin).
public enum StageCheck {
    /// Phase zum Zeitpunkt `at` (Beginn einschliesslich, Ende ausschliesslich); bei Überlappung die zuletzt gelistete; `nil` ausserhalb jeder Phase.
    public static func stageAt(_ spans: [StageSpan], _ at: Date) -> SleepStage? {
        spans.last { at >= $0.start && at < $0.end }?.stage
    }

    /// Phase beim Wecken. Gesundheits-Apps beenden die Schlafsitzung oft genau beim Aufwachen; darum zählt auch die Phase, die bis zu `toleranceSeconds` Sekunden (Standard 2 Min) vorher endete.
    public static func stageAtWake(_ spans: [StageSpan], wokeAt: Date, toleranceSeconds: TimeInterval = 120) -> SleepStage? {
        stageAt(spans, wokeAt) ?? stageAt(spans, wokeAt.addingTimeInterval(-toleranceSeconds))
    }

    /// Ob gut geweckt wurde: `true` aus leichtem Schlaf, REM oder wach, `false` aus Tiefschlaf, `nil` ohne bekannte Phase.
    public static func wokeWell(_ spans: [StageSpan], wokeAt: Date) -> Bool? {
        switch stageAtWake(spans, wokeAt: wokeAt) {
        case .light, .rem, .awake: true
        case .deep: false
        case .unknown, nil: nil
        }
    }

    /// Spannen auf den Bereich `from`–`to` zugeschnitten, ausserhalb fallen weg, nach Beginn sortiert.
    public static func clip(_ spans: [StageSpan], from: Date, to: Date) -> [StageSpan] {
        spans.filter { $0.end > from && $0.start < to }
            .map { StageSpan(start: max($0.start, from), end: min($0.end, to), stage: $0.stage) }
            .sorted { $0.start < $1.start }
    }
}

/// Vorschlag aus dem Verlauf: grösseres Fenster, wenn meist erst zur Zielzeit geweckt wurde.
public enum WindowAdvice {
    /// So viele jüngste Nächte des Weckers werden betrachtet.
    public static let nights = 7
    /// Mindestens so viele Nächte, sonst kein Vorschlag.
    public static let minNights = 3
    /// Um so viele Minuten wird das Fenster vergrössert (höchstens bis 30).
    public static let stepMinutes = 10

    /// Vorschlag mit Begründung für die Anzeige.
    public struct Suggestion: Equatable, Sendable {
        /// Vorgeschlagenes Fenster in Minuten.
        public let windowMinutes: Int
        /// Nächte, die erst zur Zielzeit geweckt haben.
        public let deadlineNights: Int
        /// Betrachtete Nächte.
        public let nights: Int
    }

    /// Vorschlag, oder `nil`: nur bei einem Fenster unter 30 Min (nicht bei «Genau», bewusst ohne Fenster), ab 3 Nächten
    /// und wenn mehr als die Hälfte der letzten 7 Nächte erst zur Zielzeit geweckt hat.
    public static func suggest(_ spec: AlarmSpec, history: [NightRecord]) -> Suggestion? {
        guard !spec.isExact, spec.windowMinutes < AlarmSpec.maxWindowMinutes else { return nil }
        let recent = Array(history.filter { $0.alarmId == spec.id && $0.reason.earlyAllowed }.sorted { $0.windowStart < $1.windowStart }.suffix(nights))
        guard recent.count >= minNights else { return nil }
        let deadline = recent.filter { $0.reason == .deadline }.count
        guard deadline * 2 > recent.count else { return nil }
        return Suggestion(windowMinutes: min(spec.windowMinutes + stepMinutes, AlarmSpec.maxWindowMinutes), deadlineNights: deadline, nights: recent.count)
    }
}
