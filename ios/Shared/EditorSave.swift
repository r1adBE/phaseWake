import Foundation

/// Was «Speichern» in einem Wecker-Editor tut, wenn sich die Wecker anderswo geändert haben (Prüfrunde 2, B-3 und B-11;
/// wie `editorSave` der Galaxy Watch und `EditorRoute` am Handy). Rein, ohne Plattform (testbar).
enum EditorSave: Equatable {
    /// Speichern wie gewohnt.
    case save
    /// Der Wecker bestand beim Öffnen und ist inzwischen gelöscht (Uhr, iPhone): Hinweis, Editor zu, nichts anlegen.
    case gone
    /// Neuer Wecker, aber alle Plätze sind inzwischen belegt: Hinweis auf die Höchstzahl, der Editor bleibt offen.
    case full

    /// Entscheid für den Entwurf `draftId`; `existed`: beim Öffnen ein gespeicherter Wecker, `storedIds`: gespeicherte Wecker.
    static func check(existed: Bool, storedIds: [String], draftId: String, maxAlarms: Int) -> EditorSave {
        if storedIds.contains(draftId) { return .save }
        if existed { return .gone }
        return storedIds.count >= maxAlarms ? .full : .save
    }
}
