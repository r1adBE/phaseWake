import Foundation
import PhaseWakeCore

/// Merker einer laufenden Wiederherstellung (wie der Restore-Marker auf Android). Verlauf und Notizen werden Datei für
/// Datei getauscht, danach folgen Wecker und Einstellungen; stirbt die App mittendrin (beendet, abgestürzt, Akku leer),
/// bliebe sonst ein Gemisch aus altem und neuem Stand. Darum liegt alles für den Tausch in einem festen Arbeitsordner
/// neben `PrivateFiles` (gleiches Volume, Tauschen bleibt ein atomares Umbenennen): die neuen Dateien (`staging`),
/// Kopien der bisherigen (`rollback`), der Inhalt der Sicherung (für Wecker und Einstellungen) und der Merker.
///
/// Ab dem Merker gilt die Wiederherstellung als begonnen. ``recover(model:)`` führt sie beim nächsten Start zu Ende
/// oder holt, wenn das nicht geht, den alten Stand aus den Kopien zurück. Die Kopien bleiben, bis eines davon ganz
/// gelungen ist. Die Phase steckt im Namen der Merker-Datei: Der Wechsel zum Zurückholen ist ein Umbenennen und klappt
/// auch bei vollem Speicher.
@MainActor
enum RestoreJournal {
    /// Was getauscht wird.
    struct Marker: Codable, Equatable {
        /// Dateien im Ordner `PrivateFiles`, die die Sicherung ersetzt.
        let names: [String]
        /// Davon die, die es vorher gab (Kopie in `rollback`); die übrigen werden beim Zurückholen entfernt.
        let existed: [String]
    }

    /// Arbeitsordner «Application Support/RestoreWork», von der Sicherung ausgenommen.
    static var work: URL {
        PrivateFiles.directory.deletingLastPathComponent().appendingPathComponent("RestoreWork", isDirectory: true)
    }
    /// Neue Dateien aus der Sicherung, bereit zum Tauschen.
    static var staging: URL { work.appendingPathComponent("staging", isDirectory: true) }
    /// Kopien der bisherigen Dateien.
    static var rollback: URL { work.appendingPathComponent("rollback", isDirectory: true) }
    /// Merker «Tausch läuft»: beim nächsten Start zu Ende führen.
    private static var swappingFile: URL { work.appendingPathComponent("swapping.json") }
    /// Merker «Tausch gescheitert»: beim nächsten Start den alten Stand zurückholen.
    private static var rollingBackFile: URL { work.appendingPathComponent("rolling-back.json") }
    /// Inhalt der Sicherung (für Wecker und Einstellungen nach dem Tausch).
    private static var payloadFile: URL { work.appendingPathComponent("payload.json") }

    /// Legt den Arbeitsordner frisch an; Reste eines Abbruchs vor dem Merker (bisheriger Stand unberührt) gehen weg.
    /// - Throws: wenn noch eine begonnene Wiederherstellung offen ist (Merker da) oder der Ordner nicht entsteht.
    static func prepare() throws {
        let manager = FileManager.default
        for marker in [swappingFile, rollingBackFile] where manager.fileExists(atPath: marker.path) {
            throw CocoaError(.fileWriteFileExists, userInfo: [NSFilePathErrorKey: marker.path])
        }
        try? manager.removeItem(at: work)
        try manager.createDirectory(at: staging, withIntermediateDirectories: true)
        try manager.createDirectory(at: rollback, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var dir = work
        try? dir.setResourceValues(values)
    }

    /// Kopiert die bisherigen Dateien `names` nach `rollback` (auf APFS ein Klon ohne zusätzlichen Platz).
    /// - Returns: Merker für den Tausch. - Throws: wenn eine Kopie scheitert.
    static func copyLive(_ names: [String]) throws -> Marker {
        let manager = FileManager.default
        var existed: [String] = []
        for name in names {
            let live = PrivateFiles.directory.appendingPathComponent(name)
            guard manager.fileExists(atPath: live.path) else { continue }
            try manager.copyItem(at: live, to: rollback.appendingPathComponent(name))
            existed.append(name)
        }
        return Marker(names: names, existed: existed)
    }

    /// Merkt den Beginn: zuerst der Inhalt der Sicherung, dann der Merker, beides atomar geschrieben.
    /// - Throws: wenn eines nicht geschrieben werden konnte; dann ist noch nichts getauscht.
    static func begin(_ marker: Marker, payload: BackupPayload) throws {
        try Data(payload.toJson().utf8).write(to: payloadFile, options: .atomic)
        try JSONEncoder().encode(marker).write(to: swappingFile, options: .atomic)
    }

    /// Setzt jede Datei aus `staging` an ihren Platz, die noch dort liegt; die übrigen sind schon getauscht.
    /// Wiederholbar. - Throws: wenn ein Tausch scheitert.
    static func swap(_ marker: Marker) throws {
        let manager = FileManager.default
        for name in marker.names {
            let source = staging.appendingPathComponent(name)
            guard manager.fileExists(atPath: source.path) else { continue }
            let target = PrivateFiles.directory.appendingPathComponent(name)
            try place(source, at: target, replacing: manager.fileExists(atPath: target.path))
        }
    }

    /// Holt den alten Stand zurück: Kopien an ihren Platz, Dateien, die es vorher nicht gab, weg. Wiederholbar: Was
    /// schon zurück ist, hat keine Kopie mehr. Erst wenn alles geklappt hat, verschwinden Merker und Kopien; sonst
    /// versucht es der nächste Start wieder.
    /// - Returns: `true`, wenn der alte Stand vollständig zurück ist.
    @discardableResult
    static func rollBack(_ marker: Marker, model: PhoneModel) -> Bool {
        let manager = FileManager.default
        if manager.fileExists(atPath: swappingFile.path) {
            // Nur Umbenennen (braucht keinen Platz): Ein Abbruch ab hier holt beim nächsten Start zurück.
            try? manager.moveItem(at: swappingFile, to: rollingBackFile)
        }
        var complete = true
        for name in marker.names {
            let target = PrivateFiles.directory.appendingPathComponent(name)
            let copy = rollback.appendingPathComponent(name)
            do {
                if marker.existed.contains(name) {
                    if manager.fileExists(atPath: copy.path) {
                        try place(copy, at: target, replacing: manager.fileExists(atPath: target.path))
                    }
                } else if manager.fileExists(atPath: target.path) {
                    try manager.removeItem(at: target)
                }
            } catch {
                complete = false
                DiagStore.shared.log(.error, "restore rollback \(name): \(error.localizedDescription)")
            }
        }
        if complete { finish() }
        model.loadState()
        return complete
    }

    /// Räumt auf: zuerst die Merker (danach gibt es nichts mehr nachzuholen), dann Kopien und Arbeitsordner.
    static func finish() {
        let manager = FileManager.default
        try? manager.removeItem(at: swappingFile)
        try? manager.removeItem(at: rollingBackFile)
        try? manager.removeItem(at: work)
    }

    /// Beim Start und beim Öffnen: eine abgebrochene Wiederherstellung zu Ende führen (Dateien tauschen, dann Wecker
    /// und Einstellungen, ``BackupIO/applySettings(_:to:)``) oder, wenn das nicht geht, zurückholen. Ohne Merker
    /// verwirft es nur Reste. Ist der Merker nicht lesbar (z. B. vor dem ersten Entsperren), bleibt alles liegen und
    /// der nächste Aufruf versucht es wieder.
    static func recover(model: PhoneModel) {
        let manager = FileManager.default
        guard manager.fileExists(atPath: work.path) else { return }
        let rollingBack = manager.fileExists(atPath: rollingBackFile.path)
        guard rollingBack || manager.fileExists(atPath: swappingFile.path) else {
            finish()
            return
        }
        guard let data = try? Data(contentsOf: rollingBack ? rollingBackFile : swappingFile),
              let marker = try? JSONDecoder().decode(Marker.self, from: data) else {
            DiagStore.shared.log(.error, "restore marker unreadable, retry later")
            return
        }
        if rollingBack {
            let done = rollBack(marker, model: model)
            DiagStore.shared.log(done ? DiagKind.sync : .error, done ? "restore rolled back after restart" : "restore rollback pending")
            return
        }
        do {
            try swap(marker)
            let stored = try Data(contentsOf: payloadFile)
            let payload = try BackupPayload.fromJson(String(decoding: stored, as: UTF8.self))
            // Schon gespeichert (mit einer späteren Änderung): nicht noch einmal, sonst gingen neuere Einstellungen
            // verloren. Nicht gespeichert: Merker bleibt, der nächste Start versucht es wieder.
            guard BackupIO.settingsApplied(payload) || BackupIO.applySettings(payload, to: model) else { return }
            finish()
            DiagStore.shared.log(.sync, "restore finished after restart")
        } catch {
            DiagStore.shared.log(.error, "restore not finished, rolling back: \(error.localizedDescription)")
            rollBack(marker, model: model)
        }
    }

    /// Setzt `source` an die Stelle von `target`: atomar ersetzen oder, ohne bisherige Datei, verschieben.
    private static func place(_ source: URL, at target: URL, replacing: Bool) throws {
        if replacing {
            _ = try FileManager.default.replaceItemAt(target, withItemAt: source)
        } else {
            try FileManager.default.moveItem(at: source, to: target)
        }
    }
}
