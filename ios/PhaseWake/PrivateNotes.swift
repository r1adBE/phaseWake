import Foundation
import os
import PhaseWakeCore

/// Schlafnotizen, eigene Notizen und Befinden zu Nächten aus anderen Apps (nur iPhone): als Dateien im von der
/// Sicherung ausgenommenen Ordner (`PrivateFiles`) statt in `UserDefaults`, das mit der iCloud-Sicherung ginge.
/// Gleiche Formate und Regeln wie die Notiz-Funktionen von `PhaseWakeStorage`. Stände früherer Versionen werden
/// beim Erzeugen einmal aus `UserDefaults` übernommen und dort gelöscht.
final class PrivateNotes: @unchecked Sendable {
    private enum File: String, CaseIterable {
        case notes = "notes.v1"
        case custom = "custom-notes.v1"
        case feelings = "health-feelings.v1"

        /// Schlüssel in `UserDefaults` bis Version 16.2 (`PhaseWakeStorage`).
        var legacyKey: String {
            switch self {
            case .notes: "sleepNotes"
            case .custom: "customNotes"
            case .feelings: "healthFeelings"
            }
        }
    }

    private let lock = NSLock()
    private let directory: URL
    private static let logger = Logger(subsystem: "com.phasewake.app", category: "PrivateNotes")

    /// Namen aller Dateien im Ordner (für das Wiederherstellen in einem Schritt, `BackupIO`).
    static let fileNames = File.allCases.map(\.rawValue)

    /// Erzeugt den Speicher in `directory` und übernimmt alte Stände aus `defaults`; `nil` = nichts übernehmen
    /// (Vorbereiten einer Wiederherstellung in einem eigenen Ordner).
    init(directory: URL = PrivateFiles.directory, defaults: UserDefaults? = .standard) {
        self.directory = directory
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        guard let defaults else { return }
        for file in File.allCases {
            guard let text = defaults.string(forKey: file.legacyKey) else { continue }
            // Schon eine Datei vorhanden: Sie ist neuer. Sonst nur löschen, wenn das Schreiben geklappt hat.
            if read(file) == nil, !write(text, to: file) { continue }
            defaults.removeObject(forKey: file.legacyKey)
        }
    }

    /// Notizen je Nachtdatum «yyyy-MM-dd».
    func notes() -> [String: [String]] {
        lock.lock(); defer { lock.unlock() }
        return SleepNotes.decode(read(.notes) ?? "")
    }

    /// Setzt die Notizen einer Nacht (leer = entfernen); `SleepNotes.encode` behält höchstens 90 Nachtdaten.
    /// - Returns: neuer Stand.
    @discardableResult
    func setNotes(_ ids: [String], nightDate: String) -> [String: [String]] {
        lock.lock(); defer { lock.unlock() }
        var all = SleepNotes.decode(read(.notes) ?? "")
        all[nightDate] = SleepNotes.normalize(ids)
        let text = SleepNotes.encode(all)
        write(text, to: .notes)
        return SleepNotes.decode(text)
    }

    /// Ersetzt die Notizen aller Nächte (Wiederherstellen einer Sicherung). - Returns: neuer Stand.
    @discardableResult
    func replaceNotes(_ notes: [String: [String]]) -> [String: [String]] {
        lock.lock(); defer { lock.unlock() }
        let text = SleepNotes.encode(notes)
        write(text, to: .notes)
        return SleepNotes.decode(text)
    }

    /// Löscht die Notizen aller Nächte und das Befinden zu Nächten aus anderen Apps (mit dem Verlauf); die eigenen
    /// Notizen bleiben zur Auswahl.
    func clearNotes() {
        lock.lock(); defer { lock.unlock() }
        remove(.notes)
        remove(.feelings)
    }

    /// Die eigenen Notizen in gespeicherter Reihenfolge, höchstens 5.
    func customNotes() -> [CustomNote] {
        lock.lock(); defer { lock.unlock() }
        return SleepNotes.decodeCustom(read(.custom) ?? "")
    }

    /// Speichert die eigenen Notizen (höchstens 5). Vergangene Nächte behalten gelöschte Kennungen.
    func setCustomNotes(_ notes: [CustomNote]) {
        lock.lock(); defer { lock.unlock() }
        write(SleepNotes.encodeCustom(notes.filter { !$0.name.isEmpty }), to: .custom)
    }

    /// Befinden (1–5) je Nachtdatum zu Nächten aus anderen Apps, z. B. Apple Health; nie in HealthKit.
    func healthFeelings() -> [String: Int] {
        lock.lock(); defer { lock.unlock() }
        return HealthFeelings.decode(read(.feelings) ?? "")
    }

    /// Setzt das Befinden zur Nacht aus einer anderen App mit Nachtdatum `nightDate`. - Returns: neuer Stand.
    @discardableResult
    func setHealthFeeling(_ feeling: Int, nightDate: String) -> [String: Int] {
        lock.lock(); defer { lock.unlock() }
        let all = HealthFeelings.decode(read(.feelings) ?? "")
        let text = HealthFeelings.encode(HealthFeelings.set(all, date: nightDate, feeling: feeling))
        write(text, to: .feelings)
        return HealthFeelings.decode(text)
    }

    /// Ersetzt das Befinden zu Nächten aus anderen Apps (Wiederherstellen; ältere Sicherung ohne: leer).
    /// - Returns: neuer Stand.
    @discardableResult
    func replaceHealthFeelings(_ feelings: [String: Int]) -> [String: Int] {
        lock.lock(); defer { lock.unlock() }
        let text = HealthFeelings.encode(feelings)
        write(text, to: .feelings)
        return HealthFeelings.decode(text)
    }

    // MARK: Dateien

    private func url(_ file: File) -> URL { directory.appendingPathComponent(file.rawValue) }

    /// Inhalt der Datei; `nil`, wenn es sie (noch) nicht gibt.
    private func read(_ file: File) -> String? {
        try? String(contentsOf: url(file), encoding: .utf8)
    }

    /// Schreibt atomar: Scheitert es (Speicher voll), bleibt der bisherige Stand. - Returns: ob geschrieben.
    @discardableResult
    private func write(_ text: String, to file: File) -> Bool {
        do {
            try Data(text.utf8).write(to: url(file), options: .atomic)
            return true
        } catch {
            Self.logger.error("\(file.rawValue, privacy: .public) nicht gespeichert: \(error.localizedDescription, privacy: .public)")
            return false
        }
    }

    private func remove(_ file: File) {
        try? FileManager.default.removeItem(at: url(file))
    }
}
