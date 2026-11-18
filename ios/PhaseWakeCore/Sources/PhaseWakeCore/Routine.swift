import Foundation

/// Ein Punkt der Abendroutine. Vorgeschlagene Punkte haben einen leeren `text` (Anzeige übersetzt).
public struct RoutineItem: Equatable, Sendable, Identifiable {
    /// Kennung: `PHONE`, `LIGHT`, `WATER` oder «custom:» plus UUID.
    public let id: String
    /// Eigener Text; leer bei den Vorschlägen.
    public let text: String

    /// Erzeugt einen Punkt; Vorschläge ohne Text.
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
    /// Vorschlag «Handy weg».
    public static let phone = "PHONE"
    /// Vorschlag «Licht dimmen».
    public static let light = "LIGHT"
    /// Vorschlag «Wasser bereit».
    public static let water = "WATER"

    /// Die drei Vorschläge für den Anfang.
    public static let defaults: [RoutineItem] = [RoutineItem(id: phone), RoutineItem(id: light), RoutineItem(id: water)]

    /// Höchstzahl Punkte; mehr wäre keine Routine mehr.
    public static let maxItems = 8
    /// Höchstlänge eines eigenen Texts in Zeichen; passt auf eine Zeile.
    public static let maxTextLength = 40
    /// Vorsatz der Kennung eigener Punkte.
    public static let customPrefix = "custom:"
    /// Durchschnitt erst ab so vielen Abenden …
    public static let minEvenings = 3
    /// … über so viele Nachtdaten bis heute.
    public static let averageNights = 30
    /// Abgehakt bleibt so viele Nachtdaten (wie die Notizen).
    public static let maxDates = NightHistory.maxDates

    /// Ob die Kennung einer der drei Vorschläge ist.
    public static func isBuiltIn(_ id: String) -> Bool { id == phone || id == light || id == water }

    /// Kennung für einen eigenen Punkt aus einer UUID.
    public static func customId(_ uuid: String) -> String { customPrefix + uuid }

    /// Text ohne Zeilenumbrüche und «|» (Trennzeichen des Formats), ohne Rand-Leerzeichen, höchstens 40 Zeichen.
    public static func cleanText(_ text: String) -> String {
        let flat = text.replacingOccurrences(of: "\n", with: " ").replacingOccurrences(of: "\r", with: " ")
            .replacingOccurrences(of: "|", with: " ")
        return String(flat.trimmingCharacters(in: .whitespaces).prefix(maxTextLength))
            .trimmingCharacters(in: .whitespaces)
    }

    /// Schreibt die Punkte je Zeile «id|Text», höchstens 8.
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

    /// Behält die jüngsten ``maxDates`` Nachtdaten mit Haken.
    public static func trim(_ ticks: [String: [String]]) -> [String: [String]] {
        let kept = ticks.filter { !$0.value.isEmpty }.keys.sorted().suffix(maxDates)
        return Dictionary(uniqueKeysWithValues: kept.map { ($0, ticks[$0] ?? []) })
    }

    /// Je Nachtdatum eine Zeile «yyyy-MM-dd|id,id», nach Datum sortiert.
    public static func encodeTicks(_ ticks: [String: [String]]) -> String {
        trim(ticks).sorted { $0.key < $1.key }
            .map { "\($0.key)|\(unique($0.value).joined(separator: ","))" }
            .joined(separator: "\n")
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
