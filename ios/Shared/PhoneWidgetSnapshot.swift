import Foundation
import PhaseWakeCore

/// Was das iPhone-Widget zeigt: die nächsten Weckfenster und das Ergebnis des Nacht-Checks, am Weckmorgen bis
/// 12 Uhr stattdessen die letzte Nacht («Details von heute», wie Android).
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
        /// «Genau»: klingelt zur Zielzeit, ohne Weckfenster. Optional, damit ein Stand älterer Versionen
        /// (ohne das Feld) weiter lesbar bleibt; `nil` = kein genauer Wecker.
        var exact: Bool?
    }

    /// Die letzte Nacht, wie sie der Morgenbericht zeigt; Texte schon übersetzt (wie ``issue``).
    struct LastNight: Codable, Equatable {
        /// Zeitpunkt des Weckens.
        let wokeAt: Date
        /// Zeitpunkt und Grund, z. B. «12 Min vor der Zielzeit · Unruhig – wohl leichter Schlaf».
        let reason: String
        /// «Befinden: Gut»; `nil` ohne Angabe.
        var feeling: String?
        /// Geschlafene Minuten dieser Nacht laut Apple Health (`NoteEffect.asleepByNight` zu ihrem Nachtdatum, wie
        /// Verlauf und Detailseite); `nil` ohne Daten. Optional, damit ein Stand älterer Versionen lesbar bleibt.
        var asleepMinutes: Int?
    }

    /// Kommende Fenster, nach Zielzeit sortiert.
    var plans: [Plan]
    /// Titel der ersten offenen Prüfung; leer = bereit für die Nacht, `nil` = noch kein Check gelaufen.
    var issue: String?
    /// Jüngste Nacht. Optional, damit ein Stand älterer Versionen (ohne das Feld) weiter lesbar bleibt.
    var lastNight: LastNight?

    /// Bis zu dieser Stunde (Ortszeit) zeigt das Widget am Weckmorgen die letzte Nacht (wie der Morgenbericht).
    static let morningEndHour = 12

    /// Art des Widgets (für das Neuladen).
    static let kind = "PhoneNextAlarm"
    private static let key = "phoneWidget"
    private static var defaults: UserDefaults? { UserDefaults(suiteName: PhaseWakeStorage.watchGroup) }

    /// Erstes Fenster, dessen Zielzeit noch nicht vorbei ist.
    func next(after now: Date) -> Plan? { plans.first { $0.target > now } }

    /// Die letzte Nacht, falls sie heute geweckt hat und es noch vor 12 Uhr ist; sonst `nil` (dann der nächste Wecker).
    func morningNight(at now: Date, calendar: Calendar = .current) -> LastNight? {
        guard let night = lastNight, now >= night.wokeAt, calendar.isDate(night.wokeAt, inSameDayAs: now),
              calendar.component(.hour, from: now) < Self.morningEndHour else { return nil }
        return night
    }

    /// 12 Uhr am Weckmorgen der letzten Nacht (dort wechselt das Widget zurück); `nil` ohne Nacht.
    func morningEnd(calendar: Calendar = .current) -> Date? {
        lastNight.flatMap { calendar.date(bySettingHour: Self.morningEndHour, minute: 0, second: 0, of: $0.wokeAt) }
    }

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
