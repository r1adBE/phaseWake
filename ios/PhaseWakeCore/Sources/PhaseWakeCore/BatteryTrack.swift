import Foundation

/// Art eines Akku-Messpunkts einer Nacht (``BatteryTrack``). Der Rohwert steht im Speicherformat: nie ändern, nur ergänzen.
/// Identisch zu `BatteryMark` (Kotlin).
public enum BatteryMark: String, CaseIterable, Sendable {
    /// Messbeginn der Nacht (Einschlaf-Messung, Aufzeichnung oder Weckfenster, was zuerst kommt).
    case start = "s"
    /// Zwischenstand, z. B. Beginn des Weckfensters nach der Einschlaf-Messung.
    case sample = "m"
    /// Ladebeginn: Ladegerät angeschlossen.
    case plug = "c"
    /// Ladeende: Ladegerät getrennt.
    case unplug = "u"
    /// Ende der Nacht (Wecken beendet).
    case end = "e"
}

/// Akkustand des ganzen Geräts: `percent` 0–100, `plugged` = am Ladegerät. Zeit auf Millisekunden genau (wie Kotlin).
public struct BatteryPoint: Equatable, Sendable {
    /// Zeitpunkt der Messung.
    public let at: Date
    /// Akkustand in Prozent (0–100).
    public let percent: Int
    /// Am Ladegerät?
    public let plugged: Bool
    /// Art des Punkts.
    public let mark: BatteryMark

    /// Zeit auf ganze Millisekunden gerundet (wie das Textformat).
    public init(at: Date, percent: Int, plugged: Bool, mark: BatteryMark) {
        self.at = Date(timeIntervalSince1970: TimeInterval(TimeBounds.millis(at)) / 1000)
        self.percent = percent
        self.plugged = plugged
        self.mark = mark
    }
}

/// Akkuverbrauch eines Geräts in einer Nacht (Entscheid r1AD 14.11.2026): Messpunkte vom Messbeginn bis zum Ende der Nacht.
/// Gemessen wird der Akkustand des ganzen Geräts. Zeit am Ladegerät zählt nicht: Summiert werden nur die Teilstrecken ohne
/// Laden. Ist etwas unbekannt, gibt es keinen Wert statt eines falschen. Identisch zu `BatteryTrack` (Kotlin).
public enum BatteryTrack {
    /// Länger offen gilt eine Nacht als verwaist: Ein neuer Beginn ersetzt sie.
    public static let maxSpan: TimeInterval = 14 * 3600
    /// Höchstens so viele Zwischenstände je Nacht; Ladebeginn und -ende zählen immer.
    public static let maxSamples = 24
    /// Mehr Punkte liest ``decode(_:)`` nicht.
    public static let maxPoints = 64
    /// So weit darf eine Nacht beim Handy nach ihrem Ende eintreffen, damit «jetzt» noch als ihr Ende gilt.
    public static let arrivalSlack: TimeInterval = 5 * 60

    /// Verbrauch in Prozentpunkten, nur aus den Teilstrecken ohne Ladegerät; `nil`, wenn unbekannt (wie Kotlin `used`).
    public static func used(_ track: [BatteryPoint]?) -> Int? {
        guard let track, track.count >= 2, track.count <= maxPoints,
              track.first?.mark == .start, track.last?.mark == .end else { return nil }
        if track.dropFirst().dropLast().contains(where: { $0.mark == .start || $0.mark == .end }) { return nil }
        if track.contains(where: { !(0...100).contains($0.percent) || !consistent($0) }) { return nil }
        var used = 0
        var unplugged = false
        for (a, b) in zip(track, track.dropFirst()) {
            if b.at < a.at { return nil }
            if a.plugged != b.plugged, b.mark != (b.plugged ? .plug : .unplug) { return nil }
            if !a.plugged {
                if b.percent > a.percent { return nil }
                used += a.percent - b.percent
                unplugged = true
            }
        }
        return unplugged ? used : nil
    }

    /// Ob `open` eine laufende Nacht ist (begonnen, nicht beendet, höchstens ``maxSpan`` alt, `now` nicht davor).
    public static func isOpen(_ open: [BatteryPoint], now: Date) -> Bool {
        guard let first = open.first, let last = open.last else { return false }
        let nowMs = TimeBounds.millis(now)
        return first.mark == .start && last.mark != .end && nowMs >= TimeBounds.millis(last.at)
            && nowMs - TimeBounds.millis(first.at) <= Int64(maxSpan * 1000)
    }

    /// Messbeginn: Läuft schon eine Nacht, bleibt ihr früherer Beginn und dieser Stand kommt als Zwischenstand dazu;
    /// sonst beginnt eine neue (ersetzt eine verwaiste). Eine neue Nacht hat genau einen Punkt.
    public static func begin(_ open: [BatteryPoint], at: Date, percent: Int, plugged: Bool) -> [BatteryPoint] {
        isOpen(open, now: at) ? sample(open, at: at, percent: percent, plugged: plugged)
            : [BatteryPoint(at: at, percent: percent, plugged: plugged, mark: .start)]
    }

    /// Zwischenstand in einer laufenden Nacht (höchstens ``maxSamples``); sonst unverändert.
    public static func sample(_ open: [BatteryPoint], at: Date, percent: Int, plugged: Bool) -> [BatteryPoint] {
        guard isOpen(open, now: at), open.filter({ $0.mark == .sample }).count < maxSamples else { return open }
        return open + [BatteryPoint(at: at, percent: percent, plugged: plugged, mark: .sample)]
    }

    /// Ladegerät angeschlossen oder getrennt (beobachtet): Ladebeginn bzw. -ende, wenn sich der Stand ändert.
    public static func charge(_ open: [BatteryPoint], at: Date, percent: Int, plugged: Bool) -> [BatteryPoint] {
        guard isOpen(open, now: at), open.last?.plugged != plugged else { return open }
        return open + [BatteryPoint(at: at, percent: percent, plugged: plugged, mark: plugged ? .plug : .unplug)]
    }

    /// Ende der Nacht: die fertigen Punkte; `nil`, wenn keine Nacht läuft.
    public static func end(_ open: [BatteryPoint], at: Date, percent: Int, plugged: Bool) -> [BatteryPoint]? {
        isOpen(open, now: at) ? open + [BatteryPoint(at: at, percent: percent, plugged: plugged, mark: .end)] : nil
    }

    /// Gehören die Punkte zu `night`? Das erste Wecken liegt zwischen Messbeginn und Ende.
    public static func belongs(_ track: [BatteryPoint], to night: NightRecord) -> Bool {
        guard let first = track.first, let last = track.last else { return false }
        return night.wokeAt >= first.at && night.wokeAt <= last.at
    }

    /// Kam `night` gleich nach ihrem Ende an (Ende der Uhr-Messung, sonst das erste Wecken), höchstens ``arrivalSlack`` daneben?
    public static func arrivedFresh(_ night: NightRecord, now: Date) -> Bool {
        let end = night.watchBattery?.last?.at ?? night.wokeAt
        return abs(now.timeIntervalSince(end)) <= arrivalSlack
    }

    /// «s,ms,prozent,0|1» je Punkt, durch «;» getrennt (wie Kotlin).
    public static func encode(_ track: [BatteryPoint]) -> String {
        track.map { "\($0.mark.rawValue),\(TimeBounds.millis($0.at)),\($0.percent),\($0.plugged ? 1 : 0)" }.joined(separator: ";")
    }

    /// Liest ``encode(_:)``; leer, fremd oder kaputt: `nil`.
    public static func decode(_ text: String?) -> [BatteryPoint]? {
        guard let text, !text.isEmpty else { return nil }
        let parts = text.components(separatedBy: ";")
        guard parts.count <= maxPoints else { return nil }
        var out: [BatteryPoint] = []
        for part in parts {
            let f = part.components(separatedBy: ",")
            guard f.count == 4, let mark = BatteryMark(rawValue: f[0]),
                  let ms = TimeBounds.checked(Int64(f[1])),
                  let percent = Int(f[2]), (0...100).contains(percent),
                  f[3] == "1" || f[3] == "0" else { return nil }
            let point = BatteryPoint(at: Date(timeIntervalSince1970: TimeInterval(ms) / 1000), percent: percent, plugged: f[3] == "1", mark: mark)
            guard consistent(point) else { return nil }
            out.append(point)
        }
        return out
    }

    /// Für das Diagnose-Protokoll, z. B. «start 80% unplugged».
    public static func describe(_ point: BatteryPoint) -> String {
        let what: String
        switch point.mark {
        case .start: what = "start"
        case .sample: what = "sample"
        case .plug: what = "charge start"
        case .unplug: what = "charge end"
        case .end: what = "end"
        }
        return "\(what) \(point.percent)% \(point.plugged ? "plugged" : "unplugged")"
    }

    /// Ladebeginn nur am Ladegerät, Ladeende nur ohne.
    private static func consistent(_ point: BatteryPoint) -> Bool {
        !(point.mark == .plug && !point.plugged) && !(point.mark == .unplug && point.plugged)
    }
}
