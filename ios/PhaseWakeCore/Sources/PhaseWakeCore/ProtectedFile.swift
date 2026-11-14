import Foundation

/// Lesen einer Datei, die vor dem ersten Entsperren nach einem Neustart geschützt ist (iPhone, Apple Watch). «Fehlt»
/// heisst leerer Stand (neue Installation), «nicht lesbar» nicht: Wer aus einem nicht lesbaren Stand schreibt, stempelt
/// oder sendet, überschreibt den echten (Prüfrunde 2 HG-1/SP-1/SP-5). Wie auf Android, wo die Dateien erst nach dem
/// Entsperren gelesen werden.
public enum ProtectedFile {
    /// Ergebnis des Lesens.
    public enum Read: Equatable, Sendable {
        /// Keine Datei: leerer Stand.
        case missing
        /// Datei da, aber (noch) nicht lesbar, z. B. vor dem ersten Entsperren: Stand unbekannt, nichts schreiben.
        case unreadable
        /// Inhalt der Datei.
        case data(Data)
    }

    /// Liest `url` und unterscheidet «fehlt» von «nicht lesbar».
    public static func read(_ url: URL) -> Read {
        guard FileManager.default.fileExists(atPath: url.path) else { return .missing }
        do {
            return .data(try Data(contentsOf: url))
        } catch {
            // Zwischen Prüfen und Lesen gelöscht: fehlt.
            return FileManager.default.fileExists(atPath: url.path) ? .unreadable : .missing
        }
    }
}
