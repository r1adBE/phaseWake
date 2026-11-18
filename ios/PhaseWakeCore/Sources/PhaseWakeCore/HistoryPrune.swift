import Foundation

/// «Älter als 90 Tage löschen»: wie «Verlauf löschen», nur bis ``cutoff(now:)`` (jetzt − ``days`` Tage). Was bis
/// dahin endete, ist weg; Nächte aus der Gesundheits-App lassen sich nicht löschen und werden wie dort über den
/// Zeitpunkt «Verlauf löschen» ausgeblendet (``HealthHistory/visible(_:clearedAt:)``). Identisch zu Kotlin.
public enum HistoryPrune {
    /// Älter als so viele Tage wird gelöscht.
    public static let days = 90

    /// Grenze: jetzt − ``days`` Tage.
    public static func cutoff(now: Date) -> Date {
        now.addingTimeInterval(-Double(days) * 86_400)
    }

    /// Neuer Zeitpunkt «Verlauf löschen» für die Nächte aus der Gesundheits-App: `cutoff`, ausser ein früheres
    /// «Verlauf löschen» liegt später (dann bleibt es, sonst kämen dort gelöschte Nächte zurück).
    public static func clearedAt(current: Date?, cutoff: Date) -> Date {
        if let current, current > cutoff { return current }
        return cutoff
    }

    /// Eigene Nächte, die bleiben: geweckt echt nach `cutoff` (wie beim Löschen über die Uhr).
    public static func nights(_ nights: [NightRecord], cutoff: Date) -> [NightRecord] {
        nights.filter { $0.wokeAt > cutoff }
    }

    /// Ob eine Aufzeichnung von `start` bis `end` bleibt: endet echt nach `cutoff`, läuft noch (`end` `nil`) oder
    /// gehört zur noch offenen Lernphase ab `learningSince` (Beginn nicht vor `learningSince`, wie `Learning.counted`
    /// zählt) – sonst fehlten ihre Nächte der Lernphase. `learningSince` `nil`: keine offene Lernphase.
    public static func keepsRecording(start: Date, end: Date?, cutoff: Date, learningSince: Date?) -> Bool {
        guard let end, end <= cutoff else { return true }
        if let learningSince, start >= learningSince { return true }
        return false
    }

    /// Notizen, Befinden oder Haken, die bleiben: ab dem Nachtdatum von `cutoff` (`SleepNotes.nightDate`,
    /// «yyyy-MM-dd»). Im Zweifel bleibt ein Nachtdatum (eine Nacht, die am Morgen von `cutoff` endete, behält ihre
    /// Notizen).
    public static func dates<T>(_ byDate: [String: T], cutoff: Date, calendar: Calendar) -> [String: T] {
        let first = SleepNotes.nightDate(cutoff, calendar: calendar)
        return byDate.filter { $0.key >= first }
    }
}
