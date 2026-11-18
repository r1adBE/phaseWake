import PhaseWakeCore
import SwiftUI

// Liste im Verlauf nach Woche und Jahr: die neusten Nächte einzeln, ältere zusammen in einer Karte mit aufklappbaren
// Zeilen; aufgeklappt folgen eingerückt kompakte Zeilen der Nächte bzw. die Wochen des Jahres. Wie
// `HistoryGroupRows.kt` (Android).

/// Einrücken je Stufe (Woche im Jahr, Nacht in der Woche).
private let groupStep: CGFloat = 16

/// Die Liste aus `groups`: einzelne Einträge über `row`; alle Wochen und Jahre zusammen in einer Karte, aufgeklappt
/// mit den Einträgen der Woche über `compact` bzw. den Wochen des Jahres. `open` hält die aufgeklappten Zeilen
/// (``weekKey`` und ``yearKey``), `summary` rechnet den Schnitt, `today` das Jahr, das eine Wochenzeile nicht nennen
/// muss.
struct HistoryGroupList<T: Identifiable, Row: View, Compact: View>: View where T.ID == String {
    let groups: [HistoryGroup<T>]
    @Binding var open: Set<String>
    let summary: ([T]) -> HistoryGroupSummary
    let today: String
    @ViewBuilder let row: (T) -> Row
    @ViewBuilder let compact: (T) -> Compact

    /// Eine Zeile der Karte: Wochenzeile (in einer Jahreszeile: mit deren Jahr), Jahreszeile oder Eintrag einer
    /// aufgeklappten Woche; `depth` = Stufe des Einrückens.
    private enum Line: Identifiable {
        case week(HistoryWeek<T>, year: Int?)
        case year(Int, [T])
        case entry(T, depth: Int)

        var id: String {
            switch self {
            case .week(let week, let year): weekKey(week.start, year: year)
            case .year(let year, _): yearKey(year)
            case .entry(let entry, _): entry.id
            }
        }

        var depth: Int {
            switch self {
            case .week(_, let year): year == nil ? 0 : 1
            case .year: 0
            case .entry(_, let depth): depth
            }
        }
    }

    var body: some View {
        ForEach(singles) { row($0) }
        let lines = self.lines
        if !lines.isEmpty {
            VStack(alignment: .leading, spacing: 0) {
                ForEach(Array(lines.enumerated()), id: \.element.id) { index, line in
                    if index > 0 { RowLine(indent: CGFloat(line.depth) * groupStep) }
                    view(line)
                }
            }
            .card(inset: 0)
        }
    }

    @ViewBuilder private func view(_ line: Line) -> some View {
        let indent = CGFloat(line.depth) * groupStep
        switch line {
        case .week(let week, let year):
            // In einer Jahreszeile nennt die Woche kein Jahr; sonst nur, wenn es nicht das laufende ist.
            let thisYear = String(today.prefix(4))
            let withYear = year == nil && (!week.start.hasPrefix(thisYear) || !week.end.hasPrefix(thisYear))
            HistoryGroupRow(title: weekRange(week, withYear: withYear), summary: summary(week.entries),
                            open: open.contains(line.id), indent: indent) { toggle(line.id) }
        case .year(let year, let entries):
            HistoryGroupRow(title: year.formatted(.number.grouping(.never)), summary: summary(entries),
                            open: open.contains(line.id), indent: indent) { toggle(line.id) }
        case .entry(let entry, _):
            compact(entry).padding(.leading, indent)
        }
    }

    /// Die neusten Einträge, einzeln über der Karte.
    private var singles: [T] {
        groups.compactMap { group in
            if case .single(let entry) = group { return entry }
            return nil
        }
    }

    /// Zeilen der Karte in Listenfolge; zugeklappte Wochen und Jahre ohne ihren Inhalt.
    private var lines: [Line] {
        var out: [Line] = []
        func addWeek(_ week: HistoryWeek<T>, year: Int?) {
            out.append(.week(week, year: year))
            if open.contains(weekKey(week.start, year: year)) {
                out += week.entries.map { .entry($0, depth: year == nil ? 1 : 2) }
            }
        }
        for group in groups {
            switch group {
            case .single:
                continue
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

/// Feine Linie zwischen zwei Zeilen der Karte, ab `indent` (bündig mit dem Text der Zeile darunter).
private struct RowLine: View {
    let indent: CGFloat

    var body: some View {
        Divider().padding(.leading, 16 + indent)
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

/// «5 Nächte · Ø 7 Std. 5 Min Schlaf · Befinden: 3,8/5»; Schlaf und Befinden nur mit Daten. Ein «Ø» für die Zeile,
/// das Befinden als Wert von 5 mit dem Dezimalzeichen der Sprache (``decimalText(_:signed:locale:)``). Wie
/// `groupSummaryText` (Android).
func historyGroupSummaryText(_ summary: HistoryGroupSummary) -> String {
    var parts: [String] = []
    if summary.nights > 0 { parts.append(LP("history_group_nights", summary.nights, Int64(summary.nights))) }
    if let asleep = summary.asleepMinutes { parts.append(L("trends_asleep", Format.duration(minutes: asleep))) }
    if let feeling = summary.feeling {
        parts.append(L("feeling_value", decimalText(feeling) + "/" + 5.formatted()))
    }
    return parts.joined(separator: " · ")
}

/// Aufklappbare Zeile einer Woche oder eines Jahres, um `indent` eingerückt: Titel, darunter der Schnitt; der Pfeil
/// dreht sich beim Aufklappen. Für VoiceOver ein Knopf mit Zustand («Aufgeklappt» / «Zugeklappt»).
private struct HistoryGroupRow: View {
    let title: String
    let summary: HistoryGroupSummary
    let open: Bool
    let indent: CGFloat
    let onToggle: () -> Void

    var body: some View {
        let text = historyGroupSummaryText(summary)
        Button(action: onToggle) {
            HStack(spacing: 8) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(Fonts.manrope(15, .semibold, relativeTo: .headline))
                    if !text.isEmpty {
                        Text(text).font(Fonts.manrope(12, relativeTo: .caption)).foregroundStyle(.secondary)
                    }
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.down")
                    .foregroundStyle(.tint)
                    .rotationEffect(.degrees(open ? 180 : 0))
                    .accessibilityHidden(true)
            }
            .padding(.leading, 16 + indent)
            .padding(.trailing, 16)
            .padding(.vertical, 12)
            .frame(minHeight: 56)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityValue(L(open ? "history_group_expanded" : "history_group_collapsed"))
    }
}
