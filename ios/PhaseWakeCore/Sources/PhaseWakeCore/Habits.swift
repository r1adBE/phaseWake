import Foundation

/// Rechnen mit Nachtdaten «yyyy-MM-dd» ohne Zeitzone (wie `LocalDate` in Kotlin).
public enum NightDate {
    private static let utc: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "UTC")!
        return c
    }()

    /// Mitternacht UTC des Datums; `nil` bei ungültigem Text (auch «2026-02-30»).
    static func date(_ text: String) -> Date? {
        let parts = text.split(separator: "-", omittingEmptySubsequences: false)
        guard text.count == 10, parts.count == 3, parts.allSatisfy({ $0.allSatisfy(\.isASCII) && $0.allSatisfy(\.isNumber) }),
              let year = Int(parts[0]), let month = Int(parts[1]), let day = Int(parts[2]),
              let date = utc.date(from: DateComponents(year: year, month: month, day: day)),
              SleepNotes.dateString(date, calendar: utc) == text else { return nil }
        return date
    }

    public static func isValid(_ text: String) -> Bool { date(text) != nil }

    /// Datum plus `days` Tage; ungültiger Text bleibt unverändert.
    public static func adding(_ days: Int, to text: String) -> String {
        guard let day = date(text), let moved = utc.date(byAdding: .day, value: days, to: day) else { return text }
        return SleepNotes.dateString(moved, calendar: utc)
    }

    /// Wochentag des Datums.
    public static func weekday(_ text: String) -> Weekday? {
        date(text).map { Weekday(date: $0, calendar: utc) }
    }

    /// Kalenderdatum «heute» in der Ortszeit.
    public static func today(_ now: Date, calendar: Calendar) -> String { SleepNotes.dateString(now, calendar: calendar) }
}

extension NoteEffect {
    /// Schlafdauer je Nachtdatum aus der Gesundheits-App: Nachtdatum über das Ende der Nacht; mehrere
    /// Nächte am selben Datum → die längste. Identisch zu `NoteEffect.asleepByNight` (Kotlin).
    public static func asleepByNight(_ sleeps: [SleepSummary], calendar: Calendar) -> [String: Int] {
        sleeps.reduce(into: [:]) { out, sleep in
            let date = SleepNotes.nightDate(sleep.end, calendar: calendar)
            out[date] = max(out[date] ?? Int.min, sleep.asleepMinutes)
        }
    }
}

/// Regelmässigkeit: wie oft die Bettzeit (Einschlafbeginn laut Gesundheits-App) in einer Woche höchstens
/// 30 Minuten vom Median der letzten 14 Nächte abweicht. Ohne Gesundheitsdaten gibt es nichts zu zählen.
/// Identisch zu `Regularity` (Kotlin).
public enum Regularity {
    public static let toleranceMinutes = 30
    public static let weekNights = 7
    public static let medianNights = 14

    /// Bettzeiten als (Nachtdatum, Einschlafbeginn) aus den Nächten der Gesundheits-App.
    public static func bedtimes(of sleeps: [SleepSummary], calendar: Calendar) -> [(date: String, start: Date)] {
        sleeps.map { (date: SleepNotes.nightDate($0.end, calendar: calendar), start: $0.start) }
    }

    /// (regelmässig, gezählt) für die 7 Nachtdaten bis `until` (einschliesslich), gemessen am Median der
    /// 14 Nachtdaten bis `until`. Je Nachtdatum zählt die früheste Bettzeit. `until` `nil`: das jüngste
    /// Nachtdatum mit Daten. Ohne Daten (0, 0).
    public static func count(_ bedtimes: [(date: String, start: Date)], calendar: Calendar,
                             toleranceMinutes: Int = Regularity.toleranceMinutes, until: String? = nil) -> (regular: Int, total: Int) {
        var byDate: [String: Date] = [:]
        for b in bedtimes { byDate[b.date] = min(byDate[b.date] ?? b.start, b.start) }
        guard let end = until ?? byDate.keys.max() else { return (0, 0) }
        let medianFrom = NightDate.adding(-medianNights, to: end)
        let medianDates = byDate.filter { $0.key <= end && $0.key > medianFrom }
        guard !medianDates.isEmpty else { return (0, 0) }
        let median = SoundEventDetector.median(medianDates.values.map { minutesFromNoon($0, calendar: calendar) })
        let weekFrom = NightDate.adding(-weekNights, to: end)
        let week = medianDates.filter { $0.key > weekFrom }
        let regular = week.values.filter { abs(minutesFromNoon($0, calendar: calendar) - median) <= Double(toleranceMinutes) + 1e-9 }.count
        return (regular, week.count)
    }

    /// Uhrzeit als Minuten ab Mittag, damit 23:30 und 00:30 nahe beieinander liegen.
    public static func minutesFromNoon(_ at: Date, calendar: Calendar) -> Double {
        let c = calendar.dateComponents([.hour, .minute], from: at)
        let m = (c.hour ?? 0) * 60 + (c.minute ?? 0) - 12 * 60
        return Double(((m % 1440) + 1440) % 1440)
    }
}

/// Serien: Nächte in Folge mit erreichtem Schlafziel. Keine Punkte, keine Abzeichen. Identisch zu Kotlin.
public enum Streak {
    public static let longestDays = 90

    /// Schlafziel erreicht je Nachtdatum (aus der Gesundheits-App; mehrere Nächte am selben Datum → die längste).
    public static func reachedByNight(_ sleeps: [SleepSummary], calendar: Calendar, goalMinutes: Int) -> [String: Bool] {
        NoteEffect.asleepByNight(sleeps, calendar: calendar).mapValues { SleepGoal.reached(asleepMinutes: $0, goalMinutes: goalMinutes) }
    }

    /// Aktuelle Serie bis zur jüngsten Nacht. `today` = Nachtdatum von «jetzt» (`SleepNotes.nightDate`):
    /// Hat diese Nacht noch keine Daten (Abend), beginnt die Zählung bei der Nacht davor. Eine Nacht ohne
    /// Daten oder mit verfehltem Ziel beendet die Serie.
    public static func current(_ reached: [String: Bool], today: String) -> Int {
        var date = reached[today] != nil ? today : NightDate.adding(-1, to: today)
        var count = 0
        while reached[date] == true {
            count += 1
            date = NightDate.adding(-1, to: date)
        }
        return count
    }

    /// Längste Serie innerhalb der `days` Nachtdaten bis `today`.
    public static func longest(_ reached: [String: Bool], today: String, days: Int = Streak.longestDays) -> Int {
        var best = 0
        var run = 0
        var date = NightDate.adding(-(days - 1), to: today)
        while date <= today {
            if reached[date] == true {
                run += 1
                best = max(best, run)
            } else {
                run = 0
            }
            date = NightDate.adding(1, to: date)
        }
        return best
    }
}

/// Wochenrückblick über die Nachtdaten `from` bis `to` (Sonntag bis Samstag; am Sonntag ist die
/// Samstagnacht die letzte abgeschlossene). Identisch zu `WeeklySummary` (Kotlin).
public struct WeeklySummary: Equatable, Sendable {
    public let from: String
    public let to: String
    /// Durchschnittlicher Schlaf in Minuten; `nil` ohne Gesundheitsdaten.
    public let asleepAverage: Int?
    /// Nächte mit erreichtem Schlafziel (von `nightsWithSleep`).
    public let goalReached: Int
    public let nightsWithSleep: Int
    /// Regelmässige Abende (von `regularCounted`).
    public let regular: Int
    public let regularCounted: Int
    /// Durchschnittliches Befinden; `nil` ohne Angaben.
    public let feelingAverage: Double?
    /// Notiz mit der deutlichsten Wirkung (über die letzten 90 Nächte), falls eine.
    public let strongestNote: NoteEffect?

    /// Gesundheitsdaten in dieser Woche.
    public var hasSleep: Bool { nightsWithSleep > 0 }
    /// Etwas zu berichten: Schlaf oder Befinden.
    public var hasData: Bool { hasSleep || feelingAverage != nil }

    /// Mitteilung am Sonntag um 18:00.
    public static let notifyWeekday = Weekday.sunday
    public static let notifyHour = 18

    /// Letztes Nachtdatum der zuletzt abgeschlossenen Woche: der Samstag vor `today` (Kalenderdatum).
    public static func weekEnd(today: String) -> String {
        var date = NightDate.adding(-1, to: today)
        var steps = 0
        while NightDate.weekday(date) != .saturday, steps < 7 {
            date = NightDate.adding(-1, to: date)
            steps += 1
        }
        return date
    }

    /// Die Karte im Verlauf erscheint am Sonntag und am Montag (Kalenderdatum).
    public static func cardVisible(today: String) -> Bool {
        let day = NightDate.weekday(today)
        return day == .sunday || day == .monday
    }

    /// Nächster Zeitpunkt der Mitteilung echt nach `after`: Sonntag 18:00 Ortszeit.
    public static func nextNotification(after: Date, calendar: Calendar) -> Date? {
        // Calendar-Wochentag: 1 = Sonntag.
        calendar.nextDate(after: after, matching: DateComponents(hour: notifyHour, minute: 0, second: 0, weekday: 1),
                          matchingPolicy: .nextTime)
    }

    public static func of(nights: [NightRecord], sleeps: [SleepSummary], notes: [String: [String]], calendar: Calendar,
                          goalMinutes: Int, weekEnd: String) -> WeeklySummary {
        let from = NightDate.adding(-(Regularity.weekNights - 1), to: weekEnd)
        func inWeek(_ date: String) -> Bool { date >= from && date <= weekEnd }
        let allAsleep = NoteEffect.asleepByNight(sleeps, calendar: calendar)
        let asleep = Array(allAsleep.filter { inWeek($0.key) }.values)
        let regularity = Regularity.count(Regularity.bedtimes(of: sleeps, calendar: calendar), calendar: calendar, until: weekEnd)
        // Je Nachtdatum das Befinden der zuletzt geweckten Nacht (wie bei den Notizen).
        var latest: [String: NightRecord] = [:]
        for night in nights where night.feeling != nil {
            let date = SleepNotes.nightDate(of: night, calendar: calendar)
            if let current = latest[date], current.wokeAt >= night.wokeAt { continue }
            latest[date] = night
        }
        let feelings = latest.filter { inWeek($0.key) }.compactMap { $0.value.feeling }
        let strongest = NoteEffect.compute(nights: nights, notes: notes, asleepByNight: allAsleep, calendar: calendar).first(where: \.isVisible)
        return WeeklySummary(
            from: from,
            to: weekEnd,
            asleepAverage: asleep.isEmpty ? nil : asleep.reduce(0, +) / asleep.count,
            goalReached: asleep.filter { SleepGoal.reached(asleepMinutes: $0, goalMinutes: goalMinutes) }.count,
            nightsWithSleep: asleep.count,
            regular: regularity.regular,
            regularCounted: regularity.total,
            feelingAverage: feelings.isEmpty ? nil : Double(feelings.reduce(0, +)) / Double(feelings.count),
            strongestNote: strongest
        )
    }
}

/// Ein Punkt der Abendroutine. Vorgeschlagene Punkte haben einen leeren `text` (Anzeige übersetzt).
public struct RoutineItem: Equatable, Sendable, Identifiable {
    public let id: String
    public let text: String

    public init(id: String, text: String = "") {
        self.id = id
        self.text = text
    }
}

/// Abendroutine: eine eigene Checkliste (höchstens 8 Punkte) für den Abend, sichtbar wie die Notizen ab
/// 17:00 bis zum Weckfenster. Abgehakt wird je Nachtdatum; am nächsten Abend ist alles frei.
/// Speicherformat: Punkte je Zeile «id|Text»; abgehakte Punkte je Nachtdatum «yyyy-MM-dd|id,id».
/// Identisch zu `Routine` (Kotlin).
public enum Routine {
    public static let phone = "PHONE"
    public static let light = "LIGHT"
    public static let water = "WATER"

    /// Die drei Vorschläge für den Anfang.
    public static let defaults: [RoutineItem] = [RoutineItem(id: phone), RoutineItem(id: light), RoutineItem(id: water)]

    public static let maxItems = 8
    public static let maxTextLength = 40
    public static let customPrefix = "custom:"
    /// Durchschnitt erst ab so vielen Abenden …
    public static let minEvenings = 3
    /// … über so viele Nachtdaten bis heute.
    public static let averageNights = 30
    /// Abgehakt bleibt so viele Nachtdaten (wie die Notizen).
    public static let maxDates = NightHistory.maxNights

    public static func isBuiltIn(_ id: String) -> Bool { id == phone || id == light || id == water }

    public static func customId(_ uuid: String) -> String { customPrefix + uuid }

    public static func cleanText(_ text: String) -> String {
        let flat = text.replacingOccurrences(of: "\n", with: " ").replacingOccurrences(of: "\r", with: " ").replacingOccurrences(of: "|", with: " ")
        return String(flat.trimmingCharacters(in: .whitespaces).prefix(maxTextLength)).trimmingCharacters(in: .whitespaces)
    }

    public static func encodeItems(_ items: [RoutineItem]) -> String {
        items.prefix(maxItems).map { "\($0.id)|\(cleanText($0.text))" }.joined(separator: "\n")
    }

    /// Liest `encodeItems`; nie gespeichert (`nil`): die Vorschläge. Eigene Punkte ohne Text fallen weg.
    public static func decodeItems(_ text: String?) -> [RoutineItem] {
        guard let text else { return defaults }
        var out: [RoutineItem] = []
        for line in text.components(separatedBy: "\n") where !line.trimmingCharacters(in: .whitespaces).isEmpty {
            let parts = line.split(separator: "|", maxSplits: 1, omittingEmptySubsequences: false)
            let id = String(parts[0]).trimmingCharacters(in: .whitespaces)
            let label = cleanText(parts.count > 1 ? String(parts[1]) : "")
            let item: RoutineItem?
            if isBuiltIn(id) {
                item = RoutineItem(id: id)
            } else if id.hasPrefix(customPrefix), id.count > customPrefix.count, !label.isEmpty {
                item = RoutineItem(id: id, text: label)
            } else {
                item = nil
            }
            if let item, !out.contains(where: { $0.id == item.id }) { out.append(item) }
        }
        return Array(out.prefix(maxItems))
    }

    /// Hakt einen Punkt für ein Nachtdatum ab oder wieder an; ein Datum ohne Haken fällt weg.
    public static func toggle(_ ticks: [String: [String]], date: String, id: String) -> [String: [String]] {
        let current = ticks[date] ?? []
        let updated = current.contains(id) ? current.filter { $0 != id } : current + [id]
        var result = ticks
        result[date] = updated.isEmpty ? nil : updated
        return trim(result)
    }

    /// Behält die jüngsten 90 Nachtdaten mit Haken.
    public static func trim(_ ticks: [String: [String]]) -> [String: [String]] {
        let kept = ticks.filter { !$0.value.isEmpty }.keys.sorted().suffix(maxDates)
        return Dictionary(uniqueKeysWithValues: kept.map { ($0, ticks[$0] ?? []) })
    }

    /// Je Nachtdatum eine Zeile «yyyy-MM-dd|id,id», nach Datum sortiert.
    public static func encodeTicks(_ ticks: [String: [String]]) -> String {
        trim(ticks).sorted { $0.key < $1.key }.map { "\($0.key)|\(unique($0.value).joined(separator: ","))" }.joined(separator: "\n")
    }

    /// Liest `encodeTicks`; unlesbare Zeilen werden übersprungen.
    public static func decodeTicks(_ text: String?) -> [String: [String]] {
        var out: [String: [String]] = [:]
        for line in (text ?? "").components(separatedBy: "\n") {
            let parts = line.split(separator: "|", maxSplits: 1, omittingEmptySubsequences: false)
            let date = String(parts.first ?? "")
            guard NightDate.isValid(date) else { continue }
            let ids = unique((parts.count > 1 ? String(parts[1]) : "").components(separatedBy: ",")
                .map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty })
            if !ids.isEmpty { out[date] = ids }
        }
        return trim(out)
    }

    /// Durchschnittlich erledigte Punkte je Abend über die 30 Nachtdaten bis `today`; es zählen nur Abende
    /// mit mindestens einem Haken und nur Punkte, die es noch gibt. `nil` unter 3 Abenden oder ohne Punkte.
    public static func average(_ ticks: [String: [String]], items: [RoutineItem], today: String) -> Double? {
        guard !items.isEmpty else { return nil }
        let ids = Set(items.map(\.id))
        let from = NightDate.adding(-(averageNights - 1), to: today)
        let counts = ticks.filter { $0.key >= from && $0.key <= today }.values
            .map { list in list.filter { ids.contains($0) }.count }
            .filter { $0 > 0 }
        guard counts.count >= minEvenings else { return nil }
        return Double(counts.reduce(0, +)) / Double(counts.count)
    }

    private static func unique(_ ids: [String]) -> [String] {
        var seen = Set<String>()
        return ids.filter { seen.insert($0).inserted }
    }
}
