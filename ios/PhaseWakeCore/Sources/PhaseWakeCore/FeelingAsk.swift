import Foundation

/// Befinden auf der Uhr direkt nach dem Wecken (Apple Watch und Galaxy Watch gleich, Entscheid 23.11.2026): Nach dem
/// Stopp zeigt die Uhr «Wie fühlst du dich?» als eigenen Bildschirm, still (ohne Ton und Vibration), höchstens
/// ``seconds`` lang. Die Wahl reist mit der Nacht zum iPhone über den Weg der Nächte (`transferUserInfo`, zugestellt,
/// sobald das iPhone erreichbar ist); dort gilt sie wie eine Eingabe am iPhone (gleiche Nacht, gleiches Feld). Gleich
/// in Kotlin (`FeelingAsk`).
public enum FeelingAsk {
    /// So lange bleibt die Abfrage ohne Eingabe stehen, in Sekunden; danach verschwindet sie ohne Wahl.
    public static let seconds: TimeInterval = 60

    /// Ob die Uhr nach dem Ende der Nacht fragt: nur nach echtem Wecken (`recorded`: geklingelt, kein Probewecker und
    /// kein Powernap, die Nacht kommt in den Verlauf) und nur, wenn auf der Uhr gestoppt wurde (`stoppedOnWatch`;
    /// nicht am iPhone, nicht nach einem Abbruch). Ein schon bekanntes Befinden der Nacht (`known`) wird nicht noch
    /// einmal erfragt.
    public static func asks(recorded: Bool, stoppedOnWatch: Bool, known: Bool = false) -> Bool {
        recorded && stoppedOnWatch && !known
    }

    /// Ob die Abfrage, gestellt um `askedAt`, um `now` noch gilt: ab dem Stellen weniger als ``seconds`` lang. Läuft
    /// die Uhrzeit zurück (vor `askedAt`), gilt sie nicht mehr: lieber keine Abfrage als eine, die stehen bleibt.
    public static func isOpen(askedAt: Date, now: Date) -> Bool {
        let elapsed = now.timeIntervalSince(askedAt)
        return elapsed >= 0 && elapsed < seconds
    }

    /// Befinden, wenn dieselbe Nacht noch einmal ankommt (iPhone: die Uhr schickt sie mit ihrer Wahl erneut): Ein
    /// schon gespeichertes `stored` (am iPhone angegeben oder früher übernommen) hat Vorrang vor dem ankommenden
    /// `incoming`. So überschreibt eine verspätet zugestellte Wahl der Uhr nie eine Eingabe am iPhone, und dieselbe
    /// Nacht zweimal ändert nichts. `nil` = keines.
    public static func merged(stored: Int?, incoming: Int?) -> Int? {
        stored ?? incoming
    }
}
