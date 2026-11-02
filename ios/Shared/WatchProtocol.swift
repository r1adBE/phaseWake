import Foundation
import PhaseWakeCore

extension SyncKeys {
    /// Uhr → iPhone im Anwendungskontext: Stand der Verbindung, den die Uhr-App kennt (`WatchProtocol.current`).
    /// Ältere Uhr-Versionen schicken ihn nicht; dann gilt der Stand als unbekannt.
    static let watchProtocol = "watchProtocol"
}

/// Stand der Verbindung iPhone ↔ Apple Watch. iPhone- und Uhr-App kommen aus demselben Projekt, die Build-Nummer ist
/// aber nicht verlässlich (lokal immer 1), darum eine eigene Zahl: Sie steigt, sobald die iPhone-App etwas voraussetzt,
/// das eine ältere Uhr-App nicht kann (neues Feld, neuer Befehl, geänderte Regel). Der Nacht-Check auf dem iPhone
/// warnt, wenn die Uhr einen kleineren Stand meldet (`isOutdated`).
enum WatchProtocol {
    /// Stand dieser Version. Erhöhen, wenn die Uhr-App für das iPhone aktualisiert werden muss.
    static let current = 1

    /// Ob eine Uhr mit dem gemeldeten Stand `watch` älter ist als diese iPhone-App. Tolerant: `nil` (nie gemeldet,
    /// ältere Uhr ohne die Angabe, noch kein Kontext) warnt nicht; eine Uhr ohne Überspringen und Feiertage meldet
    /// der Nacht-Check ohnehin (`watchKnowsSkip`).
    static func isOutdated(watch: Int?, phone: Int = current) -> Bool {
        guard let watch else { return false }
        return watch < phone
    }
}
