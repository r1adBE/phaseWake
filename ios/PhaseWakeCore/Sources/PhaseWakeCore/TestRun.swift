import Foundation

/// Schritte, die der Probewecker prüft (Ergebnis am iPhone). Rohwerte und Reihenfolge wie die Kotlin-Namen.
public enum TestStep: String, CaseIterable, Comparable, Sendable {
    /// Die Uhr hat den Probewecker empfangen (Bestätigung der Uhr).
    case received = "RECEIVED"
    /// Die Uhr hat geklingelt (Meldung «klingeln»).
    case watchRang = "WATCH_RANG"
    /// Das iPhone hat mitgeklingelt (Zweitwecker gestartet).
    case phoneRang = "PHONE_RANG"
    /// Das Beenden (Halten auf der Uhr) kam am iPhone an (Meldung «stop»).
    case stopArrived = "STOP_ARRIVED"

    /// Ordnet wie die Kotlin-Aufzählung (für das Format).
    public static func < (lhs: TestStep, rhs: TestStep) -> Bool { lhs.order < rhs.order }

    private var order: Int {
        switch self {
        case .received: 0
        case .watchRang: 1
        case .phoneRang: 2
        case .stopArrived: 3
        }
    }
}

/// Ein laufender oder beendeter Probewecker am iPhone. Identisch zu `TestRun` (Kotlin).
public struct TestRun: Equatable, Sendable {
    /// Kennung des Probeweckers (`Transient.testPrefix` + UUID).
    public let id: String
    /// Zielzeit des Probeweckers.
    public let target: Date
    /// Weckart des iPhones; bei `.off` wird «Handy hat mitgeklingelt» nicht geprüft.
    public let phoneAlert: PhoneAlert
    /// Zeitpunkt, zu dem das iPhone einen Schritt zuerst gesehen hat.
    public let seen: [TestStep: Date]

    /// Erzeugt einen Lauf.
    public init(id: String, target: Date, phoneAlert: PhoneAlert, seen: [TestStep: Date] = [:]) {
        self.id = id
        self.target = target
        self.phoneAlert = phoneAlert
        self.seen = seen
    }
}

/// Auswertung und Format von `TestRun`. Identisch zu `TestRuns` (Kotlin).
public enum TestRuns {
    /// So lange nach der Zielzeit (Sekunden, 3 Minuten) wird auf Ereignisse gewartet, bevor das Ergebnis ohne «stop» gilt.
    public static let resultAfterTarget: TimeInterval = 3 * 60

    /// Merkt `step` zum Zeitpunkt `at`; das erste Auftreten zählt.
    public static func record(_ run: TestRun, _ step: TestStep, at: Date) -> TestRun {
        guard run.seen[step] == nil else { return run }
        var seen = run.seen
        seen[step] = at
        return TestRun(id: run.id, target: run.target, phoneAlert: run.phoneAlert, seen: seen)
    }

    /// Ob das Ergebnis feststeht: «stop» kam an, oder `resultAfterTarget` nach der Zielzeit ist vorbei.
    public static func isFinished(_ run: TestRun, now: Date) -> Bool {
        run.seen[.stopArrived] != nil || now >= run.target.addingTimeInterval(resultAfterTarget)
    }

    /// Ergebnis eines Schritts: erfüllt, nicht erfüllt, oder `nil` = nicht geprüft (iPhone-Wecker aus).
    public static func result(_ run: TestRun, _ step: TestStep) -> Bool? {
        if step == .phoneRang && run.phoneAlert == .off { return nil }
        return run.seen[step] != nil
    }

    /// «kennung|zielzeitMs|HANDY|SCHRITT=ms,SCHRITT=ms».
    public static func encode(_ run: TestRun) -> String {
        [
            run.id, String(AlarmSchedule.millis(run.target)), run.phoneAlert.rawValue,
            run.seen.sorted { $0.key < $1.key }.map { "\($0.key.rawValue)=\(AlarmSchedule.millis($0.value))" }.joined(separator: ","),
        ].joined(separator: "|")
    }

    /// Liest `encode`; leer oder unlesbar ergibt `nil`, unbekannte Schritte fallen weg.
    public static func decode(_ text: String?) -> TestRun? {
        guard let f = text?.components(separatedBy: "|"), f.count >= 4, let target = Int64(f[1]) else { return nil }
        let alert = PhoneAlert(rawValue: f[2]) ?? .sound
        var seen: [TestStep: Date] = [:]
        for pair in f[3].components(separatedBy: ",") {
            let parts = pair.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false).map(String.init)
            guard let step = TestStep(rawValue: parts[0]), parts.count == 2, let ms = Int64(parts[1]) else { continue }
            seen[step] = Date(timeIntervalSince1970: TimeInterval(ms) / 1000)
        }
        return TestRun(id: f[0], target: Date(timeIntervalSince1970: TimeInterval(target) / 1000), phoneAlert: alert, seen: seen)
    }
}
