import Foundation

/// Eine aufgezeichnete Weck-Nacht. Identisch zu `NightRecord` (Kotlin).
public struct NightRecord: Equatable, Sendable {
    public let alarmId: String
    public let windowStart: Date
    public let target: Date
    public let wokeAt: Date
    public let reason: WakeReason
    public let snoozes: Int
    public let epochs: [Epoch]

    public init(alarmId: String, windowStart: Date, target: Date, wokeAt: Date, reason: WakeReason, snoozes: Int, epochs: [Epoch]) {
        self.alarmId = alarmId
        self.windowStart = windowStart
        self.target = target
        self.wokeAt = wokeAt
        self.reason = reason
        self.snoozes = snoozes
        self.epochs = epochs
    }

    /// Ganze Minuten vor der Zielzeit (abgerundet, nie negativ), wie `Duration.toMinutes()`.
    public var minutesBeforeTarget: Int { max(0, Int(target.timeIntervalSince(wokeAt) / 60)) }
}

/// Aufbewahrung und anonymer Export. Identisch zu `NightHistory` (Kotlin).
public enum NightHistory {
    public static let maxNights = 90

    public static func trim(_ nights: [NightRecord]) -> [NightRecord] {
        Array(nights.sorted { $0.windowStart < $1.windowStart }.suffix(maxNights))
    }

    public static func motionsForCalibration(_ nights: [NightRecord]) -> [[Double?]] {
        trim(nights).map { $0.epochs.map(\.motion) }
    }

    /// Anonymer CSV-Export: gleiche Spalten und Zahlenformate wie Android.
    public static func exportCsv(_ nights: [NightRecord]) -> String {
        var out = "night,window_minutes,reason,woke_minutes_before_target,snoozes,epoch,motion,hr\n"
        for (nightIndex, night) in trim(nights).enumerated() {
            let windowMinutes = Int(night.target.timeIntervalSince(night.windowStart) / 60)
            for (epochIndex, epoch) in night.epochs.enumerated() {
                let motion = epoch.motion.map { String(format: "%.6f", locale: Locale(identifier: "en_US_POSIX"), $0) } ?? ""
                let hr = epoch.heartRate.map { String(format: "%.1f", locale: Locale(identifier: "en_US_POSIX"), $0) } ?? ""
                out += "\(nightIndex + 1),\(windowMinutes),\(night.reason.rawValue),\(night.minutesBeforeTarget),\(night.snoozes),\(epochIndex),\(motion),\(hr)\n"
            }
        }
        return out
    }
}
