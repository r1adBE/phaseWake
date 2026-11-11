import Foundation

/// «Verlauf löschen» zwischen iPhone und Uhr (Audit D-02/AP-6, wie Android): Der Befehl trägt seine Sendezeit. Gelöscht
/// wird nur, was bis dahin endete; was danach endet (eine Nacht, die erst später weckt, eine Aufzeichnung, die noch
/// läuft), bleibt. So holt eine verspätet zugestellte Nacht den gelöschten Verlauf nicht zurück, und ein verspäteter
/// Befehl löscht keine neuere Nacht. Rein, ohne Plattform (testbar).
enum HistoryClear {
    /// Zeitpunkt, bis zu dem die Uhr löscht: die Sendezeit `sentAt` des Befehls, wenn sie neuer ist als das zuletzt
    /// angewandte Löschen `applied`. Ohne Sendezeit (ältere iPhone-App) oder schon angewandt: `nil` (nichts tun).
    static func clearUntil(sentAt: Date?, applied: Date?) -> Date? {
        guard let sentAt else { return nil }
        if let applied, sentAt <= applied { return nil }
        return sentAt
    }

    /// Ob etwas, das um `end` endete, ein Löschen bis `clearedAt` übersteht: nur, wenn es danach endete. Ohne Löschen
    /// (`nil`) immer.
    static func keeps(end: Date, clearedAt: Date?) -> Bool {
        guard let clearedAt else { return true }
        return end > clearedAt
    }
}
