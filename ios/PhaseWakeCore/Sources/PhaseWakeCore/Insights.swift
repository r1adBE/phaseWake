import Foundation

/// Wie gut die Messung einer Nacht war. Identisch zu `DataQuality` (Kotlin).
public enum DataQuality: String, Sendable {
    case full = "FULL", motionOnly = "MOTION_ONLY", partial = "PARTIAL", none = "NONE"
}

/// Identisch zu `NightQuality` (Kotlin).
public enum NightQuality {
    public static let completeShare = 0.8
    public static let heartShare = 0.5

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
    public let start: Date
    public let end: Date
    public let asleepMinutes: Int

    public init(start: Date, end: Date, asleepMinutes: Int) {
        self.start = start
        self.end = end
        self.asleepMinutes = asleepMinutes
    }

    public static let newNightGap: TimeInterval = 3 * 3600
    public static let minAsleepMinutes = 180

    /// Fasst Phasen zu Nächten zusammen, älteste zuerst.
    public static func group(_ spans: [StageSpan]) -> [SleepSummary] {
        var groups: [[StageSpan]] = []
        for span in spans.sorted(by: { $0.start < $1.start }) {
            if let last = groups.last, let lastEnd = last.map(\.end).max(), span.start.timeIntervalSince(lastEnd) < newNightGap {
                groups[groups.count - 1].append(span)
            } else {
                groups.append([span])
            }
        }
        return groups.compactMap { group in
            let asleep = group.filter { $0.stage != .awake }
            guard let start = asleep.map(\.start).min(), let end = group.map(\.end).max() else { return nil }
            // Ganze Minuten je Phase, wie `Duration.toMinutes()`.
            let minutes = asleep.reduce(0) { $0 + Int($1.end.timeIntervalSince($1.start) / 60) }
            return minutes < minAsleepMinutes ? nil : SleepSummary(start: start, end: end, asleepMinutes: minutes)
        }
    }
}

/// Wochenvergleich für den Verlauf. Identisch zu `Trends` (Kotlin).
public struct Trends: Equatable, Sendable {
    public let asleepThisWeek: Int?
    public let asleepLastWeek: Int?
    public let bedtimeSpreadMinutes: Int?
    public let feelingThisWeek: Double?
    public let feelingLastWeek: Double?

    public var asleepChange: Int? {
        guard let a = asleepThisWeek, let b = asleepLastWeek else { return nil }
        return a - b
    }

    public static let minNightsPerWeek = 3
    public static let minNightsForSpread = 5

    public static func compute(nights: [NightRecord], sleeps: [SleepSummary], now: Date, calendar: Calendar) -> Trends {
        let weekAgo = now.addingTimeInterval(-7 * 86_400)
        let twoWeeksAgo = now.addingTimeInterval(-14 * 86_400)
        func asleep(_ from: Date, _ to: Date) -> Int? {
            let list = sleeps.filter { $0.end > from && $0.end <= to }
            guard list.count >= minNightsPerWeek else { return nil }
            return list.reduce(0) { $0 + $1.asleepMinutes } / list.count
        }
        func feeling(_ from: Date, _ to: Date) -> Double? {
            let list = nights.filter { $0.wokeAt > from && $0.wokeAt <= to }.compactMap(\.feeling)
            guard list.count >= minNightsPerWeek else { return nil }
            return Double(list.reduce(0, +)) / Double(list.count)
        }
        // Einschlafzeit als Minuten ab Mittag, damit 23:30 und 00:30 nahe beieinander liegen.
        let bedtimes: [Double] = sleeps.filter { $0.end > twoWeeksAgo && $0.end <= now }.map {
            let c = calendar.dateComponents([.hour, .minute], from: $0.start)
            let m = (c.hour ?? 0) * 60 + (c.minute ?? 0) - 12 * 60
            return Double(((m % 1440) + 1440) % 1440)
        }
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
