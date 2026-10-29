import Foundation

/// Gerät, das ein Ereignis protokolliert hat. Rohwert = Kürzel im Format von `DiagLog.encode` (wie Kotlin).
public enum DiagDevice: String, CaseIterable, Sendable {
    /// Handy (iPhone oder Android).
    case phone = "P"
    /// Uhr (Apple Watch oder Galaxy Watch).
    case watch = "W"

    /// Name in Kleinbuchstaben für den lesbaren Text («phone», «watch»).
    public var name: String { self == .phone ? "phone" : "watch" }
}

/// Art eines Ereignisses im Diagnose-Protokoll. Rohwerte wie die Kotlin-Namen.
public enum DiagKind: String, CaseIterable, Sendable {
    /// Wecker empfangen (von der Gegenseite).
    case alarmsReceived = "ALARMS_RECEIVED"
    /// Wecker oder Sicherung geplant.
    case scheduled = "SCHEDULED"
    /// Abgleich gesendet oder empfangen.
    case sync = "SYNC"
    /// Weckfenster geöffnet.
    case window = "WINDOW"
    /// Messung gestartet.
    case measureStart = "MEASURE_START"
    /// Messung beendet.
    case measureEnd = "MEASURE_END"
    /// Phase erkannt (leichter Schlaf, wach).
    case phase = "PHASE"
    /// Klingeln (wer und warum im Text).
    case ring = "RING"
    /// Weckbildschirm: erwarteter Weg (`RingRoute`), Startversuch und ob er zu sehen ist (nur Android).
    case ringScreen = "RING_SCREEN"
    /// Schlummern.
    case snooze = "SNOOZE"
    /// Stopp.
    case stop = "STOP"
    /// Zweitwecker ausgesetzt (die Uhr hat die Nacht übernommen).
    case suppressed = "SUPPRESSED"
    /// Neustart des Geräts oder der App.
    case boot = "BOOT"
    /// Vor dem ersten Entsperren (nur Android).
    case directBoot = "DIRECT_BOOT"
    /// Akkustand.
    case battery = "BATTERY"
    /// Probewecker.
    case test = "TEST"
    /// Powernap.
    case nap = "NAP"
    /// Fehler oder Ausnahme.
    case error = "ERROR"
    /// Unbekannte Art (aus einer neueren Version).
    case other = "OTHER"
}

/// Ein Eintrag im Diagnose-Protokoll; `detail` ohne persönliche Daten (keine Namen, keine Messwerte).
public struct DiagEntry: Equatable, Sendable {
    /// Zeitpunkt des Ereignisses.
    public let at: Date
    /// Gerät, das es protokolliert hat.
    public let device: DiagDevice
    /// Art des Ereignisses.
    public let kind: DiagKind
    /// Kurzer Text (höchstens `DiagLog.maxDetail` Zeichen nach `DiagLog.clean`).
    public let detail: String

    /// Erzeugt einen Eintrag.
    public init(at: Date, device: DiagDevice, kind: DiagKind, detail: String) {
        self.at = at
        self.device = device
        self.kind = kind
        self.detail = detail
    }
}

/// Diagnose-Protokoll: nur lokal, ein Ringpuffer der letzten `maxEntries` Ereignisse aus höchstens `maxAge`.
/// Eine Zeile je Eintrag: «ms|P|ART|Text». Auf allen Geräten gleich (wie `DiagLog` in Kotlin).
public enum DiagLog {
    /// Höchstens so viele Einträge (die neuesten bleiben).
    public static let maxEntries = 500
    /// Älter als das (Sekunden, 7 Tage) fällt weg.
    public static let maxAge: TimeInterval = 7 * 24 * 3600
    /// Längster Text eines Eintrags in Zeichen.
    public static let maxDetail = 200

    private static let separator = "|"

    /// Eine Zeile; «|» und Zeilenumbrüche im Text werden zu Leerzeichen, zu lange Texte gekürzt.
    public static func encode(_ entry: DiagEntry) -> String {
        [String(AlarmSchedule.millis(entry.at)), entry.device.rawValue, entry.kind.rawValue, clean(entry.detail)].joined(separator: separator)
    }

    /// Liest `encode`; unbekannte Art wird `.other`, eine kaputte Zeile `nil`.
    public static func decode(_ line: String) -> DiagEntry? {
        let f = line.split(separator: "|", maxSplits: 3, omittingEmptySubsequences: false).map(String.init)
        guard f.count >= 3, let ms = Int64(f[0]), let device = DiagDevice(rawValue: f[1]) else { return nil }
        return DiagEntry(at: Date(timeIntervalSince1970: TimeInterval(ms) / 1000), device: device,
                         kind: DiagKind(rawValue: f[2]) ?? .other, detail: f.count > 3 ? f[3] : "")
    }

    /// Nach Zeit sortiert, ohne Einträge älter als `maxAge` vor `now`, höchstens die neuesten `maxEntries`.
    public static func trim(_ entries: [DiagEntry], now: Date) -> [DiagEntry] {
        let from = now.addingTimeInterval(-maxAge)
        return Array(stableSorted(entries.filter { $0.at >= from }).suffix(maxEntries))
    }

    /// Lesbarer Text zum Teilen: «2026-10-29 07:12:03 phone RING Text» je Zeile, älteste zuerst.
    public static func format(_ entries: [DiagEntry], timeZone: TimeZone) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.timeZone = timeZone
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss"
        return stableSorted(entries).map { e in
            [formatter.string(from: e.at), e.device.name, e.kind.rawValue, e.detail].filter { !$0.isEmpty }.joined(separator: " ")
        }.joined(separator: "\n")
    }

    /// Text ohne Trennzeichen und Zeilenumbrüche, höchstens `maxDetail` Zeichen.
    public static func clean(_ detail: String) -> String {
        let flat = detail.replacingOccurrences(of: separator, with: " ")
            .replacingOccurrences(of: "\n", with: " ").replacingOccurrences(of: "\r", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return String(flat.prefix(maxDetail))
    }

    /// Wie `sortedBy` in Kotlin: gleiche Zeiten behalten ihre Reihenfolge.
    private static func stableSorted(_ entries: [DiagEntry]) -> [DiagEntry] {
        entries.enumerated().sorted { ($0.element.at, $0.offset) < ($1.element.at, $1.offset) }.map(\.element)
    }
}

/// Bericht der Uhr ans iPhone: Zustand als Schlüssel und Wert, dazu ihr Protokoll.
public struct DiagReport: Equatable, Sendable {
    /// Zustand, z. B. («version», «1.4»), («next», ISO-Zeit), («battery», «80»).
    public let status: [(key: String, value: String)]
    /// Protokoll der Uhr.
    public let entries: [DiagEntry]

    /// Erzeugt einen Bericht.
    public init(status: [(key: String, value: String)], entries: [DiagEntry]) {
        self.status = status
        self.entries = entries
    }

    /// Gleich bei gleichem Zustand (in Reihenfolge) und gleichem Protokoll.
    public static func == (lhs: DiagReport, rhs: DiagReport) -> Bool {
        lhs.status.map(\.key) == rhs.status.map(\.key) && lhs.status.map(\.value) == rhs.status.map(\.value) && lhs.entries == rhs.entries
    }
}

/// Format von `DiagReport`: «phasewake-diag 1», Zeilen «schlüssel=wert», «--», dann die Zeilen von `DiagLog`.
public enum DiagReports {
    private static let header = "phasewake-diag 1"
    private static let divider = "--"

    /// Schreibt den Bericht; «=» im Schlüssel und Zeilenumbrüche im Wert werden entfernt.
    public static func encode(_ report: DiagReport) -> String {
        var out = header + "\n"
        for (key, value) in report.status {
            out += key.replacingOccurrences(of: "=", with: "").replacingOccurrences(of: "\n", with: " ")
                + "=" + value.replacingOccurrences(of: "\n", with: " ") + "\n"
        }
        out += divider + "\n"
        for entry in report.entries { out += DiagLog.encode(entry) + "\n" }
        return out
    }

    /// Liest `encode`; fremdes Format oder `nil` ergibt `nil`, kaputte Zeilen fallen weg.
    public static func decode(_ text: String?) -> DiagReport? {
        guard let lines = text?.components(separatedBy: "\n"), lines.first == header else { return nil }
        let end = lines.firstIndex(of: divider).flatMap { $0 > 0 ? $0 : nil } ?? lines.count
        let status = lines[1..<end].compactMap { line -> (key: String, value: String)? in
            guard let cut = line.firstIndex(of: "=") else { return nil }
            return (key: String(line[..<cut]), value: String(line[line.index(after: cut)...]))
        }
        let entries = lines.dropFirst(end + 1).filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }.compactMap(DiagLog.decode)
        return DiagReport(status: status, entries: entries)
    }
}

/// Freischalten der Diagnose: `taps`-mal auf die Versionszeile tippen, ab `hintFrom` mit Hinweis.
public enum DiagUnlock {
    /// So viele Tipps schalten die Diagnose frei.
    public static let taps = 7
    /// Ab so vielen Tipps erscheint «Noch N Mal».
    public static let hintFrom = 4

    /// Noch fehlende Tipps für den Hinweis; `nil` vor `hintFrom` und ab `taps`.
    public static func remaining(_ count: Int) -> Int? { (hintFrom..<taps).contains(count) ? taps - count : nil }

    /// Ob `count` Tipps die Diagnose freischalten.
    public static func unlocked(_ count: Int) -> Bool { count >= taps }
}
