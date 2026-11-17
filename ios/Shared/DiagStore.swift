import Foundation
import PhaseWakeCore

/// Diagnose-Protokoll dieses Geräts (iPhone oder Apple Watch): Ringpuffer nach `DiagLog` in einer Datei,
/// nur lokal, nichts wird gesendet (ausser der Nutzer teilt es oder holt es aufs iPhone).
/// Schreiben läuft auf einer eigenen seriellen Warteschlange, nie auf dem Hauptthread. Jeder Eintrag wird an die Datei
/// angehängt; erst ab `DiagLog.maxEntries` plus ``slack`` Zeilen wird sie gekürzt und neu geschrieben (wie
/// `DiagnosticsLog` auf Android).
final class DiagStore: @unchecked Sendable {
    static let shared = DiagStore()

    #if os(watchOS)
    /// Gerät dieser App.
    static let device = DiagDevice.watch
    #else
    /// Gerät dieser App.
    static let device = DiagDevice.phone
    #endif

    /// So viele Zeilen über `DiagLog.maxEntries`, bevor gekürzt wird (seltener umschreiben, wie `SLACK` auf Android).
    static let slack = 100

    private static let unlockedKey = "diagUnlocked"
    private let queue = DispatchQueue(label: "phasewake.diag")
    private let url: URL
    /// Einträge der Datei in ihrer Reihenfolge; `nil` = noch nicht gelesen. Nur auf `queue` gelesen und geschrieben.
    private var cache: [DiagEntry]?
    /// Ob die Datei ohne Zeilenumbruch endet: Frühere Versionen trennten die Zeilen nur (ohne Umbruch am Ende); der
    /// erste angehängte Eintrag beginnt dann mit einem. Nur auf `queue`.
    private var needsBreak = false
    /// Einträge, die noch nicht in der Datei stehen: Datei nicht lesbar (vor dem ersten Entsperren) oder Schreiben
    /// gescheitert (Speicher voll). Der nächste Eintrag versucht es erneut. Nur auf `queue`.
    private var pending: [DiagEntry] = []

    /// `directory` = Ordner des Protokolls; Standard ist der von der Sicherung ausgenommene `PrivateFiles.directory`
    /// (ein Protokoll früherer Versionen aus Application Support wird dorthin übernommen).
    init(directory: URL? = nil) {
        let dir = directory ?? PrivateFiles.adopt(["diag.v1"])
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        url = dir.appendingPathComponent("diag.v1")
    }

    /// Ob die Diagnose sichtbar ist (7× auf die Versionszeile getippt; in der Diagnose wieder ausblendbar).
    var unlocked: Bool {
        get { UserDefaults.standard.bool(forKey: Self.unlockedKey) }
        set { UserDefaults.standard.set(newValue, forKey: Self.unlockedKey) }
    }

    /// Hält ein Ereignis fest. `detail` ohne persönliche Daten (keine Namen, keine einzelnen Messwerte – höchstens Kennzahlen wie Mittelwert oder Akku).
    func log(_ kind: DiagKind, _ detail: String = "", at: Date = Date()) {
        let entry = DiagEntry(at: at, device: Self.device, kind: kind, detail: DiagLog.clean(detail))
        queue.async {
            self.pending.append(entry)
            self.flushOnQueue()
        }
    }

    /// Alle Einträge der letzten 7 Tage, älteste zuerst.
    func entries(now: Date = Date()) -> [DiagEntry] {
        queue.sync { DiagLog.trim((loadOnQueue() ?? []) + pending, now: now) }
    }

    /// Leert das Protokoll.
    func clear() {
        queue.async {
            self.pending = []
            self.cache = []
            self.needsBreak = false
            do {
                try FileManager.default.removeItem(at: self.url)
            } catch CocoaError.fileNoSuchFile {
                // Noch nie geschrieben.
            } catch {
                // Ohne Löschen bleibt der Ringpuffer; er wird beim nächsten Schreiben ohnehin gekürzt.
                self.cache = nil
            }
        }
    }

    /// Hängt `pending` an die Datei an; über `DiagLog.maxEntries` + ``slack`` Zeilen gekürzt neu geschrieben. Datei
    /// nicht lesbar: Die Einträge bleiben gemerkt (höchstens `DiagLog.maxEntries`), nichts wird überschrieben.
    private func flushOnQueue() {
        guard !pending.isEmpty else { return }
        guard let list = loadOnQueue() else {
            pending = Array(pending.suffix(DiagLog.maxEntries))
            return
        }
        let all = list + pending
        if all.count > DiagLog.maxEntries + Self.slack {
            let kept = DiagLog.trim(all, now: Date())
            guard writeOnQueue(kept) else { return }
            cache = kept
        } else {
            let text = (needsBreak ? "\n" : "") + pending.map { DiagLog.encode($0) + "\n" }.joined()
            guard appendOnQueue(text) else {
                // Vielleicht halb geschrieben: Beim nächsten Mal die Datei neu lesen (Zeilenende prüfen).
                cache = nil
                return
            }
            cache = all
        }
        needsBreak = false
        pending = []
    }

    /// Einträge der Datei (gemerkt); `nil` = Datei da, aber nicht lesbar (dann nie überschreiben). Liest beide Formate:
    /// Zeilen mit Umbruch am Ende (seit dem Anhängen) und nur getrennte Zeilen (frühere Versionen).
    private func loadOnQueue() -> [DiagEntry]? {
        if let cache { return cache }
        let text: String
        switch ProtectedFile.read(url) {
        case .missing: text = ""
        case .unreadable: return nil
        case let .data(data): text = String(decoding: data, as: UTF8.self)
        }
        let list = text.components(separatedBy: "\n").compactMap(DiagLog.decode)
        needsBreak = !text.isEmpty && !text.hasSuffix("\n")
        cache = list
        return list
    }

    /// Hängt `text` an (legt die Datei an, falls sie fehlt). - Returns: ob es geklappt hat.
    private func appendOnQueue(_ text: String) -> Bool {
        do {
            guard FileManager.default.fileExists(atPath: url.path) else {
                try Data(text.utf8).write(to: url, options: .atomic)
                return true
            }
            let handle = try FileHandle(forWritingTo: url)
            defer { try? handle.close() }
            _ = try handle.seekToEnd()
            try handle.write(contentsOf: Data(text.utf8))
            return true
        } catch {
            // Speicher voll oder nicht beschreibbar: Das Protokoll ist nur eine Hilfe, der Wecker läuft weiter.
            return false
        }
    }

    /// Schreibt `list` neu, je Eintrag eine Zeile mit Umbruch. Atomar: Scheitert das Schreiben (Speicher voll), bleibt
    /// der alte Stand. - Returns: ob es geklappt hat.
    private func writeOnQueue(_ list: [DiagEntry]) -> Bool {
        let text = list.map { DiagLog.encode($0) + "\n" }.joined()
        do {
            try Data(text.utf8).write(to: url, options: .atomic)
            return true
        } catch {
            return false
        }
    }
}
