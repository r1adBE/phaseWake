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
        guard text.count == 10, parts.count == 3,
              parts.allSatisfy({ $0.allSatisfy(\.isASCII) && $0.allSatisfy(\.isNumber) }),
              let year = Int(parts[0]), let month = Int(parts[1]), let day = Int(parts[2]),
              let date = utc.date(from: DateComponents(year: year, month: month, day: day)),
              SleepNotes.dateString(date, calendar: utc) == text else { return nil }
        return date
    }

    /// Ob der Text ein gültiges Datum «yyyy-MM-dd» ist (auch Monatslänge und Schaltjahr).
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
    public static func today(_ now: Date,
                             calendar: Calendar) -> String { SleepNotes.dateString(now, calendar: calendar) }
}

extension NoteEffect {
    /// Schlafdauer je Nachtdatum aus der Gesundheits-App: Nachtdatum über die Mitte der Nacht
    /// (`SleepNotes.nightDate(of:)`); mehrere Nächte am selben Datum → die längste. Identisch zu
    /// `NoteEffect.asleepByNight` (Kotlin).
    public static func asleepByNight(_ sleeps: [SleepSummary], calendar: Calendar) -> [String: Int] {
        sleeps.reduce(into: [:]) { out, sleep in
            let date = SleepNotes.nightDate(of: sleep, calendar: calendar)
            out[date] = max(out[date] ?? Int.min, sleep.asleepMinutes)
        }
    }
}

/// Regelmässigkeit: wie oft die Bettzeit (Einschlafbeginn laut Gesundheits-App) in einer Woche höchstens
/// 30 Minuten vom Median der letzten 14 Nächte abweicht. Ohne Gesundheitsdaten gibt es nichts zu zählen.
/// Identisch zu `Regularity` (Kotlin).
public enum Regularity {
    /// Höchste Abweichung vom Median in Minuten, die noch als regelmässig zählt.
    public static let toleranceMinutes = 30
    /// Abende einer Woche.
    public static let weekNights = 7
    /// Nachtdaten, über die der Median gebildet wird.
    public static let medianNights = 14

    /// Bettzeiten als (Nachtdatum, Einschlafbeginn, Zone der Aufzeichnung) aus den Nächten der Gesundheits-App
    /// (``SleepSummary/timeZone``; `nil` = Zone des Geräts).
    public static func bedtimes(of sleeps: [SleepSummary],
                                calendar: Calendar) -> [(date: String, start: Date, timeZone: TimeZone?)] {
        sleeps.map { (date: SleepNotes.nightDate(of: $0, calendar: calendar), start: $0.start, timeZone: $0.timeZone) }
    }

    /// (regelmässig, gezählt) für die 7 Nachtdaten bis `until` (einschliesslich), gemessen am Median der
    /// 14 Nachtdaten bis `until`. Je Nachtdatum zählt die früheste Bettzeit. `until` `nil`: das jüngste
    /// Nachtdatum mit Daten. Ohne Daten (0, 0). Uhrzeiten in `calendar`.
    public static func count(_ bedtimes: [(date: String, start: Date)], calendar: Calendar,
                             toleranceMinutes: Int = Regularity.toleranceMinutes,
                             until: String? = nil) -> (regular: Int, total: Int) {
        count(bedtimes.map { (date: $0.date, start: $0.start, timeZone: TimeZone?.none) }, calendar: calendar,
              toleranceMinutes: toleranceMinutes, until: until)
    }

    /// Wie oben, jede Bettzeit in ihrer eigenen Zone (``bedtimes(of:calendar:)``; ohne Zone `calendar`).
    public static func count(_ bedtimes: [(date: String, start: Date, timeZone: TimeZone?)], calendar: Calendar,
                             toleranceMinutes: Int = Regularity.toleranceMinutes,
                             until: String? = nil) -> (regular: Int, total: Int) {
        var byDate: [String: (start: Date, timeZone: TimeZone?)] = [:]
        for b in bedtimes where byDate[b.date].map({ b.start < $0.start }) ?? true { byDate[b.date] = (b.start,
                                                                                                       b.timeZone) }
        guard let end = until ?? byDate.keys.max() else { return (0, 0) }
        let medianFrom = NightDate.adding(-medianNights, to: end)
        let medianDates = byDate.filter { $0.key <= end && $0.key > medianFrom }
        guard !medianDates.isEmpty else { return (0, 0) }
        // Ohne Sprung um Mittag (`unwrap`): 11:30 und 12:30 liegen 60 Min auseinander, nicht 1380.
        let entries = Array(medianDates)
        let unwrapped = unwrap(entries.map { entry -> Double in
            var local = calendar
            if let zone = entry.value.timeZone { local.timeZone = zone }
            return minutesFromNoon(entry.value.start, calendar: local)
        })
        let minutes = Dictionary(uniqueKeysWithValues: zip(entries.map(\.key), unwrapped))
        let median = SoundEventDetector.median(Array(minutes.values))
        let weekFrom = NightDate.adding(-weekNights, to: end)
        let week = minutes.filter { $0.key > weekFrom }
        let regular = week.values.filter { abs($0 - median) <= Double(toleranceMinutes) + 1e-9 }.count
        return (regular, week.count)
    }

    /// Uhrzeit als Minuten ab Mittag, damit 23:30 und 00:30 nahe beieinander liegen.
    public static func minutesFromNoon(_ at: Date, calendar: Calendar) -> Double {
        let c = calendar.dateComponents([.hour, .minute], from: at)
        let m = (c.hour ?? 0) * 60 + (c.minute ?? 0) - 12 * 60
        return Double(((m % dayMinutes) + dayMinutes) % dayMinutes)
    }

    /// Minuten eines Tages: Umfang der Uhr in ``minutesFromNoon(_:calendar:)``.
    public static let dayMinutes = 24 * 60

    /// Uhrzeiten in Minuten ab Mittag (0 ≤ m < ``dayMinutes``) ohne Sprung: Die Uhr wird in der grössten
    /// Lücke zwischen den Zeiten aufgeschnitten, damit auch Zeiten um Mittag (11:56 und 12:50) nahe beieinander liegen.
    /// Ist die Lücke um Mittag die grösste (bei Gleichstand bevorzugt), bleiben die Werte wie sie sind; sonst bekommen
    /// die Zeiten bis zur grössten Lücke ``dayMinutes`` dazu. Reihenfolge wie `minutes`. Identisch zu Kotlin.
    public static func unwrap(_ minutes: [Double]) -> [Double] {
        guard minutes.count >= 2 else { return minutes }
        let sorted = minutes.sorted()
        // Lücke um Mittag: von der spätesten Zeit bis zur frühesten des nächsten Tages.
        var widest = sorted[0] + Double(dayMinutes) - sorted[sorted.count - 1]
        var cut: Double?
        for i in 0..<(sorted.count - 1) {
            let gap = sorted[i + 1] - sorted[i]
            if gap > widest {
                widest = gap
                cut = sorted[i]
            }
        }
        guard let at = cut else { return minutes }
        return minutes.map { $0 <= at ? $0 + Double(dayMinutes) : $0 }
    }

    /// Mittlere Uhrzeit der `minutes` (Minuten ab Mittag) ohne Sprung um Mittag (``unwrap(_:)``), gerundet (x.5
    /// aufwärts, wie `Math.round`) und wieder in 0 ≤ m < ``dayMinutes``. `minutes` darf nicht leer sein.
    public static func meanMinute(_ minutes: [Double]) -> Int {
        let values = unwrap(minutes)
        let rounded = Int((values.reduce(0, +) / Double(values.count) + 0.5).rounded(.down))
        return ((rounded % dayMinutes) + dayMinutes) % dayMinutes
    }
}

/// Serien: Nächte in Folge mit erreichtem Schlafziel. Keine Punkte, keine Abzeichen. Identisch zu Kotlin.
public enum Streak {
    /// Zeitraum für die längste Serie in Nachtdaten (wie die gespeicherten Notizen).
    public static let longestDays = 90

    /// Schlafziel erreicht je Nachtdatum (aus der Gesundheits-App; mehrere Nächte am selben Datum → die längste).
    public static func reachedByNight(_ sleeps: [SleepSummary], calendar: Calendar,
                                      goalMinutes: Int) -> [String: Bool] {
        NoteEffect.asleepByNight(sleeps,
                                 calendar: calendar).mapValues { SleepGoal.reached(asleepMinutes: $0,
                                                                                   goalMinutes: goalMinutes) }
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
    /// Erstes Nachtdatum der Woche (Sonntag).
    public let from: String
    /// Letztes Nachtdatum der Woche (Samstag).
    public let to: String
    /// Durchschnittlicher Schlaf in Minuten; `nil` ohne Gesundheitsdaten.
    public let asleepAverage: Int?
    /// Nächte mit erreichtem Schlafziel (von `nightsWithSleep`).
    public let goalReached: Int
    /// Nächte mit Gesundheitsdaten in dieser Woche.
    public let nightsWithSleep: Int
    /// Regelmässige Abende (von `regularCounted`).
    public let regular: Int
    /// Gezählte Abende (Nenner zu `regular`).
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
    /// Stunde der Mitteilung (Ortszeit).
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

    /// Rechnet den Rückblick der Woche, die mit `weekEnd` endet.
    /// - Parameters:
    ///   - nights: Uhr-Nächte für das Befinden (je Nachtdatum die zuletzt geweckte).
    ///   - sleeps: Nächte der Gesundheits-App für Schlaf, Ziel und Regelmässigkeit.
    ///   - notes: Notizen je Nachtdatum für die stärkste Wirkung.
    ///   - goalMinutes: Schlafziel in Minuten.
    ///   - health: Befinden zu Nächten aus anderen Apps (zählt an Nachtdaten ohne eigenes).
    public static func of(nights: [NightRecord], sleeps: [SleepSummary], notes: [String: [String]], calendar: Calendar,
                          goalMinutes: Int, weekEnd: String, health: [HealthFeeling] = []) -> WeeklySummary {
        let from = NightDate.adding(-(Regularity.weekNights - 1), to: weekEnd)
        func inWeek(_ date: String) -> Bool { date >= from && date <= weekEnd }
        let allAsleep = NoteEffect.asleepByNight(sleeps, calendar: calendar)
        let asleep = Array(allAsleep.filter { inWeek($0.key) }.values)
        let regularity = Regularity.count(Regularity.bedtimes(of: sleeps, calendar: calendar), calendar: calendar,
                                          until: weekEnd)
        // Je Nachtdatum das Befinden der zuletzt geweckten Nacht (wie bei den Notizen), sonst das der Fremd-Nacht.
        let feelings = Array(HealthFeelings.byDate(nights, health: health,
                                                   calendar: calendar).filter { inWeek($0.key) }.values)
        let strongest = NoteEffect.compute(nights: nights, notes: notes, asleepByNight: allAsleep, health: health,
                                           calendar: calendar)
            .first(where: \.isVisible)
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
