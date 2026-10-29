import Foundation

/// Zeitplan einer Weck-Nacht.
public struct WakePlan: Equatable, Sendable {
    /// Kennung des Weckers, zu dem der Plan gehört.
    public let alarmId: String
    /// Beginn des Weckfensters.
    public let windowStart: Date
    /// Zielzeit: spätester Weckmoment.
    public let target: Date

    /// Erzeugt einen Plan; `windowStart` liegt vor `target`.
    public init(alarmId: String, windowStart: Date, target: Date) {
        self.alarmId = alarmId
        self.windowStart = windowStart
        self.target = target
    }
}

/// Berechnet, wann ein Wecker das nächste Mal fällig ist. Identisch zu `AlarmSchedule` (Kotlin).
///
/// Feiertage kommen als Nachtdaten «yyyy-MM-dd» (Datum der Zielzeit im Kalender), wie `LocalDate` in Kotlin.
public enum AlarmSchedule {
    /// Wie weit voraus gesucht wird; eine Woche plus ein Tag deckt jeden Wochentag ab.
    private static let searchDays = 8

    /// Grenze der Suche, wenn Termine ausfallen (Überspringen, Feiertage): gut ein Jahr. Auch eine
    /// unsinnige Feiertagsliste (jeder Tag) endet so mit `nil` statt in einer endlosen Suche.
    public static let maxSearchDays = 370

    /// Nächste Zielzeit, die echt nach `now` liegt, oder `nil`, wenn der Wecker aus ist.
    /// Sommer-/Winterzeit: in der Lücke die erste gültige Zeit danach, in der Überlappung die frühere.
    /// Ein wiederholter Wecker lässt Termine bis `skipUntil` aus und, mit `skipHolidays`, Termine an `holidays`.
    public static func nextTarget(_ spec: AlarmSpec, now: Date, calendar: Calendar, holidays: Set<String> = []) -> Date? {
        guard spec.enabled else { return nil }
        let today = calendar.startOfDay(for: now)
        let limit = mayDrop(spec, holidays: holidays) ? maxSearchDays : searchDays
        for offset in 0...limit {
            guard let day = calendar.date(byAdding: .day, value: offset, to: today) else { continue }
            if !spec.isOneShot, !spec.days.contains(Weekday(date: day, calendar: calendar)) { continue }
            var parts = calendar.dateComponents([.year, .month, .day], from: day)
            parts.hour = spec.hour
            parts.minute = spec.minute
            guard let target = resolve(parts, calendar: calendar), target > now else { continue }
            if !dropped(spec, target: target, date: SleepNotes.dateString(day, calendar: calendar), holidays: holidays) { return target }
        }
        return nil
    }

    /// Plan für die nächste Nacht des Weckers (Zielzeit echt nach `now`), oder `nil`, wenn er aus ist.
    public static func nextPlan(_ spec: AlarmSpec, now: Date, calendar: Calendar, holidays: Set<String> = []) -> WakePlan? {
        guard let target = nextTarget(spec, now: now, calendar: calendar, holidays: holidays) else { return nil }
        return WakePlan(alarmId: spec.id, windowStart: windowStart(target, windowMinutes: spec.windowMinutes), target: target)
    }

    /// Der früheste Plan über alle Wecker, oder `nil`, wenn keiner aktiv ist. Bei gleicher Zielzeit gewinnt die kleinere Kennung, damit beide Plattformen denselben Wecker wählen.
    public static func earliestPlan(_ specs: [AlarmSpec], now: Date, calendar: Calendar, holidays: Set<String> = []) -> WakePlan? {
        earliestPlan(specs, now: now, doneUntil: [:], calendar: calendar, holidays: holidays)
    }

    /// Wie oben, aber pro Wecker erst ab `doneUntil[id]` (erledigte bzw. ausgesetzte Nacht).
    /// Ein zweiter Wecker am selben Morgen bleibt davon unberührt.
    public static func earliestPlan(_ specs: [AlarmSpec], now: Date, doneUntil: [String: Date], calendar: Calendar,
                                    holidays: Set<String> = []) -> WakePlan? {
        specs.compactMap { nextPlan($0, now: max(now, doneUntil[$0.id] ?? .distantPast), calendar: calendar, holidays: holidays) }
            .min { ($0.target, $0.alarmId) < ($1.target, $1.alarmId) }
    }

    /// «Nächstes Mal überspringen»: `skipUntil` = nächste Zielzeit nach allen Regeln (auch Feiertagen).
    /// Einmal-Wecker, ausgeschaltete Wecker oder ohne Termin: unverändert.
    public static func skipNext(_ spec: AlarmSpec, now: Date, calendar: Calendar, holidays: Set<String> = []) -> AlarmSpec {
        guard !spec.isOneShot, let target = nextTarget(spec, now: now, calendar: calendar, holidays: holidays) else { return spec }
        return spec.with(skipUntil: millis(target))
    }

    /// Überspringen rückgängig: `skipUntil` = 0.
    public static func undoSkip(_ spec: AlarmSpec) -> AlarmSpec { spec.with(skipUntil: 0) }

    /// Datum «yyyy-MM-dd» des übersprungenen Termins (`skipUntil`), solange er nach `now` liegt; sonst `nil`.
    public static func skippedDate(_ spec: AlarmSpec, now: Date, calendar: Calendar) -> String? {
        guard !spec.isOneShot, spec.skipUntil > millis(now) else { return nil }
        return SleepNotes.dateString(Date(timeIntervalSince1970: TimeInterval(spec.skipUntil) / 1000), calendar: calendar)
    }

    /// Datum des nächsten Termins ohne Feiertagsregel (aber mit `skipUntil`), wenn es ein Feiertag ist und
    /// `skipHolidays` gilt; sonst `nil`. Für «Do 30.10.: Feiertag – nicht geweckt».
    public static func skippedHoliday(_ spec: AlarmSpec, now: Date, calendar: Calendar, holidays: Set<String>) -> String? {
        guard spec.skipHolidays, !spec.isOneShot, !holidays.isEmpty,
              let target = nextTarget(spec.with(skipHolidays: false), now: now, calendar: calendar) else { return nil }
        let date = SleepNotes.dateString(target, calendar: calendar)
        return holidays.contains(date) ? date : nil
    }

    /// Ob bei diesem Wecker überhaupt Termine ausfallen können (nur wiederholte Wecker).
    private static func mayDrop(_ spec: AlarmSpec, holidays: Set<String>) -> Bool {
        !spec.isOneShot && (spec.skipUntil > 0 || (spec.skipHolidays && !holidays.isEmpty))
    }

    /// Ob der Termin `target` am Datum `date` («yyyy-MM-dd») ausfällt: übersprungen oder Feiertag.
    private static func dropped(_ spec: AlarmSpec, target: Date, date: String, holidays: Set<String>) -> Bool {
        guard !spec.isOneShot else { return false }
        if millis(target) <= spec.skipUntil { return true }
        return spec.skipHolidays && holidays.contains(date)
    }

    /// Millisekunden seit 1970, gerundet wie im Textformat.
    static func millis(_ date: Date) -> Int64 { Int64((date.timeIntervalSince1970 * 1000).rounded()) }

    /// Einschlafzeit plus Schlafdauer, nie später als `latest`.
    public static func sleepTarget(onset: Date, sleepDurationMinutes: Int, latest: Date) -> Date {
        min(onset.addingTimeInterval(TimeInterval(sleepDurationMinutes * 60)), latest)
    }

    /// Beginn des Weckfensters: `windowMinutes` Minuten vor `target`.
    public static func windowStart(_ target: Date, windowMinutes: Int) -> Date {
        target.addingTimeInterval(-TimeInterval(windowMinutes * 60))
    }

    /// Wie `ZonedDateTime.of`: In der Lücke verschiebt Foundation um die Lückenlänge nach vorn,
    /// in der Überlappung wählt es die frühere Zeit (beides durch Tests abgesichert).
    private static func resolve(_ parts: DateComponents, calendar: Calendar) -> Date? {
        calendar.date(from: parts)
    }
}
