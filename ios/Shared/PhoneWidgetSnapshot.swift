import Foundation
import PhaseWakeCore

/// Was das iPhone-Widget zeigt: die nächsten Weckfenster und das Ergebnis des Nacht-Checks.
///
/// Die iPhone-App schreibt den Stand bei jedem Öffnen und alle 30 Sekunden, solange sie offen ist, in die
/// gemeinsame App-Gruppe; das Widget liest nur. Mehrere Fenster, damit das Widget nach einer Zielzeit von
/// selbst den nächsten Wecker zeigt, ohne dass die App läuft (wie der Nacht-Check auf Android).
struct PhoneWidgetSnapshot: Codable, Equatable {
    /// Ein geplantes Weckfenster.
    struct Plan: Codable, Equatable {
        let windowStart: Date
        let target: Date
        /// Name des Weckers, leer ohne Namen.
        let label: String
    }

    /// Kommende Fenster, nach Zielzeit sortiert.
    var plans: [Plan]
    /// Titel der ersten offenen Prüfung; leer = bereit für die Nacht, `nil` = noch kein Check gelaufen.
    var issue: String?

    /// Art des Widgets (für das Neuladen).
    static let kind = "PhoneNextAlarm"
    private static let key = "phoneWidget"
    private static var defaults: UserDefaults? { UserDefaults(suiteName: PhaseWakeStorage.watchGroup) }

    /// Erstes Fenster, dessen Zielzeit noch nicht vorbei ist.
    func next(after now: Date) -> Plan? { plans.first { $0.target > now } }

    /// Gespeicherter Stand; `nil` ohne Stand oder ohne App-Gruppe.
    static func load() -> PhoneWidgetSnapshot? {
        guard let data = defaults?.data(forKey: key) else { return nil }
        return try? JSONDecoder().decode(PhoneWidgetSnapshot.self, from: data)
    }

    /// Speichert den Stand; `true`, wenn er sich geändert hat (dann das Widget neu laden).
    @discardableResult
    func save() -> Bool {
        guard self != Self.load(), let data = try? JSONEncoder().encode(self) else { return false }
        Self.defaults?.set(data, forKey: Self.key)
        return true
    }
}
