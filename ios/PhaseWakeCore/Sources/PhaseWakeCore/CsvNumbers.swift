import Foundation

/// Zahlen im CSV-Export (Bewegung, Puls), auf Swift und Kotlin Zeichen für Zeichen gleich (Audit F-7): `String(format:)`
/// rundet wie C den genauen Binärwert halb auf gerade, `String.format` (Java) die kürzeste Dezimaldarstellung halb auf –
/// 56.25 wurde so zu 56.2 bzw. 56.3. Hier zählt nur Gleitkomma-Rechnung, die IEEE 754 auf beiden gleich festlegt
/// (Betrag × Zehnerpotenz + 0.5, abrunden), der Rest ist Ganzzahl (`Int64`, auch auf der Apple Watch mit 32-Bit-`Int`).
/// Gleich in Kotlin (`CsvNumbers`).
public enum CsvNumbers {
    /// Obergrenze für den gerundeten Wert (unter `Int64.max`, als Double exakt vergleichbar).
    private static let limit = 9.0e18

    /// `value` mit `decimals` Nachkommastellen (0 bis 9), halbe Werte vom Nullpunkt weg gerundet (am Wert × 10^`decimals`),
    /// Dezimalpunkt. Nicht endlich oder zu gross für die Rechnung: leer (wie ein fehlender Wert).
    public static func fixed(_ value: Double, decimals: Int) -> String {
        precondition((0...9).contains(decimals), "decimals \(decimals)")
        guard value.isFinite else { return "" }
        var scale: Int64 = 1
        for _ in 0..<decimals { scale *= 10 }
        let scaled = (value.magnitude * Double(scale) + 0.5).rounded(.down)
        guard scaled < limit else { return "" }
        let units = Int64(scaled)
        let sign = value < 0 && units != 0 ? "-" : ""
        let whole = units / scale
        guard decimals > 0 else { return "\(sign)\(whole)" }
        let fraction = String(units % scale)
        return "\(sign)\(whole)." + String(repeating: "0", count: decimals - fraction.count) + fraction
    }
}
