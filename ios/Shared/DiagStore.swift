import Foundation
import PhaseWakeCore

/// Diagnose-Protokoll dieses Geräts (iPhone oder Apple Watch): Ringpuffer nach `DiagLog` in einer Datei,
/// nur lokal, nichts wird gesendet (ausser der Nutzer teilt es oder holt es aufs iPhone).
/// Schreiben läuft auf einer eigenen seriellen Warteschlange, nie auf dem Hauptthread.
final class DiagStore: @unchecked Sendable {
    static let shared = DiagStore()

    #if os(watchOS)
    /// Gerät dieser App.
    static let device = DiagDevice.watch
    #else
    /// Gerät dieser App.
    static let device = DiagDevice.phone
    #endif

    private static let unlockedKey = "diagUnlocked"
    private let queue = DispatchQueue(label: "phasewake.diag")
    private let url: URL
    /// Nur auf `queue` gelesen und geschrieben.
    private var cache: [DiagEntry]?

    init(directory: URL? = nil) {
        let dir = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        url = dir.appendingPathComponent("diag.v1")
    }

    /// Ob die Diagnose sichtbar ist (7× auf die Versionszeile getippt; in der Diagnose wieder ausblendbar).
    var unlocked: Bool {
        get { UserDefaults.standard.bool(forKey: Self.unlockedKey) }
        set { UserDefaults.standard.set(newValue, forKey: Self.unlockedKey) }
    }

    /// Hält ein Ereignis fest. `detail` ohne persönliche Daten (keine Namen, keine Messwerte).
    func log(_ kind: DiagKind, _ detail: String = "", at: Date = Date()) {
        let entry = DiagEntry(at: at, device: Self.device, kind: kind, detail: DiagLog.clean(detail))
        queue.async {
            let list = DiagLog.trim(self.loadOnQueue() + [entry], now: at)
            self.cache = list
            self.writeOnQueue(list)
        }
    }

    /// Alle Einträge der letzten 7 Tage, älteste zuerst.
    func entries(now: Date = Date()) -> [DiagEntry] {
        queue.sync { DiagLog.trim(loadOnQueue(), now: now) }
    }

    /// Leert das Protokoll.
    func clear() {
        queue.async {
            self.cache = []
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

    private func loadOnQueue() -> [DiagEntry] {
        if let cache { return cache }
        let text = (try? String(contentsOf: url, encoding: .utf8)) ?? ""
        let list = text.components(separatedBy: "\n").compactMap(DiagLog.decode)
        cache = list
        return list
    }

    private func writeOnQueue(_ list: [DiagEntry]) {
        let text = list.map(DiagLog.encode).joined(separator: "\n")
        // Atomar: Scheitert das Schreiben (Speicher voll), bleibt der alte Stand; das Protokoll ist nur eine Hilfe.
        try? Data(text.utf8).write(to: url, options: .atomic)
    }
}
