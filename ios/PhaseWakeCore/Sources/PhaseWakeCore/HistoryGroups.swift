import Foundation

/// Woche im Verlauf ab `start` (Wochenbeginn nach der Sprache des Geräts, «yyyy-MM-dd») bis ``end``; `entries`
/// neueste zuerst. Reicht die Woche über die Grenze der einzelnen Nächte oder des Jahres, enthält sie nur ihre
/// Einträge dieser Seite. Identisch zu `HistoryGroup.Week` (Kotlin).
public struct HistoryWeek<T> {
    /// Erster Tag der Woche («yyyy-MM-dd»).
    public let start: String
    /// Einträge dieser Woche, neueste zuerst.
    public let entries: [T]

    /// Woche ab `start` mit `entries`.
    public init(start: String, entries: [T]) {
        self.start = start
        self.entries = entries
    }

    /// Letzter Tag der Woche (eingeschlossen).
    public var end: String { NightDate.adding(6, to: start) }
}

/// Gruppe der Liste im Verlauf (``HistoryGroups/of(_:dayOf:isNight:today:firstDay:)``): ein einzelner Eintrag, eine
/// Woche oder ein Kalenderjahr mit seinen Wochen (neueste zuerst). Jeder Eintrag steht in genau einer Gruppe.
/// Identisch zu `HistoryGroup` (Kotlin).
public enum HistoryGroup<T> {
    case single(T)
    case week(HistoryWeek<T>)
    case year(Int, [HistoryWeek<T>])

    /// Alle Einträge der Gruppe, neueste zuerst.
    public var entries: [T] {
        switch self {
        case .single(let entry): [entry]
        case .week(let week): week.entries
        case .year(_, let weeks): weeks.flatMap(\.entries)
        }
    }
}

extension HistoryWeek: Equatable where T: Equatable {}
extension HistoryGroup: Equatable where T: Equatable {}
extension HistoryWeek: Sendable where T: Sendable {}
extension HistoryGroup: Sendable where T: Sendable {}

/// Kennzahlen einer Woche oder eines Jahres im Verlauf (``HistoryGroups/summary(_:nightDate:asleep:feeling:)``).
/// Identisch zu `HistoryGroupSummary` (Kotlin).
public struct HistoryGroupSummary: Equatable, Sendable {
    /// Nächte (ohne Aufzeichnungen und Geräusche ohne Nacht).
    public let nights: Int
    /// Durchschnittlicher Schlaf je Nachtdatum mit Daten (ganze Minuten, abgerundet); `nil` ohne Daten.
    public let asleepMinutes: Int?
    /// Durchschnittliches Befinden 1–5 der Nächte mit Befinden; `nil` ohne.
    public let feeling: Double?

    /// Kennzahlen aus den einzelnen Werten.
    public init(nights: Int, asleepMinutes: Int?, feeling: Double?) {
        self.nights = nights
        self.asleepMinutes = asleepMinutes
        self.feeling = feeling
    }
}

/// Gruppen der Liste im Verlauf: reine Rechnung, identisch zu `HistoryGroups` (Kotlin).
///
/// Die ``recentNights`` neusten Nächte stehen einzeln oben (egal wie alt), samt allem, was in der Liste vor der
/// fünften Nacht steht (Aufzeichnungen, Geräusche ohne Nacht). Alles danach nach dem Tag des Eintrags (Datum des
/// Morgens, wie auf der Karte): bis ein Jahr vor heute (``yearCutoff(_:)``, eingeschlossen) je Woche, älter je
/// Kalenderjahr und darin je Woche. Eine Woche über eine dieser Grenzen wird geteilt; jede Seite zeigt nur ihre
/// Einträge.
public enum HistoryGroups {
    /// So viele neuste Nächte stehen immer einzeln oben.
    public static let recentNights = 5

    /// Gruppen aus `entries` (neueste zuerst); `dayOf` = Tag des Eintrags («yyyy-MM-dd»), `isNight` = zählt als
    /// Nacht. Wochen beginnen mit `firstDay`.
    public static func of<T>(_ entries: [T], dayOf: (T) -> String, isNight: (T) -> Bool, today: String,
                             firstDay: Weekday) -> [HistoryGroup<T>] {
        var nights = 0
        var recentCount = 0
        for entry in entries {
            guard nights < recentNights else { break }
            if isNight(entry) { nights += 1 }
            recentCount += 1
        }
        let rest = entries.dropFirst(recentCount)
        let cutoff = yearCutoff(today)
        let newer = rest.filter { dayOf($0) >= cutoff }
        let older = rest.filter { dayOf($0) < cutoff }
        let byYear = Dictionary(grouping: older) { year(of: dayOf($0)) }
        let years = byYear.keys.sorted(by: >).map { key in
            HistoryGroup<T>.year(key, weeks(byYear[key] ?? [], dayOf: dayOf, firstDay: firstDay))
        }
        return entries.prefix(recentCount).map { HistoryGroup.single($0) }
            + weeks(Array(newer), dayOf: dayOf, firstDay: firstDay).map { HistoryGroup.week($0) }
            + years
    }

    /// Ältester Tag, der noch in die Wochen gehört: heute vor einem Jahr (29. Februar → 28. Februar), wie
    /// `LocalDate.minusYears(1)` (Kotlin).
    public static func yearCutoff(_ today: String) -> String {
        let parts = today.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return today }
        let day = parts[1] == 2 && parts[2] == 29 ? 28 : parts[2]
        return pad(parts[0] - 1, 4) + "-" + pad(parts[1], 2) + "-" + pad(day, 2)
    }

    /// Erster Tag der Woche von `day`, die mit `firstDay` beginnt.
    public static func weekStart(_ day: String, firstDay: Weekday) -> String {
        let weekday = NightDate.weekday(day) ?? firstDay
        return NightDate.adding(-((weekday.rawValue - firstDay.rawValue + 7) % 7), to: day)
    }

    /// Kennzahlen von `entries`: `nightDate` = Nachtdatum einer Nacht, `nil` für alles andere; Schlaf aus `asleep`
    /// je Nachtdatum (zwei Nächte am selben Datum zählen einmal), Befinden je Nacht aus `feeling`.
    public static func summary<T>(_ entries: [T], nightDate: (T) -> String?, asleep: [String: Int],
                                  feeling: (T) -> Int?) -> HistoryGroupSummary {
        let nights = entries.filter { nightDate($0) != nil }
        var seen = Set<String>()
        let dates = nights.compactMap(nightDate).filter { seen.insert($0).inserted }
        let minutes = dates.compactMap { asleep[$0] }
        let feelings = nights.compactMap(feeling)
        return HistoryGroupSummary(
            nights: nights.count,
            asleepMinutes: minutes.isEmpty ? nil : minutes.reduce(0, +) / minutes.count,
            feeling: feelings.isEmpty ? nil : Double(feelings.reduce(0, +)) / Double(feelings.count)
        )
    }

    /// Wochen aus `entries`, neueste zuerst; innerhalb einer Woche die Folge von `entries`.
    private static func weeks<T>(_ entries: [T], dayOf: (T) -> String, firstDay: Weekday) -> [HistoryWeek<T>] {
        let byWeek = Dictionary(grouping: entries) { weekStart(dayOf($0), firstDay: firstDay) }
        return byWeek.keys.sorted(by: >).map { HistoryWeek(start: $0, entries: byWeek[$0] ?? []) }
    }

    private static func year(of day: String) -> Int { Int(day.prefix(4)) ?? 0 }

    private static func pad(_ value: Int, _ width: Int) -> String {
        let text = String(value)
        return String(repeating: "0", count: max(0, width - text.count)) + text
    }
}
