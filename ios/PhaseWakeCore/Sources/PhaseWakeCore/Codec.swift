import Foundation

/// Fehler beim Lesen gespeicherter oder empfangener Daten.
public struct CodecError: Error, Equatable, CustomStringConvertible, Sendable {
    /// Lesbare Beschreibung des Fehlers (nur für Logs, nicht für die Oberfläche).
    public let description: String
}

/// Textformat für Wecker und Nächte, gleich wie `Codec` in `android/core`: Wecker byte-gleich;
/// Zahlen der Epochen dürfen sich in der Schreibweise unterscheiden (Kotlin «2.13E-4», Swift
/// «0.000213»), beide Seiten lesen beide.
public enum Codec {
    private static let alarmsHeader = "phasewake-alarms 1"
    private static let nightHeader = "phasewake-night 1"
    private static let dayCodes: [Weekday: String] = [
        .monday: "MO", .tuesday: "TU", .wednesday: "WE", .thursday: "TH", .friday: "FR", .saturday: "SA", .sunday: "SU",
    ]
    private static let daysByCode = Dictionary(uniqueKeysWithValues: dayCodes.map { ($1, $0) })

    /// So viele Felder je Weckerzeile kennt diese Version; die Uhr meldet die Zahl im Status (`SyncKeys.alarmFields`),
    /// damit das iPhone eine ältere Uhr erkennt (`watchKnowsSkip`).
    public static let alarmFields = 16
    /// Ab so vielen Feldern kennt eine Version Überspringen (Feld 15) und Feiertage (Feld 16).
    public static let skipFields = 16

    /// Ob eine Uhr, die `watchFields` Felder je Weckerzeile kennt, Überspringen und Feiertage beachtet. `nil` = keine
    /// Uhr bekannt (nie ein Status): `true`, das iPhone weckt dann allein und beachtet beides. Ein Status ohne Angabe
    /// stammt von einer älteren Uhr und zählt als 0.
    public static func watchKnowsSkip(_ watchFields: Int?) -> Bool {
        guard let watchFields else { return true }
        return watchFields >= skipFields
    }

    /// Schreibt Wecker im Format «phasewake-alarms 1»: Kopfzeile, dann je Wecker eine Zeile mit 16 Feldern, getrennt durch «|». Texte URL-kodiert wie in Kotlin, damit beide Seiten byte-gleich schreiben.
    public static func encodeAlarms(_ alarms: [AlarmSpec]) -> String {
        var out = alarmsHeader + "\n"
        for a in alarms {
            out += [
                escape(a.id), String(a.hour), String(a.minute),
                a.days.sorted().map { dayCodes[$0]! }.joined(separator: ","),
                String(a.windowMinutes), a.enabled ? "1" : "0", a.phone.rawValue, a.watchSound ? "1" : "0",
                a.sleepDurationMinutes.map(String.init) ?? "", escape(a.sound), escape(a.label), a.sunrise.rawValue,
                a.wakeTask.rawValue, a.sunriseDevice.rawValue, String(a.skipUntil), a.skipHolidays ? "1" : "0",
            ].joined(separator: "|") + "\n"
        }
        return out
    }

    /// Liest Wecker zeilenweise und tolerant, wie Kotlin: Eine unlesbare Zeile fällt weg, unbekannte
    /// Werte werden zum Standard. Nur ein fremdes Format wirft.
    public static func decodeAlarms(_ text: String) throws -> [AlarmSpec] {
        let lines = text.components(separatedBy: "\n").filter { !$0.isEmpty }
        guard lines.first == alarmsHeader else { throw CodecError(description: "Unbekanntes Weckerformat") }
        return lines.dropFirst().enumerated().compactMap { index, line in try? decodeAlarm(index, line) }
    }

    private static func decodeAlarm(_ index: Int, _ line: String) throws -> AlarmSpec {
        let f = line.components(separatedBy: "|")
        // Neuere Versionen dürfen Felder anhängen; gelesen wird, was diese Version kennt.
        guard f.count >= 11 else { throw CodecError(description: "Wecker \(index + 1): 16 Felder erwartet") }
        let sunrise = f.count >= 12 ? Sunrise(rawValue: f[11]) ?? .off : .off
        // 13. Feld (Weckaufgabe): fehlt bei älteren Ständen, unbekannt bei neueren → Halten.
        let wakeTask: WakeTask = f.count >= 13 ? WakeTask(rawValue: f[12]) ?? WakeTask.none : WakeTask.none
        // 14. Feld (Sonnenaufgang-Gerät): fehlt bei älteren Ständen, unbekannt bei neueren → Handy.
        let sunriseDevice: SunriseDevice = f.count >= 14 ? SunriseDevice(rawValue: f[13]) ?? .phone : .phone
        // Felder 15 und 16 fehlen bei älteren Ständen (bis 14 Felder): nichts überspringen; unlesbar oder negativ → 0.
        let skipUntil = f.count >= 15 ? max(0, Int64(f[14]) ?? 0) : 0
        let skipHolidays = f.count >= 16 && f[15] == "1"
        let phone = PhoneAlert(rawValue: f[6]) ?? .sound
        let days: Set<Weekday> = try f[3].isEmpty ? [] : Set(f[3].components(separatedBy: ",").map {
            guard let day = daysByCode[$0] else { throw CodecError(description: "Wochentag '\($0)'") }
            return day
        })
        do {
            return try AlarmSpec(
                id: unescape(f[0]), hour: int(f[1]), minute: int(f[2]), days: days, windowMinutes: int(f[4]),
                enabled: f[5] == "1", phone: phone, watchSound: f[7] == "1",
                sleepDurationMinutes: f[8].isEmpty ? nil : int(f[8]), sound: unescape(f[9]), label: unescape(f[10]), sunrise: sunrise,
                wakeTask: wakeTask, sunriseDevice: sunriseDevice, skipUntil: skipUntil, skipHolidays: skipHolidays
            )
        } catch let error as DetectorConfigError {
            throw CodecError(description: "Wecker \(index + 1): \(error.message)")
        }
    }

    /// Schreibt eine Nacht im Format «phasewake-night 1»: Kopfzeile, Zeile mit 7 Feldern (Zeiten in Millisekunden seit 1970), Zeile mit den Epochen «Bewegung;Puls», getrennt durch Kommas (leere Werte leer).
    public static func encodeNight(_ night: NightRecord) -> String {
        var out = nightHeader + "\n"
        out += [
            escape(night.alarmId), String(millis(night.windowStart)), String(millis(night.target)),
            String(millis(night.wokeAt)), night.reason.rawValue, String(night.snoozes),
            night.feeling.map(String.init) ?? "",
        ].joined(separator: "|") + "\n"
        out += night.epochs.map { e in
            (e.motion.map(number) ?? "") + ";" + (e.heartRate.map(number) ?? "")
        }.joined(separator: ",") + "\n"
        return out
    }

    /// Liest `encodeNight`. Ältere Stände ohne Befinden (6 Felder) werden gelesen, neuere Felder ignoriert.
    /// - Throws: ``CodecError`` bei fremdem Format, fehlenden Feldern, unbekanntem Grund, Befinden ausserhalb 1–5 oder unlesbaren Zahlen.
    public static func decodeNight(_ text: String) throws -> NightRecord {
        let lines = text.components(separatedBy: "\n")
        guard lines.first == nightHeader else { throw CodecError(description: "Unbekanntes Nachtformat") }
        let f = (lines.count > 1 ? lines[1] : "").components(separatedBy: "|")
        // 7 Felder; ältere Stände ohne Befinden haben 6, neuere dürfen weitere anhängen.
        guard f.count >= 6 else { throw CodecError(description: "Nacht: 7 Felder erwartet") }
        let feeling: Int? = try f.count >= 7 && !f[6].isEmpty ? int(f[6]) : nil
        if let feeling, !NightRecord.feelingRange.contains(feeling) { throw CodecError(description: "Nacht: Befinden '\(feeling)'") }
        guard let reason = WakeReason(rawValue: f[4]) else { throw CodecError(description: "Nacht: Grund '\(f[4])'") }
        let epochLine = lines.count > 2 ? lines[2] : ""
        let epochs: [Epoch] = try epochLine.isEmpty ? [] : epochLine.components(separatedBy: ",").map { pair in
            let p = pair.components(separatedBy: ";")
            guard p.count == 2 else { throw CodecError(description: "Epoche '\(pair)'") }
            return Epoch(motion: try p[0].isEmpty ? nil : double(p[0]), heartRate: try p[1].isEmpty ? nil : double(p[1]))
        }
        return try NightRecord(
            alarmId: unescape(f[0]), windowStart: try date(f[1]), target: try date(f[2]), wokeAt: try date(f[3]),
            reason: reason, snoozes: try int(f[5]), epochs: epochs, feeling: feeling
        )
    }

    // MARK: - Hilfen

    private static func int(_ s: String) throws -> Int {
        guard let v = Int(s) else { throw CodecError(description: "'\(s)' ist keine Zahl") }
        return v
    }

    private static func double(_ s: String) throws -> Double {
        guard let v = Double(s) else { throw CodecError(description: "'\(s)' ist keine Zahl") }
        return v
    }

    private static func millis(_ d: Date) -> Int64 { Int64((d.timeIntervalSince1970 * 1000).rounded()) }

    private static func date(_ s: String) throws -> Date {
        guard let ms = Int64(s) else { throw CodecError(description: "'\(s)' ist keine Zeit") }
        return Date(timeIntervalSince1970: TimeInterval(ms) / 1000)
    }

    /// Kürzeste exakt rücklesbare Darstellung (Swift `description`); Android liest sie genauso.
    static func number(_ v: Double) -> String { "\(v)" }

    /// Wie `java.net.URLEncoder` (UTF-8): Buchstaben, Ziffern und `.-*_` bleiben, Leerzeichen → `+`.
    private static func escape(_ value: String) -> String {
        var out = ""
        for byte in value.utf8 {
            switch byte {
            case UInt8(ascii: "a")...UInt8(ascii: "z"), UInt8(ascii: "A")...UInt8(ascii: "Z"), UInt8(ascii: "0")...UInt8(ascii: "9"),
                 UInt8(ascii: "."), UInt8(ascii: "-"), UInt8(ascii: "*"), UInt8(ascii: "_"):
                out.append(Character(UnicodeScalar(byte)))
            case UInt8(ascii: " "):
                out.append("+")
            default:
                out += String(format: "%%%02X", byte)
            }
        }
        return out
    }

    /// Ungültige Prozent-Kodierung ist ein Fehler, wie in Kotlin (`URLDecoder`).
    private static func unescape(_ value: String) throws -> String {
        guard let decoded = value.replacingOccurrences(of: "+", with: " ").removingPercentEncoding else {
            throw CodecError(description: "Ungültige Kodierung '\(value)'")
        }
        return decoded
    }
}

/// Kurzschreibweise der Wochentage, z. B. «Mo–Fr». Identisch zu `DayFormat` (Kotlin).
public enum DayFormat {
    /// Formatiert Wochentage kurz in der Sprache von `locale`, ab dem Wochenbeginn der Region; drei und mehr Tage in Folge als Spanne («Mo–Fr»), eine Spanne über das Wochenende bleibt zusammen («Sa–Mo»).
    /// - Parameters:
    ///   - once: Text für einen einmaligen Wecker (keine Tage).
    ///   - daily: Text für alle sieben Tage.
    public static func format(_ days: Set<Weekday>, locale: Locale, once: String, daily: String) -> String {
        if days.isEmpty { return once }
        if days.count == 7 { return daily }
        // Wie Kotlin: ab dem Wochenbeginn der Region; Spanne über das Wochenende bleibt zusammen.
        var calendar = Calendar(identifier: .gregorian)
        calendar.locale = locale
        // firstWeekday: 1 = Sonntag … 7 = Samstag; Weekday: 1 = Montag … 7 = Sonntag.
        let firstRaw = (calendar.firstWeekday + 5) % 7 + 1
        let week = (0..<7).map { Weekday(rawValue: (firstRaw - 1 + $0) % 7 + 1)! }
        let start = days.contains(week[0]) && days.contains(week[6]) ? (week.lastIndex { !days.contains($0) } ?? -1) + 1 : 0
        let sorted = (0..<7).map { week[(start + $0) % 7] }.filter { days.contains($0) }
        var parts: [String] = []
        var i = 0
        while i < sorted.count {
            var j = i
            while j + 1 < sorted.count, sorted[j + 1].rawValue == sorted[j].rawValue % 7 + 1 { j += 1 }
            if j - i >= 2 {
                parts.append(name(sorted[i], locale) + "–" + name(sorted[j], locale))
            } else {
                for k in i...j { parts.append(name(sorted[k], locale)) }
            }
            i = j + 1
        }
        return parts.joined(separator: ", ")
    }

    private static func name(_ day: Weekday, _ locale: Locale) -> String {
        var calendar = Calendar(identifier: .gregorian)
        calendar.locale = locale
        // shortWeekdaySymbols beginnt mit Sonntag.
        let symbol = calendar.shortWeekdaySymbols[day.rawValue % 7]
        return symbol.hasSuffix(".") ? String(symbol.dropLast()) : symbol
    }
}
