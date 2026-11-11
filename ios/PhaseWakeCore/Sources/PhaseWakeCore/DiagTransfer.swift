import Foundation

/// Weg des Diagnose-Berichts der Apple Watch ans iPhone (Gegenstück zu Android `Outbox.sendOnce`): WatchConnectivity
/// lässt für eine Nachricht und ihre Antwort nur etwa 65 KB zu; ein voller Bericht (`DiagLog.maxEntries` Einträge à
/// `DiagLog.maxDetail` Zeichen) ist grösser und ginge verloren. Bis `maxReplyBytes` geht er darum in der Antwort auf
/// `SyncKeys.diagRequest`, sonst als Datei (`transferFile`, Antwort `SyncKeys.diagFile`); das iPhone liest die Datei mit
/// `read(fileAt:)` (höchstens `SyncKeys.maxPeerBytes`). Nur Apple.
public enum DiagTransfer {
    /// Grösster Bericht (Bytes UTF-8) in der Antwort; mit Reserve unter der Grenze von WatchConnectivity (samt Hülle).
    public static let maxReplyBytes = 48 * 1024

    /// Wie lange das iPhone nach der Antwort `SyncKeys.diagFile` auf die Datei wartet (wie Android `WatchReport`).
    public static let fileTimeout: TimeInterval = 15

    /// Ob `text` in die Antwort passt; sonst geht er als Datei.
    public static func fitsReply(_ text: String) -> Bool { text.utf8.count <= maxReplyBytes }

    /// Schreibt `text` in eine neue Datei in `directory` (für `transferFile`); Name mit Kennung, damit sich zwei Anfragen
    /// nicht in die Quere kommen.
    public static func write(_ text: String, in directory: URL) throws -> URL {
        let url = directory.appendingPathComponent("phasewake-diag-\(UUID().uuidString).txt")
        try Data(text.utf8).write(to: url, options: .atomic)
        return url
    }

    /// Liest den Bericht aus einer empfangenen Datei; `nil`, wenn sie unlesbar, grösser als `max` (Audit S-2) oder kein
    /// Bericht ist.
    public static func read(fileAt url: URL, max: Int = SyncKeys.maxPeerBytes) -> DiagReport? {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return nil }
        defer { try? handle.close() }
        // Höchstens ein Byte über der Grenze lesen: nie ohne Grenze in den Speicher.
        // Leere Datei: `nil` (kein Bericht).
        guard let data = try? handle.read(upToCount: max + 1), data.count <= max else { return nil }
        return DiagReports.decode(String(decoding: data, as: UTF8.self))
    }
}
