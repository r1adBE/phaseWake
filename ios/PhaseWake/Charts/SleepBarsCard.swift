import Charts
import PhaseWakeCore
import SwiftUI

// MARK: 1. Schlaf-Streifen

struct SleepBarsCard: View {
    let bars: SleepBars
    /// Öffnet die Detailseite zum Nachtdatum.
    let onOpen: (String) -> Void
    @Environment(\.colorScheme) private var scheme
    @State private var selected: String?

    private var takeaway: String? {
        bars.regularCounted > 0 ? LP("habit_regular", bars.regularCounted, bars.regular, bars.regularCounted) : nil
    }

    /// «23:10 – 06:52» bzw. «Keine Daten».
    private func value(_ bar: SleepBar) -> String {
        guard let start = bar.startMinute, let end = bar.endMinute else { return L("chart_no_data") }
        return clock(start) + " – " + clock(end)
    }

    private var ticks: [Int] {
        let step = bars.axisEnd - bars.axisStart > 15 * 60 ? 240 : 180
        return Array(stride(from: (bars.axisStart + step - 1) / step * step, through: bars.axisEnd, by: step))
    }

    /// Balkenhöhe: bei 30 Nächten schmaler, damit alle Platz haben.
    private var barHeight: CGFloat { bars.bars.count > ChartData.nights ? 4 : 8 }

    private var selectedIndex: Int? { selected.flatMap { s in bars.bars.firstIndex { $0.date == s } } }

    var body: some View {
        ChartCard(title: L("chart_bars_title"), takeaway: takeaway) {
            bubble
            chart
                .frame(height: chartHeight)
                .chartFadeIn()
                .sensoryFeedback(.selection, trigger: selected)
            if let bedtime = bars.bedtimeMinute {
                ChartLegend(items: [(symbol: AnyView(DashSymbol(color: .accentColor)), text: L("chart_bedtime", clock(bedtime)))])
            }
        }
    }

    private var chart: some View {
        let palette = ChartPalette(scheme)
        return Chart {
            ForEach(bars.bars.indices, id: \.self) { i in
                let bar = bars.bars[i]
                if let start = bar.startMinute, let end = bar.endMinute {
                    BarMark(xStart: .value("start", max(start, bars.axisStart)), xEnd: .value("end", min(end, bars.axisEnd)),
                            y: .value("night", bar.date), height: .fixed(selectedIndex == i ? barHeight + 4 : barHeight))
                        .foregroundStyle(palette.active.opacity(selectedIndex != nil && selectedIndex != i ? 0.45 : 1))
                        .cornerRadius(4)
                        .accessibilityLabel(Text(verbatim: longDate(bar.date)))
                        .accessibilityValue(Text(verbatim: value(bar)))
                } else {
                    RuleMark(xStart: .value("start", bars.axisStart), xEnd: .value("end", bars.axisEnd), y: .value("night", bar.date))
                        .foregroundStyle(palette.track)
                        .lineStyle(StrokeStyle(lineWidth: 2, lineCap: .round))
                        .accessibilityLabel(Text(verbatim: longDate(bar.date)))
                        .accessibilityValue(Text(verbatim: value(bar)))
                }
            }
            if let bedtime = bars.bedtimeMinute {
                RuleMark(x: .value("bedtime", bedtime))
                    .foregroundStyle(Color.accentColor)
                    .lineStyle(StrokeStyle(lineWidth: 1.5, dash: [4, 3]))
                    .accessibilityLabel(Text(verbatim: L("chart_bedtime", clock(bedtime))))
            }
        }
        .chartXScale(domain: bars.axisStart...bars.axisEnd)
        .chartXAxis {
            AxisMarks(values: ticks) { value in
                AxisGridLine()
                AxisValueLabel { if let m = value.as(Int.self) { Text(clock(m)) } }
            }
        }
        .chartYAxis(.hidden)
        .chartYSelection(value: $selected)
        .chartScrub(vertical: true)
    }

    @ViewBuilder private var bubble: some View {
        if let i = selectedIndex {
            let bar = bars.bars[i]
            ChartSelectionBubble(text: longDate(bar.date) + " · " + value(bar), fraction: fraction(bar),
                                 onOpen: bar.hasData ? { onOpen(bar.date) } : nil)
        } else {
            ChartTouchHint()
        }
    }

    /// Lage der Balkenmitte auf der Zeitachse, 0–1; ohne Daten die Mitte.
    private func fraction(_ bar: SleepBar) -> Double {
        guard let start = bar.startMinute, let end = bar.endMinute, bars.axisEnd > bars.axisStart else { return 0.5 }
        let mid = Double(min(max(start, bars.axisStart), bars.axisEnd) + min(end, bars.axisEnd)) / 2
        return (mid - Double(bars.axisStart)) / Double(bars.axisEnd - bars.axisStart)
    }
}
