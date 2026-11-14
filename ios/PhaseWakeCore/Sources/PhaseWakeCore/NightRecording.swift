import Foundation

/// Grund, warum eine Aufzeichnung endete. `rawValue` steht in Datei und Paket: nie ändern, nur ergänzen. Unbekannte
/// Kennungen (neuere Version) gelten beim Lesen als ``service``. Gleiche Kennungen wie `RecordEnd` (Kotlin).
public enum RecordEnd: String, Sendable {
    /// Auf der Uhr gestoppt.
    case watch
    /// Am Handy gestoppt bzw. die Lernphase am Handy ausgeschaltet.
    case phone
    /// Nach ``NightRecording/maxDuration`` von selbst beendet.
    case cap
    /// Akku der Uhr zu tief.
    case battery
    /// Vom System beendet oder nicht wieder aufgenommen.
    case service
    /// Lernphase: beim Wecken beendet (Klingeln beendet, ohne Klingeln zur eingestellten Weckzeit).
    case wake

    /// Grund zur Kennung `code`; unbekannt oder fehlend ``service``.
    public static func of(_ code: String?) -> RecordEnd {
        code.flatMap(RecordEnd.init(rawValue:)) ?? .service
    }
}

/// Eine aufgezeichnete Nacht: ab `start` je 30-s-Epoche Bewegung und Puls. Epoche `i` deckt `start + i · 30 s` ab;
/// Lücken sind leere Epochen. Auf der Apple Watch aus der Lernphase (``Learning``) und von «Nacht aufzeichnen»: Die Uhr
/// rechnet die Epochen aus den Werten des Bewegungs-Rekorders und dem Puls aus Apple Health (``RecorderEpochs``) und
/// schickt die fertige Aufzeichnung ans iPhone. Ist der Beginn der Einschlafdauer bekannt, stehen `bedAt` und `asleepAt`
/// dabei. Gleiches Textformat wie `NightRecording` (Kotlin), «phasewake-recording 1».
public struct NightRecording: Equatable, Sendable {
    /// Beginn (Epoche 0).
    public let start: Date
    /// Epochen ab `start`.
    public let epochs: [Epoch]
    /// Ende; `nil`, solange sie läuft.
    public let end: Date?
    /// Grund des Endes; `nil`, solange sie läuft.
    public let endReason: RecordEnd?
    /// Beginn der Einschlafdauer; `nil` = unbekannt.
    public let bedAt: Date?
    /// Einschlafzeit; `nil` = unbekannt.
    public let asleepAt: Date?

    /// Längste Aufzeichnung (12 Std.).
    public static let maxDuration: TimeInterval = 12 * 3600
    /// Epochen in ``maxDuration``.
    public static let maxEpochs = Int(maxDuration) / DetectorConfig.epochSeconds
    /// So viele abgeschlossene Aufzeichnungen bleiben je Gerät; ältere werden gelöscht.
    public static let maxKept = 14
    /// Unter diesem Akkustand der Uhr (Prozent) startet «Nacht aufzeichnen» nicht, und eine laufende endet
    /// (``RecordEnd/battery``); wie `NightRecording.MIN_BATTERY` (Kotlin).
    public static let minBattery = 15
    /// Erste Zeile von ``encode(_:)``.
    public static let header = "phasewake-recording 1"

    private static let startPrefix = "start="
    private static let endPrefix = "end="
    private static let onsetPrefix = "onset="

    /// Aufzeichnung wie angegeben.
    public init(start: Date, epochs: [Epoch], end: Date? = nil, endReason: RecordEnd? = nil, bedAt: Date? = nil, asleepAt: Date? = nil) {
        self.start = start
        self.epochs = epochs
        self.end = end
        self.endReason = endReason
        self.bedAt = bedAt
        self.asleepAt = asleepAt
    }

    /// Abgeschlossen (Ende bekannt).
    public var finished: Bool { end != nil }

    /// Dauer: bis zum Ende, laufend bis zur letzten Epoche.
    public var duration: TimeInterval {
        (end ?? start.addingTimeInterval(TimeInterval(epochs.count * DetectorConfig.epochSeconds))).timeIntervalSince(start)
    }

    /// Ende, laufend die letzte Epoche (für den Vergleich mit einer Nacht im Verlauf).
    public var until: Date { start.addingTimeInterval(duration) }

    /// Grund für ein Ende jetzt (`now`, Akku `battery` in Prozent, `nil` = unbekannt), sonst `nil`: ``RecordEnd/cap`` ab
    /// ``maxDuration`` nach `start`, ``RecordEnd/battery`` unter ``minBattery`` (wie `NightRecording.endNow`, Kotlin).
    public static func endNow(start: Date, now: Date, battery: Int?) -> RecordEnd? {
        if now >= start.addingTimeInterval(maxDuration) { return .cap }
        if let battery, battery < minBattery { return .battery }
        return nil
    }

    /// CSV-Export der abgeschlossenen Aufzeichnungen wie ``NightHistory/exportCsv(_:notes:calendar:)``: ohne Datums- oder
    /// Uhrzeitangaben, nummeriert ab 1 (älteste zuerst), je Epoche Bewegung und Puls; dieselben Spalten wie
    /// `NightRecording.exportCsv` (Kotlin), Datei «phasewake-recordings.csv».
    public static func exportCsv(_ recordings: [NightRecording]) -> String {
        var out = "recording,end_reason,epoch,motion,hr\n"
        for (index, recording) in recordings.filter(\.finished).sorted(by: { $0.start < $1.start }).enumerated() {
            let reason = (recording.endReason ?? .service).rawValue
            for (epochIndex, epoch) in recording.epochs.enumerated() {
                let motion = epoch.motion.map { CsvNumbers.fixed($0, decimals: 6) } ?? ""
                let hr = epoch.heartRate.map { CsvNumbers.fixed($0, decimals: 1) } ?? ""
                out += "\(index + 1),\(reason),\(epochIndex),\(motion),\(hr)\n"
            }
        }
        return out
    }

    /// Für die Lernphase (``Learning``).
    public var learnNight: Learning.Night {
        Learning.Night(start: start, epochs: epochs, bedAt: bedAt, asleepAt: asleepAt)
    }

    /// Kopie mit Beginn der Einschlafdauer und Einschlafzeit (iPhone, aus Apple Health nachgetragen).
    public func with(bedAt: Date, asleepAt: Date) -> NightRecording {
        NightRecording(start: start, epochs: epochs, end: end, endReason: endReason, bedAt: bedAt, asleepAt: asleepAt)
    }

    /// Ganze Aufzeichnung als Text (Datei am iPhone, Paket ans iPhone): Kopfzeile, «start=ms», je Epoche
    /// «bewegung;puls» (leer = keine Werte), «onset=bettMs,eingeschlafenMs» falls bekannt, «end=ms,grund» falls beendet.
    public static func encode(_ recording: NightRecording) -> String {
        var text = "\(header)\n\(startPrefix)\(millis(recording.start))\n"
        for epoch in recording.epochs {
            text += "\(epoch.motion.map { "\($0)" } ?? "");\(epoch.heartRate.map { "\($0)" } ?? "")\n"
        }
        if let bed = recording.bedAt, let asleep = recording.asleepAt {
            text += "\(onsetPrefix)\(millis(bed)),\(millis(asleep))\n"
        }
        if let end = recording.end {
            text += "\(endPrefix)\(millis(end)),\((recording.endReason ?? .service).rawValue)\n"
        }
        return text
    }

    /// Liest ``encode(_:)``. Nachsichtig wie Kotlin: Eine letzte Zeile ohne Zeilenende und unlesbare Epochen-Zeilen
    /// fallen weg; die jüngste «onset»-Zeile gilt. `nil` bei fremdem Format oder ohne Beginn. Zeiten ausserhalb von
    /// ``TimeBounds`` gelten als unlesbar: Beginn → `nil`, «onset»- und «end»-Zeile fallen weg.
    public static func decode(_ text: String) -> NightRecording? {
        guard let lastBreak = text.lastIndex(of: "\n") else { return nil }
        var lines = text[..<lastBreak].components(separatedBy: "\n")
        while lines.last?.isEmpty == true { lines.removeLast() }
        guard lines.first == header, lines.count >= 2, lines[1].hasPrefix(startPrefix),
              let start = TimeBounds.checked(Int64(lines[1].dropFirst(startPrefix.count))) else { return nil }
        var epochs: [Epoch] = []
        var end: Date?
        var reason: RecordEnd?
        var bed: Date?
        var asleep: Date?
        for line in lines.dropFirst(2) {
            if line.hasPrefix(onsetPrefix) {
                let fields = line.dropFirst(onsetPrefix.count).split(separator: ",", omittingEmptySubsequences: false)
                if fields.count >= 2, let b = TimeBounds.checked(Int64(fields[0])), let a = TimeBounds.checked(Int64(fields[1])) {
                    bed = date(b)
                    asleep = date(a)
                }
                continue
            }
            if line.hasPrefix(endPrefix) {
                let fields = line.dropFirst(endPrefix.count).split(separator: ",", omittingEmptySubsequences: false)
                guard let ms = TimeBounds.checked(fields.first.flatMap({ Int64($0) })) else { continue }
                end = date(ms)
                reason = RecordEnd.of(fields.count > 1 ? String(fields[1]) : nil)
                break
            }
            if let epoch = epoch(line) { epochs.append(epoch) }
        }
        return NightRecording(start: date(start), epochs: epochs, end: end, endReason: reason, bedAt: bed, asleepAt: asleep)
    }

    private static func epoch(_ line: String) -> Epoch? {
        let fields = line.split(separator: ";", omittingEmptySubsequences: false)
        guard fields.count == 2 else { return nil }
        var motion: Double?
        var heartRate: Double?
        if !fields[0].isEmpty {
            guard let value = Double(fields[0]) else { return nil }
            motion = value
        }
        if !fields[1].isEmpty {
            guard let value = Double(fields[1]) else { return nil }
            heartRate = value
        }
        return Epoch(motion: motion, heartRate: heartRate)
    }

    private static func millis(_ date: Date) -> Int64 { TimeBounds.millis(date) }

    private static func date(_ millis: Int64) -> Date { Date(timeIntervalSince1970: TimeInterval(millis) / 1000) }
}
