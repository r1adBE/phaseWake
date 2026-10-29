import Foundation

/// Untergrenze für kurze Nächte: Wer deutlich weniger als das Schlafziel geschlafen hat, wird nicht früh geweckt,
/// sondern zur Zielzeit («Pünktlich ist das Produkt, früher ist Bonus»). Regel siehe `docs/PHASENERKENNUNG.md`,
/// Abschnitt 8. Identisch zu `ShortNight` (Kotlin).
///
/// Auf der Apple Watch ist die Einschlafzeit unbekannt (gemessen wird nur in der 30-Minuten-Sitzung); dort gilt die
/// Untergrenze darum nicht (`blocksEarly` mit `onset: nil`).
public enum ShortNight {
    /// So viele Minuten unter dem Schlafziel gilt eine Nacht noch nicht als kurz.
    public static let marginMinutes = 60

    /// Die automatische Einschlaf-Messung beginnt so viele Minuten vor der Bettzeit (`autoOnsetStart`).
    public static let autoLeadMinutes = 60

    /// Ob frühes Wecken gesperrt ist: Einschlafzeit `onset` bekannt und seit dann weniger als Schlafziel minus
    /// `marginMinutes` vergangen (bei 7½ h also unter 6½ h Schlaf). Ohne bekannte Einschlafzeit nie gesperrt.
    public static func blocksEarly(onset: Date?, now: Date, sleepGoalMinutes: Int) -> Bool {
        guard let onset else { return false }
        return now.timeIntervalSince(onset) < TimeInterval((sleepGoalMinutes - marginMinutes) * 60)
    }

    /// Beginn der automatischen Einschlaf-Messung für die Zielzeit `target`: Bettzeit minus `autoLeadMinutes`.
    /// Bettzeit = `target` minus Schlafdauer (`sleepDurationMinutes`, Einschlaf-Modus) bzw. minus Schlafziel.
    public static func autoOnsetStart(target: Date, sleepGoalMinutes: Int, sleepDurationMinutes: Int?) -> Date {
        target.addingTimeInterval(-TimeInterval(((sleepDurationMinutes ?? sleepGoalMinutes) + autoLeadMinutes) * 60))
    }
}
