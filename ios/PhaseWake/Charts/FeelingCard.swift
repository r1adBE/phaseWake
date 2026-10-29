import Charts
import PhaseWakeCore
import SwiftUI

// MARK: 5. Befinden

struct FeelingCard: View {
    @Environment(PhoneModel.self) private var model
    @Environment(\.colorScheme) private var scheme
    let series: FeelingSeries
    /// Öffnet die Detailseite zum Nachtdatum.
    let onOpen: (String) -> Void
    @State private var selected: Double?

    private var takeaway: String? {
        let last = series.lastWeek.map { L("trends_feeling_change", decimalText($0)) }
        if let this = series.thisWeek { return L("trends_feeling", decimalText(this)) + (last.map { " · " + $0 } ?? "") }
        return last
    }

    private func names(_ point: FeelingPoint) -> [String] { point.notes.compactMap { model.noteName($0) } }

    /// «28. Okt. · Befinden: Gut · Notizen: Koffein, Sport»
    private func line(_ point: FeelingPoint) -> String {
        var parts = [longDate(point.date)]
        if let feeling = point.feeling { parts.append(L("feeling_value", feelingName(feeling))) }
        let notes = names(point)
        if !notes.isEmpty { parts.append(L("notes_value", notes.joined(separator: ", "))) }
        return parts.joined(separator: " · ")
    }

    /// Linie nur zwischen benachbarten Nächten mit Angabe: jede Lücke beginnt einen neuen Abschnitt.
    private var segments: [(index: Int, value: Int, segment: Int)] {
        var out: [(Int, Int, Int)] = []
        var segment = 0
        for (i, p) in series.points.enumerated() {
            guard let v = p.feeling else { segment += 1; continue }
            out.append((i, v, segment))
        }
        return out.map { (index: $0.0, value: $0.1, segment: $0.2) }
    }

    private var selectedIndex: Int? {
        guard let selected, !series.points.isEmpty else { return nil }
        let i = min(series.points.count - 1, max(0, Int(selected.rounded())))
        return series.points[i].feeling != nil ? i : nil
    }

    var body: some View {
        let withNotes = series.points.contains { $0.feeling != nil && !names($0).isEmpty }
        ChartCard(title: L("chart_feeling_title"), takeaway: takeaway) {
            if let i = selectedIndex {
                ChartSelectionBubble(text: line(series.points[i]), fraction: slotFraction(i, of: series.points.count),
                                     onOpen: { onOpen(series.points[i].date) })
            } else {
                ChartTouchHint()
            }
            chart
                .frame(height: chartHeight)
                .chartFadeIn()
                .sensoryFeedback(.selection, trigger: selectedIndex)
            ChartLegend(items: [(symbol: AnyView(AverageSymbol()), text: L("chart_week_average"))])
            if selectedIndex == nil && withNotes {
                Text(L("chart_feeling_hint")).font(Fonts.manrope(12, relativeTo: .caption)).foregroundStyle(.secondary)
                    .padding(.top, 6).accessibilityHidden(true)
            }
        }
    }

    private var chart: some View {
        let palette = ChartPalette(scheme)
        let n = series.points.count
        let average = ChartData.weekAverage(series.points.map { $0.feeling.map(Double.init) })
        return Chart {
            ForEach(series.points.indices, id: \.self) { i in
                if let avg = average[i] {
                    LineMark(x: .value("night", Double(i)), y: .value("feeling", avg), series: .value("segment", -1))
                        .foregroundStyle(averageColor(scheme))
                        .lineStyle(averageStroke)
                        .interpolationMethod(.monotone)
                        .accessibilityHidden(true)
                }
            }
            ForEach(segments, id: \.index) { p in
                LineMark(x: .value("night", Double(p.index)), y: .value("feeling", Double(p.value)), series: .value("segment", p.segment))
                    .foregroundStyle(palette.quiet)
                    .lineStyle(StrokeStyle(lineWidth: 2, lineCap: .round))
                    .accessibilityHidden(true)
                PointMark(x: .value("night", Double(p.index)), y: .value("feeling", Double(p.value)))
                    .symbol {
                        ZStack {
                            if p.index == selectedIndex { Circle().fill(Color.accentColor.opacity(0.2)).frame(width: 24, height: 24) }
                            Circle().fill(palette.active).frame(width: 10, height: 10)
                            // Nächte mit Notiz: kleiner Ring.
                            if !names(series.points[p.index]).isEmpty { Circle().stroke(Color.accentColor, lineWidth: 1.5).frame(width: 17, height: 17) }
                        }
                    }
                    .accessibilityLabel(Text(verbatim: longDate(series.points[p.index].date)))
                    .accessibilityValue(Text(verbatim: line(series.points[p.index])))
            }
        }
        .chartXScale(domain: -0.5...(Double(n) - 0.5))
        .chartYScale(domain: 0.6...5.4)
        .chartXAxis {
            AxisMarks(values: [0, Double(n - 1)]) { value in
                AxisValueLabel {
                    if let v = value.as(Double.self), series.points.indices.contains(Int(v.rounded())) { Text(shortDate(series.points[Int(v.rounded())].date)) }
                }
            }
        }
        .chartYAxis {
            AxisMarks(position: .leading, values: [1.0, 2, 3, 4, 5]) { value in
                AxisGridLine()
                AxisValueLabel { if let v = value.as(Double.self), v == 1 || v == 5 { Text("\(Int(v))") } }
            }
        }
        .chartXSelection(value: $selected)
        .chartScrub()
    }
}
