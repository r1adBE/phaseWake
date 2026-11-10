import Foundation

/// Untergrenze für kurze Nächte: Wer deutlich weniger als das Schlafziel geschlafen hat, wird nicht früh geweckt,
/// sondern zur Zielzeit («Pünktlich ist das Produkt, früher ist Bonus»). Regel siehe `docs/PHASENERKENNUNG.md`,
/// Abschnitt 8. Identisch zu `ShortNight` (Kotlin).
///
/// Auf der Apple Watch rechnet die Uhr die Einschlafzeit zu Beginn der Smart-Alarm-Sitzung aus dem Bewegungs-Rekorder
/// nach (``RecorderOnset``), wenn er in dieser Nacht lief. Lief er nicht, ist sie unbekannt, und die Untergrenze gilt
/// nicht (`blocksEarly` mit `onset: nil`); lief er, aber die Rechnung bringt kein Ergebnis, gilt sie bis 10 Min vor der
/// Zielzeit (`measured`).
public enum ShortNight {
    /// So viele Minuten unter dem Schlafziel gilt eine Nacht noch nicht als kurz.
    public static let marginMinutes = 60

    /// In den letzten so vielen Minuten vor der Zielzeit gilt die Untergrenze nicht mehr (Entscheid r1AD 10.11.2026):
    /// Ein neuer früher Moment weckt dann; ein vorher übergangener wird nicht nachgeholt.
    public static let lateMinutes = 10

    /// Die automatische Einschlaf-Messung beginnt so viele Minuten vor der Bettzeit (`autoOnsetStart`).
    public static let autoLeadMinutes = 60

    /// Ob frühes Wecken gesperrt ist: Einschlafzeit `onset` bekannt und seit dann weniger als Schlafziel minus
    /// `marginMinutes` vergangen (bei 7½ h also unter 6½ h Schlaf). Ohne bekannte Einschlafzeit gesperrt, wenn sie
    /// gemessen wurde, die Messung aber kein Ergebnis brachte (`measured`: begonnen, dann aufgegeben, vom System
    /// beendet, gescheitert oder ohne Werte – fail-closed, Entscheid r1AD 10.11.2026); ohne Messung nie. In den letzten
    /// `lateMinutes` vor der Zielzeit `target` nie mehr (ohne `target`: bis zum Schluss gesperrt).
    public static func blocksEarly(onset: Date?, now: Date, sleepGoalMinutes: Int, target: Date? = nil, measured: Bool = false) -> Bool {
        if let target, now >= target.addingTimeInterval(-TimeInterval(lateMinutes * 60)) { return false }
        guard let onset else { return measured }
        return now.timeIntervalSince(onset) < TimeInterval((sleepGoalMinutes - marginMinutes) * 60)
    }

    /// Beginn der automatischen Einschlaf-Messung für die Zielzeit `target`: Bettzeit minus `autoLeadMinutes`.
    /// Bettzeit = `target` minus Schlafdauer (`sleepDurationMinutes`, Einschlaf-Modus) bzw. minus Schlafziel.
    public static func autoOnsetStart(target: Date, sleepGoalMinutes: Int, sleepDurationMinutes: Int?) -> Date {
        target.addingTimeInterval(-TimeInterval(((sleepDurationMinutes ?? sleepGoalMinutes) + autoLeadMinutes) * 60))
    }
}
