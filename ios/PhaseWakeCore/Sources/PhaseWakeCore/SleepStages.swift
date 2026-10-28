import Foundation

/// Schlafphase aus einer Gesundheits-App, nachträglich gelesen. Identisch zu Kotlin.
public enum SleepStage: String, Sendable { case awake = "AWAKE", light = "LIGHT", deep = "DEEP", rem = "REM", unknown = "UNKNOWN" }

/// Zeitspanne einer Schlafphase.
public struct StageSpan: Equatable, Sendable {
    public let start: Date
    public let end: Date
    public let stage: SleepStage

    public init(start: Date, end: Date, stage: SleepStage) {
        self.start = start
        self.end = end
        self.stage = stage
    }
}

/// Abgleich des Weckmoments mit den Schlafphasen. Identisch zu `StageCheck` (Kotlin).
public enum StageCheck {
    public static func stageAt(_ spans: [StageSpan], _ at: Date) -> SleepStage? {
        spans.last { at >= $0.start && at < $0.end }?.stage
    }

    public static func stageAtWake(_ spans: [StageSpan], wokeAt: Date, toleranceSeconds: TimeInterval = 120) -> SleepStage? {
        stageAt(spans, wokeAt) ?? stageAt(spans, wokeAt.addingTimeInterval(-toleranceSeconds))
    }

    public static func wokeWell(_ spans: [StageSpan], wokeAt: Date) -> Bool? {
        switch stageAtWake(spans, wokeAt: wokeAt) {
        case .light, .rem, .awake: true
        case .deep: false
        case .unknown, nil: nil
        }
    }

    public static func clip(_ spans: [StageSpan], from: Date, to: Date) -> [StageSpan] {
        spans.filter { $0.end > from && $0.start < to }
            .map { StageSpan(start: max($0.start, from), end: min($0.end, to), stage: $0.stage) }
            .sorted { $0.start < $1.start }
    }
}

/// Vorschlag aus dem Verlauf: grösseres Fenster, wenn meist erst zur Zielzeit geweckt wurde.
public enum WindowAdvice {
    public static let nights = 7
    public static let minNights = 3
    public static let stepMinutes = 10

    public struct Suggestion: Equatable, Sendable {
        public let windowMinutes: Int
        public let deadlineNights: Int
        public let nights: Int
    }

    public static func suggest(_ spec: AlarmSpec, history: [NightRecord]) -> Suggestion? {
        guard spec.windowMinutes < AlarmSpec.maxWindowMinutes else { return nil }
        let recent = Array(history.filter { $0.alarmId == spec.id }.sorted { $0.windowStart < $1.windowStart }.suffix(nights))
        guard recent.count >= minNights else { return nil }
        let deadline = recent.filter { $0.reason == .deadline }.count
        guard deadline * 2 > recent.count else { return nil }
        return Suggestion(windowMinutes: min(spec.windowMinutes + stepMinutes, AlarmSpec.maxWindowMinutes), deadlineNights: deadline, nights: recent.count)
    }
}
