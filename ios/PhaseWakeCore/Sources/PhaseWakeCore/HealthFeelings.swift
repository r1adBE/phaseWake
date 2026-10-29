import Foundation

/// Befinden einer Nacht aus einer anderen App (z. B. Apple Health), wie es im Verlauf steht: Nachtdatum, Zeitpunkt zum
/// Einordnen in Wochen (Ende der Sitzung, wie der Weckmoment bei eigenen Nächten) und Wert 1–5.
/// Identisch zu `HealthFeeling` (Kotlin).
public struct HealthFeeling: Equatable, Sendable {
    /// Nachtdatum «yyyy-MM-dd» (``HistoryItem/date``).
    public let date: String
    /// Ende der Sitzung.
    public let at: Date
    /// Befinden (`NightRecord.feelingRange`).
    public let feeling: Int

    /// Erzeugt einen Eintrag.
    public init(date: String, at: Date, feeling: Int) {
        self.date = date
        self.at = at
        self.feeling = feeling
    }
}

/// Befinden zu Nächten aus anderen Apps: nur bei Phase Wake gespeichert, je Nachtdatum, nie in HealthKit bzw.
/// Health Connect geschrieben. Gesichert wie die Notizen und mit «Verlauf löschen» gelöscht. Eigene Nächte speichern
/// ihr Befinden weiter in `NightRecord.feeling`. Identisch zu `HealthFeelings` (Kotlin).
///
/// Speicherformat: je Nachtdatum eine Zeile «yyyy-MM-dd|n», nach Datum sortiert.
public enum HealthFeelings {
    /// Höchstens so viele Nachtdaten bleiben gespeichert (wie der Verlauf).
    public static let maxDates = NightHistory.maxNights

    /// Setzt das Befinden `feeling` für `date`; ein Wert ausserhalb 1–5 oder ein ungültiges Datum ändert nichts.
    /// Höchstens ``maxDates`` bleiben.
    public static func set(_ feelings: [String: Int], date: String, feeling: Int) -> [String: Int] {
        guard valid(feeling), NightDate.isValid(date) else { return trim(feelings) }
        var out = feelings
        out[date] = feeling
        return trim(out)
    }

    /// Behält die jüngsten ``maxDates`` Nachtdaten mit gültigem Wert.
    public static func trim(_ feelings: [String: Int]) -> [String: Int] {
        let kept = feelings.filter { valid($0.value) && NightDate.isValid($0.key) }.keys.sorted().suffix(maxDates)
        return Dictionary(uniqueKeysWithValues: kept.map { ($0, feelings[$0] ?? 0) })
    }

    /// Je Nachtdatum eine Zeile «yyyy-MM-dd|n», nach Datum sortiert.
    public static func encode(_ feelings: [String: Int]) -> String {
        trim(feelings).sorted { $0.key < $1.key }.map { "\($0.key)|\($0.value)" }.joined(separator: "\n")
    }

    /// Liest `encode`; unlesbare Zeilen und Werte ausserhalb 1–5 werden übersprungen.
    public static func decode(_ text: String) -> [String: Int] {
        var out: [String: Int] = [:]
        for line in text.split(whereSeparator: \.isNewline) {
            let parts = line.split(separator: "|", maxSplits: 1, omittingEmptySubsequences: false)
            guard parts.count == 2, NightDate.isValid(String(parts[0])),
                  let feeling = Int(parts[1].trimmingCharacters(in: .whitespaces)), valid(feeling) else { continue }
            out[String(parts[0])] = feeling
        }
        return trim(out)
    }

    /// Befinden der Nächte aus anderen Apps, die im Verlauf stehen (``HistoryKind/health`` aus
    /// ``HealthHistory/merge(nights:sessions:clearedAt:calendar:)``) und eines haben. Ist die Nacht in der Quelle weg
    /// (oder ausgeblendet), zählt ihr Befinden nicht mehr mit.
    public static func of(_ items: [HistoryItem], feelings: [String: Int]) -> [HealthFeeling] {
        items.compactMap { item in
            guard item.kind == .health, let session = item.session, let feeling = feelings[item.date] else { return nil }
            return HealthFeeling(date: item.date, at: session.end, feeling: feeling)
        }
    }

    /// Befinden je Nachtdatum für Diagramme, Wochenrückblick und Notiz-Wirkung: eigene Nächte wie bisher (die zuletzt
    /// geweckte mit Befinden), dazu `health` für Nachtdaten ohne eigenes Befinden.
    public static func byDate(_ nights: [NightRecord], health: [HealthFeeling], calendar: Calendar) -> [String: Int] {
        var latest: [String: NightRecord] = [:]
        for night in nights where night.feeling != nil {
            let date = SleepNotes.nightDate(of: night, calendar: calendar)
            if let current = latest[date], current.wokeAt >= night.wokeAt { continue }
            latest[date] = night
        }
        var out: [String: Int] = [:]
        for entry in health where out[entry.date] == nil { out[entry.date] = entry.feeling }
        for (date, night) in latest { out[date] = night.feeling }
        return out
    }

    private static func valid(_ feeling: Int) -> Bool { NightRecord.feelingRange.contains(feeling) }
}
