import Charts
import PhaseWakeCore
import SwiftUI

// MARK: 2. Schlafdauer pro Nacht

struct DurationsCard: View {
    let week: Durations?
    let twoWeeks: Durations
    @Environment(\.colorScheme) private var scheme
    @State private var nights = ChartData.shortNights

    private var d: Durations { nights == ChartData.shortNights ? (week ?? twoWeeks) : twoWeeks }

    private var takeaway: String {
        L("trends_asleep", Format.duration(minutes: d.averageMinutes)) + " · " + LP("goal_week", d.counted, d.reached, d.counted)
    }

    private var summary: String {
        ([L("chart_durations_title"), takeaway, L("chart_goal", Format.duration(minutes: d.goalMinutes))]
            + d.bars.map { longDate($0.date) + ": " + ($0.asleepMinutes.map { Format.duration(minutes: $0) } ?? L("chart_no_data")) })
            .joined(separator: ". ")
    }

    /// Abstand des Balkens zum Rand seines Platzes in Punkten.
    private let barInset: CGFloat = 4
    /// Strichstärke des Umrisses verfehlter Nächte in Punkten.
    private static let outline: CGFloat = 2

    /// Strichstärke als Minuten auf der Skala (Zeichenfläche höchstens `chartHeight` hoch).
    private func border(_ scale: Double) -> Double { scale / Double(chartHeight) * Double(Self.outline) }

    var body: some View {
        let palette = ChartPalette(scheme)
        let scale = Double(d.scaleMinutes) * 1.1
        ChartCard(title: L("chart_durations_title"), takeaway: takeaway) {
            if week != nil {
                Picker(L("chart_durations_title"), selection: $nights) {
                    Text(L("chart_nights_7")).tag(ChartData.shortNights)
                    Text(L("chart_nights_14")).tag(ChartData.nights)
                }
                .pickerStyle(.segmented)
                .padding(.bottom, 6)
            }
            Chart {
                ForEach(d.bars, id: \.date) { bar in
                    if let minutes = bar.asleepMinutes {
                        // Ziel erreicht: gefüllt mit «✓»; verfehlt: nur Umriss. So ohne Farbe lesbar (WCAG 1.4.1), wie Android.
                        BarMark(x: .value("night", bar.date), y: .value("minutes", Double(minutes)), width: .inset(barInset))
                            .foregroundStyle(palette.active)
                            .cornerRadius(4)
                            .annotation(position: .top, spacing: 2) {
                                if bar.reached { Text(verbatim: "✓").font(.caption2.bold()).foregroundStyle(palette.active) }
                            }
                        if !bar.reached, Double(minutes) > 2 * border(scale) {
                            BarMark(x: .value("night", bar.date), yStart: .value("minutes", border(scale)),
                                    yEnd: .value("minutes", Double(minutes) - border(scale)), width: .inset(barInset + Self.outline))
                                .foregroundStyle(Brand.card(scheme))
                                .cornerRadius(2)
                        }
                    } else {
                        BarMark(x: .value("night", bar.date), yStart: .value("minutes", 0.0), yEnd: .value("minutes", scale * 0.02))
                            .foregroundStyle(palette.track)
                    }
                }
                RuleMark(y: .value("goal", Double(d.goalMinutes)))
                    .foregroundStyle(Color.accentColor)
                    .lineStyle(StrokeStyle(lineWidth: 1.5, dash: [4, 3]))
            }
            .chartYScale(domain: 0...scale)
            .chartYAxis(.hidden)
            .chartXAxis {
                AxisMarks(values: [d.bars.first!.date, d.bars.last!.date]) { value in
                    AxisValueLabel { if let date = value.as(String.self) { Text(shortDate(date)) } }
                }
            }
            .frame(height: chartHeight)
            .chartFadeIn()
            .chartSummary(summary)
            ChartLegend(items: [
                (symbol: AnyView(DashSymbol(color: .accentColor)), text: L("chart_goal", Format.duration(minutes: d.goalMinutes))),
                // Gefüllte Säule mit «✓» = Ziel erreicht (wie Android).
                (symbol: AnyView(RoundedRectangle(cornerRadius: 2).fill(palette.active).frame(width: 8, height: 12)), text: L("chart_goal_met")),
            ])
        }
    }
}
