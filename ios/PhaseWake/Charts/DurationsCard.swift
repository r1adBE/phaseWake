import Charts
import PhaseWakeCore
import SwiftUI

// MARK: 2. Schlafdauer pro Nacht

struct DurationsCard: View {
    let d: Durations
    /// Öffnet die Detailseite zum Nachtdatum.
    let onOpen: (String) -> Void
    @Environment(\.colorScheme) private var scheme
    @State private var selected: String?

    private var takeaway: String {
        L("trends_asleep", Format.duration(minutes: d.averageMinutes)) + " · " + LP("goal_week", d.counted, d.reached, d.counted)
    }

    /// Wert einer Nacht als Text: Dauer und ob das Ziel erreicht ist, ohne Daten «Keine Daten».
    private func value(_ bar: DurationBar) -> String {
        guard let minutes = bar.asleepMinutes else { return L("chart_no_data") }
        return Format.duration(minutes: minutes) + (bar.reached ? " · " + L("chart_goal_met") : "")
    }

    /// Gleitender Wochendurchschnitt in Minuten je Nacht (`ChartData.weekAverage`).
    private var average: [Double?] { ChartData.weekAverage(d.bars.map { $0.asleepMinutes.map(Double.init) }) }

    /// Abstand des Balkens zum Rand seines Platzes und Umriss verfehlter Nächte in Punkten; bei 30 Nächten schmaler.
    private var barInset: CGFloat { d.bars.count > ChartData.nights ? 1.5 : 4 }
    private var outline: CGFloat { d.bars.count > ChartData.nights ? 1 : 2 }

    /// Strichstärke als Minuten auf der Skala (Zeichenfläche höchstens `chartHeight` hoch).
    private func border(_ scale: Double) -> Double { scale / Double(chartHeight) * Double(outline) }

    private var selectedIndex: Int? { selected.flatMap { s in d.bars.firstIndex { $0.date == s } } }

    var body: some View {
        let scale = Double(d.scaleMinutes) * 1.1
        ChartCard(title: L("chart_durations_title"), takeaway: takeaway) {
            bubble
            chart(scale)
                .frame(height: chartHeight)
                .chartFadeIn()
                .sensoryFeedback(.selection, trigger: selected)
            ChartLegend(items: [
                (symbol: AnyView(DashSymbol(color: .accentColor)), text: L("chart_goal", Format.duration(minutes: d.goalMinutes))),
                // Gefüllte Säule mit «✓» = Ziel erreicht (wie Android).
                (symbol: AnyView(RoundedRectangle(cornerRadius: 2).fill(ChartPalette(scheme).active).frame(width: 8, height: 12)), text: L("chart_goal_met")),
                (symbol: AnyView(AverageSymbol()), text: L("chart_week_average")),
            ])
        }
    }

    private func chart(_ scale: Double) -> some View {
        let palette = ChartPalette(scheme)
        let average = self.average
        return Chart {
            ForEach(d.bars.indices, id: \.self) { i in
                let bar = d.bars[i]
                let dim = selectedIndex != nil && selectedIndex != i
                if let minutes = bar.asleepMinutes {
                    // Ziel erreicht: gefüllt mit «✓»; verfehlt: nur Umriss. So ohne Farbe lesbar (WCAG 1.4.1), wie Android.
                    BarMark(x: .value("night", bar.date), y: .value("minutes", Double(minutes)), width: .inset(barInset))
                        .foregroundStyle(palette.active.opacity(dim ? 0.45 : 1))
                        .cornerRadius(4)
                        .annotation(position: .top, spacing: 2) {
                            if bar.reached { Text(verbatim: "✓").font(.caption2.bold()).foregroundStyle(palette.active) }
                        }
                        .accessibilityLabel(Text(verbatim: longDate(bar.date)))
                        .accessibilityValue(Text(verbatim: value(bar)))
                    if !bar.reached, Double(minutes) > 2 * border(scale) {
                        BarMark(x: .value("night", bar.date), yStart: .value("minutes", border(scale)),
                                yEnd: .value("minutes", Double(minutes) - border(scale)), width: .inset(barInset + outline))
                            .foregroundStyle(Brand.card(scheme))
                            .cornerRadius(2)
                            .accessibilityHidden(true)
                    }
                } else {
                    BarMark(x: .value("night", bar.date), yStart: .value("minutes", 0.0), yEnd: .value("minutes", scale * 0.02))
                        .foregroundStyle(palette.track)
                        .accessibilityLabel(Text(verbatim: longDate(bar.date)))
                        .accessibilityValue(Text(verbatim: value(bar)))
                }
                if let avg = average[i] {
                    LineMark(x: .value("night", bar.date), y: .value("average", avg), series: .value("series", "average"))
                        .foregroundStyle(averageColor(scheme))
                        .lineStyle(averageStroke)
                        .interpolationMethod(.monotone)
                        .accessibilityHidden(true)
                }
            }
            RuleMark(y: .value("goal", Double(d.goalMinutes)))
                .foregroundStyle(Color.accentColor)
                .lineStyle(StrokeStyle(lineWidth: 1.5, dash: [4, 3]))
                .accessibilityHidden(true)
            if let selected, selectedIndex != nil {
                RuleMark(x: .value("night", selected))
                    .foregroundStyle(Color.primary.opacity(0.25))
                    .accessibilityHidden(true)
            }
        }
        .chartYScale(domain: 0...scale)
        .chartYAxis(.hidden)
        .chartXAxis {
            AxisMarks(values: [d.bars.first?.date ?? "", d.bars.last?.date ?? ""]) { value in
                AxisValueLabel { if let date = value.as(String.self) { Text(shortDate(date)) } }
            }
        }
        .chartXSelection(value: $selected)
        .chartScrub()
    }

    @ViewBuilder private var bubble: some View {
        if let i = selectedIndex {
            let bar = d.bars[i]
            ChartSelectionBubble(text: longDate(bar.date) + " · " + value(bar), fraction: slotFraction(i, of: d.bars.count),
                                 onOpen: bar.asleepMinutes == nil ? nil : { onOpen(bar.date) })
        } else {
            ChartTouchHint()
        }
    }
}
