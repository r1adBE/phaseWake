import Foundation

/// Plausible Zeitpunkte beim Lesen gespeicherter oder empfangener Daten (ms seit 1970): von 1970-01-01 bis 2100-01-01 UTC.
/// Ausserhalb ist der Wert kaputt oder fremd (z. B. eine manipulierte Sicherung mit 9223372036854775807); er wird beim
/// Lesen verworfen, sonst bräche das spätere Zurückrechnen in Millisekunden (`Int64(… .rounded())`) die App ab.
/// Untergrenze 1970 statt 2000: Tests und Testdaten beider Plattformen nutzen kleine Zeiten; die laufen nie über.
/// Identisch zu `TimeBounds` (Kotlin).
public enum TimeBounds {
    /// 1970-01-01T00:00:00Z.
    public static let minMs: Int64 = 0
    /// 2100-01-01T00:00:00Z.
    public static let maxMs: Int64 = 4_102_444_800_000

    /// Ob `ms` ein plausibler Zeitpunkt ist (``minMs`` bis ``maxMs``).
    public static func contains(_ ms: Int64) -> Bool {
        ms >= minMs && ms <= maxMs
    }

    /// `ms`, falls plausibel (``contains(_:)``); sonst `nil`.
    public static func checked(_ ms: Int64?) -> Int64? {
        guard let ms, contains(ms) else { return nil }
        return ms
    }

    /// Millisekunden seit 1970, gerundet (`.rounded()`) wie im Textformat, ohne Abbruch: Ein Zeitpunkt jenseits von
    /// ±9·10¹⁸ ms (nur aus kaputten Daten) wird dort begrenzt, NaN ergibt 0. Kotlin braucht das nicht (`Instant` aus
    /// `Long` läuft nie über).
    public static func millis(_ date: Date) -> Int64 {
        let ms = (date.timeIntervalSince1970 * 1000).rounded()
        guard ms.isFinite else { return 0 }
        return Int64(min(max(ms, -limit), limit))
    }

    /// Grösster Betrag für ``millis(_:)``, sicher unter `Int64.max` (≈ 9.22·10¹⁸) und exakt als `Double` darstellbar.
    private static let limit = 9e18
}
