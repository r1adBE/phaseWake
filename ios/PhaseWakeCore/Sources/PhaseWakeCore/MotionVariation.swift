/// «Auf und Ab» (Variante B, `docs/PHASENERKENNUNG.md` Abschnitt 3): Eine Epoche zählt in Regel S5 und in der
/// Leicht-Regel nur als Bewegung, wenn sie vom bisherigen Verlauf abweicht. Ein Plateau (eine neue Armlage, die gleich
/// bleibt) ist keine Bewegung (OFFEN 11g, Audit 23.11.2026).
///
/// Reine Logik; identisch zu `MotionVariation` (Kotlin).
public enum MotionVariation {
    /// Höchstens so viele vorherige Epochen mit Bewegung bilden den Median, gegen den
    /// ``varies(previous:motion:floor:)`` vergleicht. Wie `VARIATION_WINDOW` (Kotlin).
    public static let variationWindow = 10

    /// Nötige Abweichung als Vielfaches des Grundrauschens *F*. Wie `VARIATION_FACTOR` (Kotlin).
    public static let variationFactor = 1.0

    /// Weicht `motion` um mindestens ``variationFactor`` × `floor` ab, vom Median der letzten bis zu
    /// ``variationWindow`` Bewegungen in `previous` (leere übergangen; bei gerader Anzahl Mittel der beiden mittleren)
    /// oder von der Vorepoche (letzter Eintrag von `previous`, wenn er eine Bewegung hat)? Ohne vorherige Bewegung
    /// `true`. `previous`: Bewegungen der Epochen vor dieser in der laufenden Messung, älteste zuerst; `nil` = leer.
    public static func varies(previous: [Double?], motion: Double, floor: Double) -> Bool {
        let values = Array(previous.compactMap { $0 }.suffix(variationWindow))
        if values.isEmpty { return true }
        let needed = variationFactor * floor
        if abs(motion - median(values)) >= needed { return true }
        if let last = previous.last ?? nil, abs(motion - last) >= needed { return true }
        return false
    }

    /// Median von `values` (nicht leer); bei gerader Anzahl das Mittel der beiden mittleren Werte.
    private static func median(_ values: [Double]) -> Double {
        let sorted = values.sorted()
        let mid = sorted.count / 2
        return sorted.count % 2 == 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2.0
    }
}
