import Foundation

/// Befinden je Weckgrund: Wie erholt fühlte sich der Nutzer nach Nächten, die im leichten Schlaf, wach, zur Zielzeit,
/// mit «Genau» oder als kurze Nacht geweckt haben? Nur Auswertung für die Diagnose (Abschnitt ``title``); ändert
/// nichts am Wecken. Ob die Schwellen nach dem Befinden angepasst werden, wird erst ab ``reviewNights`` Nächten mit
/// Befinden geprüft. Technische Zeilen auf Englisch wie das Diagnose-Protokoll. Identisch zu `FeelingByReason`
/// (Kotlin).
public enum FeelingByReason {
    /// Titel des Abschnitts im Diagnose-Bericht.
    public static let title = "FEELING"

    /// Ab so vielen eigenen Nächten mit Befinden lohnt die Prüfung der Schwellen.
    public static let reviewNights = 20

    /// Erste Zeile von ``lines(_:)``: was gezählt wird.
    public static let note =
        "feeling 1 (tired) to 5 (rested) of own nights with a feeling, by wake reason; does not change waking"

    /// Reihenfolge der Zeilen: früh geweckt zuerst, dann zur Zielzeit.
    public static let order: [WakeReason] = [.lightSleep, .awake, .deadline, .exact, .shortNight]

    /// Nächte mit Befinden zu `reason`: Anzahl `nights` und Summe `sum` des Befindens.
    public struct Row: Equatable, Sendable {
        /// Weckgrund.
        public let reason: WakeReason
        /// Nächte mit Befinden.
        public let nights: Int
        /// Summe des Befindens dieser Nächte.
        public let sum: Int

        /// Durchschnitt; `nil` ohne Nacht.
        public var average: Double? { nights == 0 ? nil : Double(sum) / Double(nights) }

        /// Durchschnitt mit einer Nachkommastelle, halbe aufwärts, aus ganzen Zahlen (gleich in Kotlin); sonst «-».
        public func averageText() -> String { FeelingByReason.averageText(sum: sum, count: nights) }
    }

    /// Je Grund aus ``order`` eine Zeile, auch ohne Nacht; Nächte ohne Befinden zählen nicht.
    public static func of(_ nights: [NightRecord]) -> [Row] {
        order.map { reason in
            let feelings = nights.filter { $0.reason == reason }.compactMap(\.feeling)
            return Row(reason: reason, nights: feelings.count, sum: feelings.reduce(0, +))
        }
    }

    /// Zeilen des Abschnitts: ``note``, je Grund «REASON N nights avg X.Y», zuletzt «all: N nights with feeling avg
    /// X.Y» mit dem Stand zu ``reviewNights``. Ohne Nacht mit Befinden leer (kein Abschnitt).
    public static func lines(_ nights: [NightRecord]) -> [String] {
        let rows = of(nights)
        let count = rows.reduce(0) { $0 + $1.nights }
        guard count > 0 else { return [] }
        let review = count >= reviewNights
            ? "\(reviewNights) reached, review thresholds"
            : "review thresholds from \(reviewNights) (\(reviewNights - count) to go)"
        let all = averageText(sum: rows.reduce(0) { $0 + $1.sum }, count: count)
        return [note] + rows.map { "\($0.reason.rawValue) \($0.nights) nights avg \($0.averageText())" }
            + ["all: \(count) nights with feeling avg \(all) · \(review)"]
    }

    /// `sum` / `count` mit einer Nachkommastelle, halbe aufwärts, ohne Gleitkomma; ohne `count` «-».
    static func averageText(sum: Int, count: Int) -> String {
        guard count > 0 else { return "-" }
        let tenths = (sum * 20 + count) / (2 * count)
        return "\(tenths / 10).\(tenths % 10)"
    }
}
