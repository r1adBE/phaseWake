import Charts
import PhaseWakeCore
import SwiftUI

// MARK: 1. Schlaf-Streifen

struct SleepBarsCard: View {
    let bars: SleepBars
    @Environment(\.colorScheme) private var scheme

    private var takeaway: String? {
        bars.regularCounted > 0 ? LP("habit_regular", bars.regularCounted, bars.regular, bars.regularCounted) : nil
    }

    private var summary: String {
        var parts = [L("chart_bars_title")]
        if let takeaway { parts.append(takeaway) }
        if let bedtime = bars.bedtimeMinute { parts.append(L("chart_bedtime", clock(bedtime))) }
        for bar in bars.bars {
            let value = bar.hasData ? clock(bar.startMinute!) + " – " + clock(bar.endMinute!) : L("chart_no_data")
            parts.append(longDate(bar.date) + ": " + value)
        }
        return parts.joined(separator: ". ")
    }

    private var ticks: [Int] {
        let step = bars.axisEnd - bars.axisStart > 15 * 60 ? 240 : 180
        return Array(stride(from: (bars.axisStart + step - 1) / step * step, through: bars.axisEnd, by: step))
    }

    var body: some View {
        let palette = ChartPalette(scheme)
        ChartCard(title: L("chart_bars_title"), takeaway: takeaway) {
            Chart {
                ForEach(bars.bars, id: \.date) { bar in
                    if let start = bar.startMinute, let end = bar.endMinute {
                        BarMark(xStart: .value("start", max(start, bars.axisStart)), xEnd: .value("end", min(end, bars.axisEnd)),
                                y: .value("night", bar.date), height: .fixed(8))
                            .foregroundStyle(palette.active)
                            .cornerRadius(4)
                    } else {
                        RuleMark(xStart: .value("start", bars.axisStart), xEnd: .value("end", bars.axisEnd), y: .value("night", bar.date))
                            .foregroundStyle(palette.track)
                            .lineStyle(StrokeStyle(lineWidth: 2, lineCap: .round))
                    }
                }
                if let bedtime = bars.bedtimeMinute {
                    RuleMark(x: .value("bedtime", bedtime))
                        .foregroundStyle(Color.accentColor)
                        .lineStyle(StrokeStyle(lineWidth: 1.5, dash: [4, 3]))
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
            .frame(height: chartHeight)
            .chartFadeIn()
            .chartSummary(summary)
            if let bedtime = bars.bedtimeMinute {
                ChartLegend(items: [(symbol: AnyView(DashSymbol(color: .accentColor)), text: L("chart_bedtime", clock(bedtime)))])
            }
        }
    }
}
