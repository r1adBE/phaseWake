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
    /// 2 (10.11.2026): Lernphase – die Uhr zeichnet mit dem Bewegungs-Rekorder auf (`learnRecording`).
    /// 3 (10.11.2026): «Nacht aufzeichnen» (`recordStart`, `recordStop`, `recordSince`) und «Gute Nacht» (`onsetShift`).
    static let current = 3

    /// Ab diesem Stand kennt die Uhr-App die Lernphase; vorher zeigt das iPhone sie nicht an.
    static let learning = 2

    /// Ab diesem Stand kennt die Uhr-App «Nacht aufzeichnen»; vorher zeigt das iPhone die Karte nicht.
    static let recording = 3

    /// Ob eine Uhr mit dem gemeldeten Stand `watch` älter ist als diese iPhone-App. Tolerant: `nil` (nie gemeldet,
    /// ältere Uhr ohne die Angabe, noch kein Kontext) warnt nicht; eine Uhr ohne Überspringen und Feiertage meldet
    /// der Nacht-Check ohnehin (`watchKnowsSkip`).
    static func isOutdated(watch: Int?, phone: Int = current) -> Bool {
        guard let watch else { return false }
        return watch < phone
    }
}
