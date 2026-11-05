import Foundation

/// Lesen mit einem zweiten Versuch: Scheitert auf dem iPhone das Lesen der gestellten AlarmKit-Alarme, liest der
/// Abgleich des Zweitweckers nach einer kurzen Pause noch genau einmal. Erst wenn auch das scheitert, gelten die Alarme
/// als fehlend und werden neu gestellt (lieber doppelt als keiner). Nur Apple.
public enum ReadRetry {
    /// Pause vor dem zweiten Versuch in Sekunden.
    public static let pause: TimeInterval = 1
    /// Höchstzahl der Versuche (der erste und eine Wiederholung).
    public static let attempts = 2

    /// Ob nach dem gescheiterten Versuch `attempt` (ab 1 gezählt) noch einer folgt.
    public static func retries(after attempt: Int) -> Bool { attempt < attempts }

    /// Zeile im Protokoll zum gescheiterten Versuch `attempt` mit Fehlercode `code`, für `what` (z. B. «alarmkit list»).
    public static func failureLine(_ what: String, attempt: Int, code: Int) -> String {
        retries(after: attempt)
            ? "\(what) failed \(code), attempt \(attempt), retry in \(Int(pause)) s"
            : "\(what) failed \(code), attempt \(attempt), treated as missing"
    }

    /// Zeile im Protokoll, wenn der Versuch `attempt` nach einem gescheiterten gelingt.
    public static func recoveredLine(_ what: String, attempt: Int) -> String {
        "\(what) ok, attempt \(attempt)"
    }
}
