import Foundation

/// Nacht-Check (Audit F-06): Die Uhr hat die Messung vor dem Schlafen für die Nacht mit Zielzeit `target` nicht
/// gestartet, obwohl sie gewünscht war (Einschlaf-Messung oder Lernphase), aus `reason`. Die Uhr meldet es ans iPhone;
/// dort ist der Nacht-Check rot, bis die Zielzeit vorbei ist (``applies(at:)``). Gleich in Kotlin (`RecorderSkip`).
public struct RecorderSkip: Equatable, Sendable {
    /// Grund, mit seinem Kürzel im gemeldeten Text (``encode()``).
    public enum Reason: String, Sendable {
        /// Akku zu schwach (Einschlaf-Messung unter 30 %, Lernphase unter 50 %).
        case battery
        /// Keine Erlaubnis für Bewegungsdaten (nur Apple Watch).
        case motion
    }

    /// Zielzeit der Nacht, für die die Meldung gilt.
    public let target: Date
    /// Warum der Rekorder nicht startete.
    public let reason: Reason

    /// Meldung für die Nacht mit Zielzeit `target` und Grund `reason`.
    public init(target: Date, reason: Reason) {
        self.target = target
        self.reason = reason
    }

    /// Ob die Meldung um `now` noch gilt: Die Nacht ist nicht vorbei.
    public func applies(at now: Date) -> Bool { now < target }

    /// «Zielzeit in ms|Grund», siehe ``decode(_:)``.
    public func encode() -> String { "\(Int64((target.timeIntervalSince1970 * 1000).rounded()))|\(reason.rawValue)" }

    /// Liest ``encode()``; `nil` bei leerem oder unlesbarem Text oder unbekanntem Grund (neuere Uhr).
    public static func decode(_ text: String?) -> RecorderSkip? {
        guard let parts = text?.trimmingCharacters(in: .whitespacesAndNewlines).split(separator: "|", omittingEmptySubsequences: false),
              parts.count >= 2, let ms = Int64(parts[0]), ms > 0, let reason = Reason(rawValue: String(parts[1])) else { return nil }
        return RecorderSkip(target: Date(timeIntervalSince1970: Double(ms) / 1000), reason: reason)
    }
}
