import Foundation

/// Feste Schlafnotizen. Die Kennungen sind stabil und stehen so im Speicher. Identisch zu Kotlin.
public enum SleepNote: String, CaseIterable, Sendable {
    case caffeine = "CAFFEINE"
    case alcohol = "ALCOHOL"
    case exercise = "EXERCISE"
    case stress = "STRESS"
    case lateMeal = "LATE_MEAL"
    case screen = "SCREEN"
    case nap = "NAP"
    case sick = "SICK"
}

/// Eigene Notiz, vom Nutzer benannt. Kennung «custom:<uuid>».
public struct CustomNote: Equatable, Sendable, Identifiable {
    /// Kennung «custom:» plus UUID in Kleinbuchstaben.
    public let id: String
    /// Bereinigter Name (`SleepNotes.cleanName`), höchstens 30 Zeichen.
    public let name: String

    /// Erzeugt eine eigene Notiz; der Name wird bereinigt und kann dadurch leer werden.
    public init(id: String, name: String) {
        self.id = id
        self.name = SleepNotes.cleanName(name)
    }
}

/// Notizen je Nacht: Nachtdatum, Speicherformat, Sortierung, Export. Identisch zu Kotlin.
public enum SleepNotes {
    /// Vorsatz der Kennung eigener Notizen.
    public static let customPrefix = "custom:"
    /// Höchstens so viele eigene Notizen, damit die Auswahl übersichtlich bleibt.
    public static let maxCustom = 5
    /// Höchstlänge eines Namens in Zeichen; passt auf einen Chip.
    public static let maxNameLength = 30
    /// Wie der Verlauf: höchstens so viele Nachtdaten.
    public static let maxNights = NightHistory.maxNights
    /// Ab dieser Stunde (Ortszeit) lassen sich Notizen für die kommende Nacht setzen.
    public static let eveningHour = 17

    /// Ob die Kennung eine eigene Notiz ist.
    public static func isCustom(_ id: String) -> Bool { id.hasPrefix(customPrefix) }

    /// Neue zufällige Kennung für eine eigene Notiz (nur beim Anlegen, nicht in Rechenregeln).
    public static func newCustomId() -> String { customPrefix + UUID().uuidString.lowercased() }

    /// Name ohne Zeilenumbrüche und Trenner, höchstens 30 Zeichen.
    public static func cleanName(_ name: String) -> String {
        let flat = name.replacingOccurrences(of: "\n", with: " ").replacingOccurrences(of: "\r", with: " ").replacingOccurrences(of: "|", with: " ")
        return String(flat.trimmingCharacters(in: .whitespaces).prefix(maxNameLength))
    }

    // MARK: Nachtdatum

    /// Nachtdatum «yyyy-MM-dd» = Ortsdatum von (`time` − 12 h): Für den Fensterbeginn einer Nacht
    /// ist das der Abend, an dem sie beginnt; abends ab 12 Uhr ist es heute.
    public static func nightDate(_ time: Date, calendar: Calendar) -> String {
        dateString(time.addingTimeInterval(-12 * 3600), calendar: calendar)
    }

    /// Nachtdatum einer aufgezeichneten Nacht (von der Uhr).
    public static func nightDate(of night: NightRecord, calendar: Calendar) -> String {
        nightDate(night.windowStart, calendar: calendar)
    }

    /// Kalenderdatum «yyyy-MM-dd» von `date` im Kalender (Ortszeit) mit vierstelligem Jahr.
    public static func dateString(_ date: Date, calendar: Calendar) -> String {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        func pad(_ value: Int?, _ width: Int) -> String {
            let text = String(value ?? 0)
            return String(repeating: "0", count: max(0, width - text.count)) + text
        }
        return "\(pad(c.year, 4))-\(pad(c.month, 2))-\(pad(c.day, 2))"
    }

    /// Ob die Notizen für heute Nacht auf der Startseite stehen: ab 17 Uhr bis zum Fensterbeginn des
    /// nächsten Weckers, ohne Wecker bis Mitternacht. Nach Mitternacht nur noch bis zum Fensterbeginn
    /// eines Weckers am selben Vormittag. `nextWindowStart` = Fensterbeginn des nächsten Weckers.
    public static func entryVisible(now: Date, nextWindowStart: Date?, calendar: Calendar) -> Bool {
        // Das Weckfenster läuft schon: keine Notizen mehr für diese Nacht.
        if let start = nextWindowStart, start <= now { return false }
        let hour = calendar.component(.hour, from: now)
        if hour >= eveningHour { return true }
        // Vormittag: nur solange ein Weckfenster noch am selben Vormittag beginnt.
        guard hour < 12, let start = nextWindowStart,
              let noon = calendar.date(byAdding: .hour, value: 12, to: calendar.startOfDay(for: now)) else { return false }
        return start < noon
    }

    // MARK: Ordnung

    /// Feste Notizen in ihrer Reihenfolge, dann eigene nach Kennung; ohne Doppel und Leeres.
    public static func normalize(_ ids: [String]) -> [String] {
        let set = Set(ids.filter { !$0.isEmpty && !$0.contains(",") && !$0.contains("|") })
        let fixed = SleepNote.allCases.map(\.rawValue).filter { set.contains($0) }
        let custom = set.filter { isCustom($0) }.sorted()
        return fixed + custom
    }

    /// Schaltet eine Notiz für eine Nacht ein oder aus.
    public static func toggle(_ id: String, in ids: [String]) -> [String] {
        ids.contains(id) ? normalize(ids.filter { $0 != id }) : normalize(ids + [id])
    }

    // MARK: Speicherformat

    /// Eine Zeile je Nachtdatum «yyyy-MM-dd|id,id,…», nach Datum sortiert, höchstens 90 Daten;
    /// Nächte ohne Notiz fallen weg.
    public static func encode(_ notes: [String: [String]]) -> String {
        trim(notes).sorted { $0.key < $1.key }.map { "\($0.key)|\($0.value.joined(separator: ","))" }.joined(separator: "\n")
    }

    /// Liest das Format von `encode`; unlesbare Zeilen werden übersprungen.
    public static func decode(_ text: String) -> [String: [String]] {
        var out: [String: [String]] = [:]
        for line in text.split(whereSeparator: \.isNewline) {
            let parts = line.split(separator: "|", maxSplits: 1, omittingEmptySubsequences: false)
            guard parts.count == 2, isDate(String(parts[0])) else { continue }
            let ids = normalize(parts[1].split(separator: ",").map { String($0).trimmingCharacters(in: .whitespaces) })
            if !ids.isEmpty { out[String(parts[0])] = ids }
        }
        return trim(out)
    }

    /// Behält die 90 jüngsten Nachtdaten mit mindestens einer Notiz.
    public static func trim(_ notes: [String: [String]]) -> [String: [String]] {
        let kept = notes.filter { !$0.value.isEmpty }.keys.sorted().suffix(maxNights)
        return Dictionary(uniqueKeysWithValues: kept.map { ($0, normalize(notes[$0] ?? [])) })
    }

    /// Eigene Notizen: eine Zeile «custom:<uuid>|Name», in der gespeicherten Reihenfolge, höchstens 5.
    public static func encodeCustom(_ notes: [CustomNote]) -> String {
        notes.prefix(maxCustom).map { "\($0.id)|\($0.name)" }.joined(separator: "\n")
    }

    /// Liest `encodeCustom`; Zeilen ohne «custom:»-Kennung, doppelte Kennungen und leere Namen fallen weg, höchstens 5.
    public static func decodeCustom(_ text: String) -> [CustomNote] {
        var out: [CustomNote] = []
        for line in text.split(whereSeparator: \.isNewline) {
            let parts = line.split(separator: "|", maxSplits: 1, omittingEmptySubsequences: false)
            guard parts.count == 2, isCustom(String(parts[0])), !out.contains(where: { $0.id == String(parts[0]) }) else { continue }
            let note = CustomNote(id: String(parts[0]), name: String(parts[1]))
            if !note.name.isEmpty { out.append(note) }
        }
        return Array(out.prefix(maxCustom))
    }

    /// Wert der CSV-Spalte `notes`: Kennungen mit «;», eigene nur als «custom» (Name bleibt privat).
    public static func csvValue(_ ids: [String]) -> String {
        normalize(ids).map { isCustom($0) ? "custom" : $0 }.joined(separator: ";")
    }

    private static func isDate(_ text: String) -> Bool {
        let parts = text.split(separator: "-")
        return text.count == 10 && parts.count == 3 && parts.allSatisfy { $0.allSatisfy(\.isNumber) }
    }
}

/// Wie eine Notiz mit dem Befinden zusammenhängt, über die letzten 90 Nächte. Identisch zu Kotlin.
public struct NoteEffect: Equatable, Sendable {
    /// Kennung der Notiz.
    public let noteId: String
    /// Nächte mit Befinden und dieser Notiz.
    public let nightsWith: Int
    /// Nächte mit Befinden ohne diese Notiz.
    public let nightsWithout: Int
    /// Durchschnittliches Befinden (1–5) mit der Notiz.
    public let avgFeelingWith: Double
    /// Durchschnittliches Befinden (1–5) ohne die Notiz.
    public let avgFeelingWithout: Double
    /// Befinden mit minus ohne Notiz.
    public var delta: Double { avgFeelingWith - avgFeelingWithout }
    /// Durchschnittlich geschlafene Minuten mit/ohne Notiz laut Gesundheits-App; `nil` unter 3 Nächten je Seite.
    public let avgAsleepWith: Double?
    /// Wie `avgAsleepWith`, für Nächte ohne die Notiz.
    public let avgAsleepWithout: Double?

    /// Erzeugt eine Wirkung; Durchschnitte wie von ``compute(nights:notes:asleepByNight:calendar:)``.
    public init(noteId: String, nightsWith: Int, nightsWithout: Int, avgFeelingWith: Double, avgFeelingWithout: Double,
                avgAsleepWith: Double?, avgAsleepWithout: Double?) {
        self.noteId = noteId
        self.nightsWith = nightsWith
        self.nightsWithout = nightsWithout
        self.avgFeelingWith = avgFeelingWith
        self.avgFeelingWithout = avgFeelingWithout
        self.avgAsleepWith = avgAsleepWith
        self.avgAsleepWithout = avgAsleepWithout
    }

    /// Mindestens so viele Nächte mit und ohne die Notiz.
    public static let minNights = 3
    /// Ab diesem Unterschied im Befinden (Betrag) zeigt der Verlauf die Notiz.
    public static let minVisibleDelta = 0.5

    /// Ob der Unterschied gross genug zum Anzeigen ist (mit kleiner Toleranz gegen Rundung).
    public var isVisible: Bool { abs(delta) >= Self.minVisibleDelta - 1e-9 }

    /// Je Notiz mit genug Nächten: nur Nächte mit Befinden (1–5) zählen, je Nacht ihre Notizen
    /// (nach Nachtdatum). Mehrere Nächte mit demselben Nachtdatum zählen einmal (die zuletzt geweckte),
    /// wie in Kotlin. `asleepByNight` = geschlafene Minuten je Nachtdatum, falls bekannt.
    /// Sortiert nach |delta| absteigend, bei Gleichstand nach Kennung.
    public static func compute(nights: [NightRecord], notes: [String: [String]], asleepByNight: [String: Int] = [:],
                               calendar: Calendar) -> [NoteEffect] {
        var latest: [String: NightRecord] = [:]
        for night in NightHistory.trim(nights) where night.feeling != nil {
            let date = SleepNotes.nightDate(of: night, calendar: calendar)
            if let known = latest[date], known.wokeAt >= night.wokeAt { continue }
            latest[date] = night
        }
        let rated = latest.keys.sorted().compactMap { date -> (date: String, feeling: Int)? in
            latest[date]?.feeling.map { (date: date, feeling: $0) }
        }
        let ids = SleepNotes.normalize(rated.flatMap { notes[$0.date] ?? [] })
        func average(_ values: [Int]) -> Double { Double(values.reduce(0, +)) / Double(values.count) }
        let effects: [NoteEffect] = ids.compactMap { id in
            let with = rated.filter { notes[$0.date]?.contains(id) == true }
            let without = rated.filter { notes[$0.date]?.contains(id) != true }
            guard with.count >= minNights, without.count >= minNights else { return nil }
            let asleepWith = with.compactMap { asleepByNight[$0.date] }
            let asleepWithout = without.compactMap { asleepByNight[$0.date] }
            let asleepKnown = asleepWith.count >= minNights && asleepWithout.count >= minNights
            return NoteEffect(
                noteId: id, nightsWith: with.count, nightsWithout: without.count,
                avgFeelingWith: average(with.map(\.feeling)), avgFeelingWithout: average(without.map(\.feeling)),
                avgAsleepWith: asleepKnown ? average(asleepWith) : nil, avgAsleepWithout: asleepKnown ? average(asleepWithout) : nil
            )
        }
        return effects.sorted { abs($0.delta) != abs($1.delta) ? abs($0.delta) > abs($1.delta) : $0.noteId < $1.noteId }
    }
}

/// Schlafziel in Minuten. Identisch zu Kotlin.
public enum SleepGoal {
    /// Standardziel: 7,5 Stunden.
    public static let defaultMinutes = 450
    /// Kleinstes Ziel: 6 Stunden.
    public static let minMinutes = 360
    /// Grösstes Ziel: 10 Stunden.
    public static let maxMinutes = 600
    /// Raster der Einstellung in Minuten.
    public static let stepMinutes = 15
    /// So viele Nächte zählt der Wochenstatus.
    public static let weekNights = 7

    /// Auf den erlaubten Bereich und das 15-Minuten-Raster gebracht.
    public static func clamp(_ minutes: Int) -> Int {
        let stepped = Int((Double(minutes) / Double(stepMinutes)).rounded()) * stepMinutes
        return min(maxMinutes, max(minMinutes, stepped))
    }

    /// Ob `asleepMinutes` das Ziel erreicht (genau gleich zählt als erreicht).
    public static func reached(asleepMinutes: Int, goalMinutes: Int) -> Bool { asleepMinutes >= goalMinutes }

    /// Wie viele der letzten 7 Nächte (die 7 jüngsten Nachtdaten) das Ziel erreicht haben: (erreicht, gezählt).
    /// Mehrere Einträge zum selben Nachtdatum zählen als eine Nacht mit dem längsten Schlaf.
    public static func weekStatus(_ nights: [(date: String, asleepMinutes: Int)], goalMinutes: Int) -> (reached: Int, total: Int) {
        var byDate: [String: Int] = [:]
        for night in nights { byDate[night.date] = max(byDate[night.date] ?? 0, night.asleepMinutes) }
        let recent = byDate.keys.sorted().suffix(weekNights)
        let count = recent.filter { reached(asleepMinutes: byDate[$0] ?? 0, goalMinutes: goalMinutes) }.count
        return (count, recent.count)
    }
}

/// Bettzeit vor dem nächsten Wecker. Identisch zu Kotlin.
public enum Bedtime {
    /// Erinnerung so viele Minuten vor der Bettzeit.
    public static let leadMinutes = 30

    /// Zielzeit des nächsten eingeschalteten Weckers minus Schlafziel. Bei Schlafdauer-Weckern ist die
    /// Zielzeit ihre späteste Zeit (so plant sie `AlarmSchedule` ohnehin).
    public static func bedtime(nextAlarmTarget: Date, goalMinutes: Int) -> Date {
        nextAlarmTarget.addingTimeInterval(-TimeInterval(goalMinutes * 60))
    }

    /// Zeitpunkt der Bettzeit-Erinnerung: `leadMinutes` Minuten vor der Bettzeit.
    public static func reminderAt(nextAlarmTarget: Date, goalMinutes: Int, leadMinutes: Int = Bedtime.leadMinutes) -> Date {
        bedtime(nextAlarmTarget: nextAlarmTarget, goalMinutes: goalMinutes).addingTimeInterval(-TimeInterval(leadMinutes * 60))
    }
}
