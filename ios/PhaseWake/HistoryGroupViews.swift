import PhaseWakeCore
import SwiftUI

// Liste im Verlauf nach Woche und Jahr: die neusten Nächte einzeln, ältere in aufklappbaren Zeilen. Wie
// `HistoryGroupRows.kt` (Android).

/// Die Liste aus `groups`: einzelne Einträge über `row`, Wochen und Jahre als aufklappbare Zeilen; aufgeklappt folgen
/// darunter die Einträge der Woche bzw. die Wochen des Jahres. `open` hält die aufgeklappten Zeilen (``weekKey`` und
/// ``yearKey``), `summary` rechnet den Schnitt, `today` das Jahr, das eine Wochenzeile nicht nennen muss.
struct HistoryGroupList<T: Identifiable, Row: View>: View where T.ID == String {
    let groups: [HistoryGroup<T>]
    @Binding var open: Set<String>
    let summary: ([T]) -> HistoryGroupSummary
    let today: String
    @ViewBuilder let row: (T) -> Row

    /// Eine Zeile der Liste: Eintrag, Wochenzeile (in einer Jahreszeile: mit deren Jahr) oder Jahreszeile.
    private enum Line: Identifiable {
        case entry(T)
        case week(HistoryWeek<T>, year: Int?)
        case year(Int, [T])

        var id: String {
            switch self {
            case .entry(let entry): entry.id
            case .week(let week, let year): weekKey(week.start, year: year)
            case .year(let year, _): yearKey(year)
            }
        }
    }

    var body: some View {
        ForEach(lines) { line in
            switch line {
            case .entry(let entry):
                row(entry)
            case .week(let week, let year):
                // In einer Jahreszeile nennt die Woche kein Jahr; sonst nur, wenn es nicht das laufende ist.
                let thisYear = String(today.prefix(4))
                let withYear = year == nil && (!week.start.hasPrefix(thisYear) || !week.end.hasPrefix(thisYear))
                HistoryGroupRow(title: weekRange(week, withYear: withYear), summary: summary(week.entries),
                                open: open.contains(line.id)) { toggle(line.id) }
                    .padding(.leading, year == nil ? 0 : 16)
            case .year(let year, let entries):
                HistoryGroupRow(title: year.formatted(.number.grouping(.never)), summary: summary(entries),
                                open: open.contains(line.id)) { toggle(line.id) }
            }
        }
    }

    /// Alle Zeilen in Listenfolge; zugeklappte Wochen und Jahre ohne ihren Inhalt.
    private var lines: [Line] {
        var out: [Line] = []
        func addWeek(_ week: HistoryWeek<T>, year: Int?) {
            out.append(.week(week, year: year))
            if open.contains(weekKey(week.start, year: year)) { out += week.entries.map { .entry($0) } }
        }
        for group in groups {
            switch group {
            case .single(let entry):
                out.append(.entry(entry))
            case .week(let week):
                addWeek(week, year: nil)
            case .year(let year, let weeks):
                out.append(.year(year, group.entries))
                if open.contains(yearKey(year)) { weeks.forEach { addWeek($0, year: year) } }
            }
        }
        return out
    }

    private func toggle(_ key: String) {
        withAnimation {
            if open.contains(key) { open.remove(key) } else { open.insert(key) }
        }
    }
}

/// Schlüssel einer Wochenzeile; `year` = Jahreszeile, in der sie steht (`nil`: in den Wochen des letzten Jahres).
func weekKey(_ start: String, year: Int?) -> String { (year.map { yearKey($0) + "/" } ?? "") + "week@\(start)" }

/// Schlüssel einer Jahreszeile.
func yearKey(_ year: Int) -> String { "year@\(year)" }

/// «9. Nov. – 15. Nov.» bzw. mit Jahr «29. Dez. 2025 – 4. Jan. 2026» (Datum des Morgens, Sprache des Geräts).
private func weekRange<T>(_ week: HistoryWeek<T>, withYear: Bool) -> String {
    func text(_ day: String) -> String {
        // `morning` rechnet vom Nachtdatum auf den Morgen; die Woche zählt schon in Morgen.
        let date = morning(NightDate.adding(-1, to: day))
        return withYear ? date.formatted(.dateTime.day().month(.abbreviated).year())
            : date.formatted(.dateTime.day().month(.abbreviated))
    }
    return text(week.start) + " – " + text(week.end)
}

/// «5 Nächte · Ø 7 Std. 5 Min Schlaf · Ø Befinden 3,8 von 5»; Schlaf und Befinden nur mit Daten. Wie
/// `groupSummaryText` (Android).
func historyGroupSummaryText(_ summary: HistoryGroupSummary) -> String {
    var parts: [String] = []
    if summary.nights > 0 { parts.append(LP("history_group_nights", summary.nights, Int64(summary.nights))) }
    if let asleep = summary.asleepMinutes { parts.append(L("trends_asleep", Format.duration(minutes: asleep))) }
    if let feeling = summary.feeling {
        parts.append(L("trends_feeling", feeling.formatted(.number.precision(.fractionLength(1)))))
    }
    return parts.joined(separator: " · ")
}

/// Aufklappbare Zeile einer Woche oder eines Jahres: Titel, darunter der Schnitt; der Pfeil dreht sich beim
/// Aufklappen. Für VoiceOver ein Knopf mit Zustand («Aufgeklappt» / «Zugeklappt»).
private struct HistoryGroupRow: View {
    let title: String
    let summary: HistoryGroupSummary
    let open: Bool
    let onToggle: () -> Void

    var body: some View {
        let text = historyGroupSummaryText(summary)
        Button(action: onToggle) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text(title).font(Fonts.manrope(15, .semibold, relativeTo: .headline))
                    if !text.isEmpty {
                        Text(text).font(Fonts.manrope(12, relativeTo: .caption)).foregroundStyle(.secondary)
                    }
                }
                Spacer()
                Image(systemName: "chevron.down")
                    .foregroundStyle(.tint)
                    .rotationEffect(.degrees(open ? 180 : 0))
                    .accessibilityHidden(true)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityValue(L(open ? "history_group_expanded" : "history_group_collapsed"))
        .card()
    }
}
