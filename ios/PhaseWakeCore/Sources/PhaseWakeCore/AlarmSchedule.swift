import Foundation

/// Zeitplan einer Weck-Nacht.
public struct WakePlan: Equatable, Sendable {
    /// Kennung des Weckers, zu dem der Plan gehört.
    public let alarmId: String
    /// Beginn des Weckfensters.
    public let windowStart: Date
    /// Zielzeit: spätester Weckmoment.
    public let target: Date
    /// «Genau» (``AlarmSpec/isExact``): ab `windowStart` wird nur für den Verlauf gemessen; geweckt wird erst zur
    /// Zielzeit, nie früher (Grund ``WakeReason/exact``).
    public let exact: Bool

    /// Erzeugt einen Plan; `windowStart` liegt vor `target`.
    public init(alarmId: String, windowStart: Date, target: Date, exact: Bool = false) {
        self.alarmId = alarmId
        self.windowStart = windowStart
        self.target = target
        self.exact = exact
    }
}

/// Zeitraum der zuletzt beendeten Nacht(en) auf der Uhr, für das Nachholen (``AlarmSchedule/missedPlan(_:catchUp:now:doneUntil:calendar:holidays:)``):
/// von `from` (Fensterbeginn) bis `until` (Ende). `alarmIds`: die eingeschalteten Wecker, deren Zielzeit in diesen
/// Zeitraum fiel – nur diese werden nachgeholt; ein danach neu angelegter oder eingeschalteter klingelt nicht
/// nachträglich. Identisch zu `CatchUpWindow` (Kotlin).
public struct CatchUpWindow: Equatable, Sendable, Codable {
    /// So lange nach dem Ende der Nacht wird noch nachgeholt (10 Minuten). Danach nicht mehr: Sonst klänge ein Wecker,
    /// der Stunden später wieder eingeschaltet wird, sofort für einen längst vergangenen Morgen.
    public static let valid: TimeInterval = 10 * 60

    /// Beginn: Fensterbeginn der ersten Nacht.
    public let from: Date
    /// Ende der letzten Nacht.
    public let until: Date
    /// Wecker, die nachgeholt werden dürfen.
    public let alarmIds: Set<String>

    /// Erzeugt einen Zeitraum.
    public init(from: Date, until: Date, alarmIds: Set<String>) {
        self.from = from
        self.until = until
        self.alarmIds = alarmIds
    }

    /// Ob noch nachgeholt wird: höchstens ``valid`` nach `until`.
    public func isValid(now: Date) -> Bool { now <= until.addingTimeInterval(Self.valid) }
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
    /// Ein wiederholter Wecker lässt Termine bis `skipUntil` (Wandzeit, `wallMillis`) aus und, mit `skipHolidays`,
    /// Termine an `holidays`.
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
            let wall = wallMillis(target, calendar: calendar)
            if !dropped(spec, wall: wall, date: SleepNotes.dateString(day, calendar: calendar), holidays: holidays) { return target }
        }
        return nil
    }

    /// Plan für die nächste Nacht des Weckers (Zielzeit echt nach `now`), oder `nil`, wenn er aus ist.
    public static func nextPlan(_ spec: AlarmSpec, now: Date, calendar: Calendar, holidays: Set<String> = []) -> WakePlan? {
        guard let target = nextTarget(spec, now: now, calendar: calendar, holidays: holidays) else { return nil }
        return plan(spec, target: target)
    }

    /// Plan des Weckers `spec` für die Zielzeit `target`: Messbeginn `measureWindowMinutes` davor; bei «Genau» mit `exact`.
    public static func plan(_ spec: AlarmSpec, target: Date) -> WakePlan {
        WakePlan(alarmId: spec.id, windowStart: windowStart(target, windowMinutes: spec.measureWindowMinutes), target: target,
                 exact: spec.isExact)
    }

    /// Der früheste Plan über alle Wecker, oder `nil`, wenn keiner aktiv ist. Bei gleicher Zielzeit gewinnt die kleinere Kennung, damit beide Plattformen denselben Wecker wählen.
    public static func earliestPlan(_ specs: [AlarmSpec], now: Date, calendar: Calendar, holidays: Set<String> = []) -> WakePlan? {
        earliestPlan(specs, now: now, doneUntil: [:], calendar: calendar, holidays: holidays)
    }

    /// Wie oben, aber pro Wecker erst ab `doneUntil[id]` (erledigte bzw. ausgesetzte Nacht).
    /// Ein zweiter Wecker am selben Morgen bleibt davon unberührt.
    public static func earliestPlan(_ specs: [AlarmSpec], now: Date, doneUntil: [String: Date], calendar: Calendar,
                                    holidays: Set<String> = []) -> WakePlan? {
        specs.compactMap { nextPlan($0, now: searchFrom(now, doneUntil[$0.id]), calendar: calendar, holidays: holidays) }
            .min { ($0.target, $0.alarmId) < ($1.target, $1.alarmId) }
    }

    /// Nachfrist eines schon geplanten Zweitweckers: Liegt `now` in [`plannedTarget`, `plannedTarget` + `grace`) und ist
    /// `plannedTarget` noch ein gültiger Termin von `spec` (eingeschaltet, Zeit unverändert, nicht übersprungen, kein
    /// Feiertag: `nextTarget(spec, plannedTarget − 1 ms) == plannedTarget`) und nicht ausgesetzt (`suppressedUntil` vor
    /// der Zielzeit), bleibt dieser Plan. Sonst `nil`. `nextTarget` sucht nur nach `now`; ohne diese Prüfung fiele die
    /// Sicherung weg, wenn in der Nachfrist neu geplant wird (Kaltstart der App). Mit `grace` 0 nie. Wie Kotlin.
    public static func inGrace(_ spec: AlarmSpec, plannedTarget: Date?, now: Date, grace: TimeInterval, calendar: Calendar,
                               holidays: Set<String> = [], suppressedUntil: Date? = nil) -> WakePlan? {
        guard let target = plannedTarget, target <= now, target.addingTimeInterval(grace) > now else { return nil }
        if let suppressedUntil, suppressedUntil >= target { return nil }
        guard let next = nextTarget(spec, now: target.addingTimeInterval(-0.001), calendar: calendar, holidays: holidays),
              millis(next) == millis(target) else { return nil }
        return plan(spec, target: target)
    }

    /// Begonnene Nacht über einen Zeitzonenwechsel: Hat das Weckfenster der geplanten Zielzeit `plannedTarget` schon
    /// begonnen (`windowStart ≤ now < plannedTarget + grace`), bleibt dieser Plan zur geplanten absoluten Zeit (07:00
    /// Zürich klingelt in Tokio um 15:00), sofern `plannedTarget` im Kalender der Planung `plannedCalendar` noch ein
    /// gültiger Termin von `spec` ist (eingeschaltet, Zeit, Tage und Fenster unverändert, nicht übersprungen, kein
    /// Feiertag: `nextTarget(spec, plannedTarget − 1 ms, plannedCalendar) == plannedTarget`) und nicht ausgesetzt
    /// (`suppressedUntil` vor der Zielzeit). Sonst `nil`, ebenso ohne Zielzeit oder ohne Kalender (alte Daten). Vor dem
    /// Fensterbeginn gilt die neue Ortszeit. Wie Kotlin.
    public static func begunNight(_ spec: AlarmSpec, plannedTarget: Date?, plannedCalendar: Calendar?, now: Date,
                                  grace: TimeInterval, holidays: Set<String> = [], suppressedUntil: Date? = nil) -> WakePlan? {
        guard let target = plannedTarget, let calendar = plannedCalendar else { return nil }
        let begun = plan(spec, target: target)
        guard begun.windowStart <= now, target.addingTimeInterval(grace) > now else { return nil }
        if let suppressedUntil, suppressedUntil >= target { return nil }
        guard let next = nextTarget(spec, now: target.addingTimeInterval(-0.001), calendar: calendar, holidays: holidays),
              millis(next) == millis(target) else { return nil }
        return begun
    }

    /// Nachholen nach einer Nacht der Uhr: Ein Wecker, dessen Zielzeit fällig wurde, während eine andere Nacht lief
    /// (`catchUp`, solange ``CatchUpWindow/isValid(now:)``), und der noch nicht erledigt ist (`doneUntil`): Plan mit
    /// dieser (vergangenen) Zielzeit, damit er gleich klingelt statt erst am nächsten Tag. Nur Wecker aus
    /// `catchUp.alarmIds`. Mehrere: die früheste Zielzeit, bei Gleichstand die kleinere Kennung. Sonst `nil`.
    /// Ohne Nachholen gilt `earliestPlan` mit denselben Werten. Wie Kotlin.
    public static func missedPlan(_ specs: [AlarmSpec], catchUp: CatchUpWindow?, now: Date, doneUntil: [String: Date] = [:],
                                  calendar: Calendar, holidays: Set<String> = []) -> WakePlan? {
        guard let catchUp, catchUp.isValid(now: now) else { return nil }
        return specs.filter { catchUp.alarmIds.contains($0.id) }.compactMap { spec -> WakePlan? in
            let from = max(catchUp.from, doneUntil[spec.id] ?? .distantPast)
            guard let target = nextTarget(spec, now: from, calendar: calendar, holidays: holidays),
                  target <= catchUp.until, target <= now else { return nil }
            return plan(spec, target: target)
        }.min { ($0.target, $0.alarmId) < ($1.target, $1.alarmId) }
    }

    /// Eine Nacht, die geweckt hat, ist zu Ende: Zeitraum von ihrem Fensterbeginn `windowStart` bis `until` samt den
    /// eingeschalteten Weckern aus `specs`, deren Zielzeit hineinfiel. Schliesst eine nachgeholte Nacht direkt an
    /// `previous` an (beginnt nicht nach dessen Ende), wird der Zeitraum verlängert und die Kennungen bleiben (drei
    /// Wecker hintereinander). Wie Kotlin.
    public static func catchUpAfter(_ previous: CatchUpWindow?, windowStart: Date, until: Date, specs: [AlarmSpec],
                                    calendar: Calendar, holidays: Set<String> = []) -> CatchUpWindow {
        let joined = previous.flatMap { windowStart <= $0.until ? $0 : nil }
        let from = joined.map { min($0.from, windowStart) } ?? windowStart
        let to = joined.map { max($0.until, until) } ?? until
        let due = specs.filter { spec in
            nextTarget(spec, now: from, calendar: calendar, holidays: holidays).map { $0 <= to } ?? false
        }.map(\.id)
        return CatchUpWindow(from: from, until: to, alarmIds: (joined?.alarmIds ?? []).union(due))
    }

    /// «Nächstes Mal überspringen»: `skipUntil` = Wandzeit (`wallMillis`) der nächsten Zielzeit nach allen Regeln
    /// (auch Feiertagen), gesucht ab dem späteren von `now` und `doneUntil`. So trifft es nach frühem Wecken (Nacht
    /// erledigt, Zielzeit noch vorn) den nächsten Termin, nicht den schon erledigten heutigen.
    /// Einmal-Wecker, ausgeschaltete Wecker oder ohne Termin: unverändert.
    public static func skipNext(_ spec: AlarmSpec, now: Date, doneUntil: Date? = nil, calendar: Calendar,
                                holidays: Set<String> = []) -> AlarmSpec {
        guard !spec.isOneShot,
              let target = nextTarget(spec, now: searchFrom(now, doneUntil), calendar: calendar, holidays: holidays) else { return spec }
        return spec.with(skipUntil: wallMillis(target, calendar: calendar))
    }

    /// Datum «yyyy-MM-dd» (im Kalender) des Termins, den `skipNext` mit denselben Werten überspringen würde; für den
    /// Menüpunkt «Do 30.10. überspringen». `nil` bei Einmal-Weckern, ausgeschalteten Weckern oder ohne Termin.
    public static func nextSkipDate(_ spec: AlarmSpec, now: Date, doneUntil: Date? = nil, calendar: Calendar,
                                    holidays: Set<String> = []) -> String? {
        guard !spec.isOneShot,
              let target = nextTarget(spec, now: searchFrom(now, doneUntil), calendar: calendar, holidays: holidays) else { return nil }
        return SleepNotes.dateString(target, calendar: calendar)
    }

    /// `skipUntil` nach dem Bearbeiten von `old` zu `edited`: bleibt, wenn Stunde, Minute und Tage gleich sind; sonst 0
    /// (geänderte Zeit oder Tage heben das Überspringen auf, docs/ENTSCHEIDE.md). Schaltet der Nutzer im selben
    /// Bearbeiten «Nächstes Mal überspringen» bewusst ein, ruft der Editor danach `skipNext` auf den neuen Stand auf.
    public static func keepSkip(old: AlarmSpec, edited: AlarmSpec) -> Int64 {
        old.hour == edited.hour && old.minute == edited.minute && old.days == edited.days ? old.skipUntil : 0
    }

    /// `skipUntil` nach einem Editor mit Schalter «Nächstes Mal überspringen» (Uhr). `requested` ist die ausdrückliche
    /// Wahl im Editor, nicht aus Werten erraten:
    /// - `nil` = Schalter nicht angefasst → `keepSkip` (geänderte Zeit oder Tage heben das Überspringen auf).
    /// - `true` = bewusst eingeschaltet → `skipNext` auf den neuen Stand `edited` (nächster Termin des neuen Zeitplans,
    ///   ab `doneUntil`); Einmal-Wecker oder ohne Termin → 0.
    /// - `false` = ausgeschaltet → 0.
    ///
    /// `edited.skipUntil` wird nicht gelesen.
    public static func editedSkip(old: AlarmSpec, edited: AlarmSpec, requested: Bool?, now: Date, doneUntil: Date? = nil,
                                  calendar: Calendar, holidays: Set<String> = []) -> Int64 {
        switch requested {
        case nil: keepSkip(old: old, edited: edited)
        case true?: skipNext(undoSkip(edited), now: now, doneUntil: doneUntil, calendar: calendar, holidays: holidays).skipUntil
        case false?: 0
        }
    }

    /// Überspringen rückgängig: `skipUntil` = 0.
    public static func undoSkip(_ spec: AlarmSpec) -> AlarmSpec { spec.with(skipUntil: 0) }

    /// Datum «yyyy-MM-dd» des übersprungenen Termins, solange er bevorsteht (`skipUntil` nach der Wandzeit von `now`);
    /// sonst `nil`. Das Datum hängt nicht von der Zeitzone ab (Wandzeit).
    public static func skippedDate(_ spec: AlarmSpec, now: Date, calendar: Calendar) -> String? {
        guard !spec.isOneShot, spec.skipUntil > wallMillis(now, calendar: calendar) else { return nil }
        return SleepNotes.dateString(Date(timeIntervalSince1970: TimeInterval(spec.skipUntil) / 1000), calendar: utcCalendar)
    }

    /// Datum des nächsten Termins ohne Feiertagsregel (aber mit `skipUntil`), wenn es ein Feiertag ist und
    /// `skipHolidays` gilt; sonst `nil`. Für «Do 30.10.: Feiertag – nicht geweckt».
    public static func skippedHoliday(_ spec: AlarmSpec, now: Date, calendar: Calendar, holidays: Set<String>) -> String? {
        guard spec.skipHolidays, !spec.isOneShot, !holidays.isEmpty,
              let target = nextTarget(spec.with(skipHolidays: false), now: now, calendar: calendar) else { return nil }
        let date = SleepNotes.dateString(target, calendar: calendar)
        return holidays.contains(date) ? date : nil
    }

    /// Hinweis «Do 30.10.: Feiertag – nicht geweckt» der Startseite: der früheste `skippedHoliday` über alle Wecker (je
    /// ab dem späteren von `now` und `doneUntil`), aber nur, wenn er nicht nach dem Datum der Zielzeit des nächsten
    /// Plans (`earliestPlan`) liegt – ein Feiertag hinter dem nächsten Wecken ist noch kein Hinweis. Ohne Plan zählt
    /// jeder. Sonst `nil`.
    public static func holidayNote(_ specs: [AlarmSpec], now: Date, doneUntil: [String: Date] = [:], calendar: Calendar,
                                   holidays: Set<String>) -> String? {
        guard let day = specs.compactMap({
            skippedHoliday($0, now: searchFrom(now, doneUntil[$0.id]), calendar: calendar, holidays: holidays)
        }).min() else { return nil }
        guard let plan = earliestPlan(specs, now: now, doneUntil: doneUntil, calendar: calendar, holidays: holidays) else { return day }
        return day <= SleepNotes.dateString(plan.target, calendar: calendar) ? day : nil
    }

    /// Wandzeit von `at` im Kalender in ms: Ortsdatum und -uhrzeit, gezählt, als wäre es UTC (2026-10-29 07:00 in
    /// Zürich und in New York ergibt dieselbe Zahl). So speichert `AlarmSpec.skipUntil` den übersprungenen Termin:
    /// Auf Reisen fällt derselbe Kalendertag aus, nicht derselbe absolute Zeitpunkt. Gerechnet als Zeitpunkt plus
    /// Abstand der Zeitzone zu UTC in diesem Moment, abgeschnitten auf ganze ms – wie `LocalDateTime` und
    /// `toEpochMilli` in Kotlin, auch bei anderem Kalender des Geräts.
    public static func wallMillis(_ at: Date, calendar: Calendar) -> Int64 {
        let offset = TimeInterval(calendar.timeZone.secondsFromGMT(for: at))
        return Int64(((at.timeIntervalSince1970 + offset) * 1000).rounded(.down))
    }

    /// Gregorianischer Kalender in UTC, für Wandzeiten.
    private static let utcCalendar: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .gmt
        return calendar
    }()

    /// Ab wann gesucht wird: `now`, oder `doneUntil`, wenn das später ist (eine erledigte Nacht zählt nicht).
    private static func searchFrom(_ now: Date, _ doneUntil: Date?) -> Date {
        max(now, doneUntil ?? .distantPast)
    }

    /// Ob bei diesem Wecker überhaupt Termine ausfallen können (nur wiederholte Wecker).
    private static func mayDrop(_ spec: AlarmSpec, holidays: Set<String>) -> Bool {
        !spec.isOneShot && (spec.skipUntil > 0 || (spec.skipHolidays && !holidays.isEmpty))
    }

    /// Ob der Termin mit Wandzeit `wall` (`wallMillis`) am Datum `date` («yyyy-MM-dd») ausfällt: übersprungen oder Feiertag.
    private static func dropped(_ spec: AlarmSpec, wall: Int64, date: String, holidays: Set<String>) -> Bool {
        guard !spec.isOneShot else { return false }
        if wall <= spec.skipUntil { return true }
        return spec.skipHolidays && holidays.contains(date)
    }

    /// Millisekunden seit 1970, gerundet wie im Textformat.
    static func millis(_ date: Date) -> Int64 { TimeBounds.millis(date) }

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
