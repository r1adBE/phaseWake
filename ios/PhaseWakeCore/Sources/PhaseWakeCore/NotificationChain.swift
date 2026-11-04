import Foundation

/// Weck-Mitteilung als Kette: Statt einer einzigen kommen `count` zeitkritische Mitteilungen im Abstand von `spacing`
/// mit gleichem Inhalt. Wer eine verschläft oder wegwischt, wird von der nächsten nochmals geweckt. Nur Apple: der
/// Rückfall der Apple Watch (`SmartAlarm.scheduleFallback`) und der Zweitwecker am iPhone vor iOS 26; Wear OS und
/// Android klingeln über `AlarmManager` und brauchen das nicht.
///
/// Kennungen: die erste behält die bisherige Kennung `base` (ältere Stände und Prüfungen finden sie weiter), die
/// übrigen hängen «.1», «.2», «.3» an. `baseId` führt jede Kennung der Kette auf `base` zurück.
public enum NotificationChain {
    /// Mitteilungen je Kette.
    public static let count = 4
    /// Abstand zwischen zwei Mitteilungen der Kette.
    public static let spacing: TimeInterval = 30

    /// Alle Kennungen der Kette zu `base`, in der Reihenfolge der Zeitpunkte (`dates`).
    public static func ids(_ base: String) -> [String] {
        [base] + (1..<count).map { "\(base).\($0)" }
    }

    /// Zeitpunkte der Kette, beginnend mit `first`.
    public static func dates(_ first: Date) -> [Date] {
        (0..<count).map { first.addingTimeInterval(Double($0) * spacing) }
    }

    /// Kennungen der Kette zu `base` ohne die erste: die noch folgenden, wenn die erste schon geweckt hat.
    public static func followingIds(_ base: String) -> [String] {
        Array(ids(base).dropFirst())
    }

    /// Kennungen aller Ketten zu `bases` (für das Entfernen), ohne Doppel, in der Reihenfolge von `bases`.
    public static func allIds(_ bases: [String]) -> [String] {
        var seen = Set<String>()
        return bases.flatMap(ids).filter { seen.insert($0).inserted }
    }

    /// Kennung der Kette, zu der `id` gehört: ohne Endung «.1» … «.3»; jede andere Kennung bleibt unverändert.
    public static func baseId(_ id: String) -> String {
        guard let dot = id.lastIndex(of: ".") else { return id }
        let suffix = String(id[id.index(after: dot)...])
        // Nur genau «1» … «3» (kein «01», kein Vorzeichen): sonst gehört die Kennung zu keiner Kette.
        guard let index = Int(suffix), (1..<count).contains(index), String(index) == suffix else { return id }
        return String(id[..<dot])
    }
}
