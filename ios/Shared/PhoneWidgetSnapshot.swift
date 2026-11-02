import Foundation
import PhaseWakeCore

/// Was das iPhone-Widget zeigt: die nächsten Weckfenster und das Ergebnis des Nacht-Checks, am Weckmorgen bis
/// 12 Uhr stattdessen die letzte Nacht («Details von heute», wie Android).
///
/// Die iPhone-App schreibt den Stand bei jedem Öffnen und alle 30 Sekunden, solange sie offen ist, als Datei in die
/// gemeinsame App-Gruppe, in einen von der iCloud-Sicherung ausgenommenen Ordner (die letzte Nacht mit Schlafdauer und
/// Befinden gehört nicht in die Sicherung); das Widget liest nur. Mehrere Fenster, damit das Widget nach einer Zielzeit von
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
    /// Früherer Ort: `UserDefaults` der App-Gruppe – landete in der iCloud-Sicherung.
    private static let legacyKey = "phoneWidget"
    private static var defaults: UserDefaults? { UserDefaults(suiteName: PhaseWakeStorage.watchGroup) }

    /// Datei des Stands im Ordner «Private» der App-Gruppe; der Ordner ist von der Sicherung ausgenommen.
    /// `nil` ohne App-Gruppe. Einmal je Prozess bestimmt (Ordner anlegen und ausnehmen).
    private static let fileURL: URL? = {
        guard let group = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: PhaseWakeStorage.watchGroup) else {
            return nil
        }
        var dir = group.appendingPathComponent("Private", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? dir.setResourceValues(values)
        return dir.appendingPathComponent("phoneWidget.json")
    }()

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

    /// Gespeicherter Stand; `nil` ohne Stand oder ohne App-Gruppe. Ein Stand im früheren Ort wird einmal in die Datei
    /// übernommen und dort gelöscht; ist er unlesbar, wird er nur gelöscht (die App schreibt gleich einen neuen).
    static func load() -> PhoneWidgetSnapshot? {
        guard let url = fileURL else { return nil }
        if let data = try? Data(contentsOf: url) {
            return try? JSONDecoder().decode(PhoneWidgetSnapshot.self, from: data)
        }
        guard let legacy = defaults?.data(forKey: legacyKey) else { return nil }
        let snapshot = try? JSONDecoder().decode(PhoneWidgetSnapshot.self, from: legacy)
        if snapshot.map({ write($0, to: url) }) ?? true { defaults?.removeObject(forKey: legacyKey) }
        return snapshot
    }

    /// Speichert den Stand; `true`, wenn er sich geändert hat (dann das Widget neu laden).
    @discardableResult
    func save() -> Bool {
        guard self != Self.load(), let url = Self.fileURL else { return false }
        return Self.write(self, to: url)
    }

    /// Schreibt den Stand atomar; lesbar ab der ersten Entsperrung (das Widget lädt auch bei gesperrtem Gerät).
    private static func write(_ snapshot: PhoneWidgetSnapshot, to url: URL) -> Bool {
        guard let data = try? JSONEncoder().encode(snapshot) else { return false }
        do {
            try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            return true
        } catch {
            return false
        }
    }
}
