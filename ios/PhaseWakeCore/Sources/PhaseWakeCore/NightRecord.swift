import Foundation

/// Eine aufgezeichnete Weck-Nacht. Identisch zu `NightRecord` (Kotlin).
public struct NightRecord: Equatable, Sendable {
    /// Kennung des Weckers dieser Nacht.
    public let alarmId: String
    /// Beginn des Weckfensters.
    public let windowStart: Date
    /// Zielzeit (spätester Weckmoment).
    public let target: Date
    /// Zeitpunkt des ersten Weckens (vor dem Schlummern).
    public let wokeAt: Date
    /// Warum geweckt wurde.
    public let reason: WakeReason
    /// Wie oft geschlummert wurde (0 bis `Snooze.maxCount`).
    public let snoozes: Int
    /// Gemessene Epochen ab Fensterbeginn (30 s je Epoche), einschliesslich Aufwärmphase.
    public let epochs: [Epoch]
    /// Befinden am Morgen, 1 (müde) bis 5 (erholt); `nil` = nicht angegeben.
    public let feeling: Int?

    /// Erlaubte Werte des Befindens.
    public static let feelingRange = 1...5

    /// Ein Befinden ausserhalb 1–5 wird verworfen (Kotlin lehnt es ab; der Codec prüft vorher).
    public init(alarmId: String, windowStart: Date, target: Date, wokeAt: Date, reason: WakeReason, snoozes: Int, epochs: [Epoch], feeling: Int? = nil) {
        self.alarmId = alarmId
        self.windowStart = windowStart
        self.target = target
        self.wokeAt = wokeAt
        self.reason = reason
        self.snoozes = snoozes
        self.epochs = epochs
        self.feeling = feeling.flatMap { Self.feelingRange.contains($0) ? $0 : nil }
    }

    /// Kopie mit Befinden.
    public func with(feeling: Int?) -> NightRecord {
        NightRecord(alarmId: alarmId, windowStart: windowStart, target: target, wokeAt: wokeAt, reason: reason, snoozes: snoozes, epochs: epochs, feeling: feeling)
    }

    /// Ganze Minuten vor der Zielzeit (abgerundet, nie negativ), wie `Duration.toMinutes()`.
    public var minutesBeforeTarget: Int { max(0, Int(target.timeIntervalSince(wokeAt) / 60)) }

    /// Ganze Minuten nach der Zielzeit (abgerundet; 0, wenn nicht zu spät geweckt), wie `Duration.toMinutes()`.
    public var minutesAfterTarget: Int { max(0, Int(wokeAt.timeIntervalSince(target) / 60)) }
}

/// Aufbewahrung und anonymer Export. Identisch zu `NightHistory` (Kotlin).
public enum NightHistory {
    /// Höchstens so viele Nächte bleiben gespeichert (rund drei Monate); ältere fallen weg.
    public static let maxNights = 90

    /// Die jüngsten 90 Nächte nach Fensterbeginn, älteste zuerst.
    public static func trim(_ nights: [NightRecord]) -> [NightRecord] {
        Array(nights.sorted { $0.windowStart < $1.windowStart }.suffix(maxNights))
    }

    /// Anteil der Nächte in Prozent (gerundet), in denen im leichten Schlaf geweckt wurde. Es zählen nur
    /// Nächte, in denen früher wecken erlaubt war: «Genau» (``WakeReason/exact``) und kurze Nächte
    /// (``WakeReason/shortNight``) fehlen auch im Nenner. `nil`, wenn keine solche Nacht da ist.
    public static func lightShare(_ nights: [NightRecord]) -> Int? {
        let counted = nights.filter { $0.reason != .exact && $0.reason != .shortNight }
        guard !counted.isEmpty else { return nil }
        let light = counted.filter { $0.reason == .lightSleep }.count
        // Wie `Math.round` in Kotlin (nur positive Werte).
        return Int((100 * Double(light) / Double(counted.count)).rounded())
    }

    /// Bewegung je Epoche der gespeicherten Nächte, älteste zuerst, als Eingabe für ``Calibration``.
    public static func motionsForCalibration(_ nights: [NightRecord]) -> [[Double?]] {
        trim(nights).map { $0.epochs.map(\.motion) }
    }

    /// Anonymer CSV-Export: gleiche Spalten und Zahlenformate wie Android. Die Spalte `notes` steht
    /// zuletzt, damit ältere Leser weiter funktionieren: Notizen je Nachtdatum (siehe `SleepNotes`),
    /// mit «;» getrennt, eigene nur als «custom».
    public static func exportCsv(_ nights: [NightRecord], notes: [String: [String]] = [:], calendar: Calendar = .current) -> String {
        var out = "night,window_minutes,reason,woke_minutes_before_target,snoozes,feeling,epoch,motion,hr,notes\n"
        for (nightIndex, night) in trim(nights).enumerated() {
            let windowMinutes = Int(night.target.timeIntervalSince(night.windowStart) / 60)
            let noteValue = SleepNotes.csvValue(notes[SleepNotes.nightDate(of: night, calendar: calendar)] ?? [])
            for (epochIndex, epoch) in night.epochs.enumerated() {
                let motion = epoch.motion.map { String(format: "%.6f", locale: Locale(identifier: "en_US_POSIX"), $0) } ?? ""
                let hr = epoch.heartRate.map { String(format: "%.1f", locale: Locale(identifier: "en_US_POSIX"), $0) } ?? ""
                out += "\(nightIndex + 1),\(windowMinutes),\(night.reason.rawValue),\(night.minutesBeforeTarget),\(night.snoozes),\(night.feeling.map(String.init) ?? ""),\(epochIndex),\(motion),\(hr),\(noteValue)\n"
            }
        }
        return out
    }
}
