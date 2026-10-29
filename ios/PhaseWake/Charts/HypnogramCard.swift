import Charts
import PhaseWakeCore
import SwiftUI

// MARK: 4. Schlafphasen der Nacht

private let stageOrder: [SleepStage] = [.awake, .rem, .light, .deep]
private let stageKeys = ["chart_stage_awake", "chart_stage_rem", "chart_stage_light", "chart_stage_deep"]

struct HypnogramCard: View {
    let h: Hypnogram
    let night: NightRecord
    @Environment(\.colorScheme) private var scheme

    private var takeaway: String? {
        switch h.stageAtWake {
        case .light: L("verdict_good", L("stage_light"))
        case .rem: L("verdict_good", L("stage_rem"))
        case .awake: L("verdict_good", L("stage_awake"))
        case .deep: L("verdict_deep")
        case .unknown, nil: nil
        }
    }

    /// «Wach 20 Min · REM 1 Std. 20 Min · Leicht 2 Std. 10 Min · Tief 1 Std.»
    private var totals: String {
        stageOrder.indices.compactMap { i in
            let minutes = h.minutesOf(stageOrder[i])
            return minutes > 0 ? L(stageKeys[i]) + " " + Format.duration(minutes: minutes) : nil
        }.joined(separator: " · ")
    }

    private var date: String { longDate(SleepNotes.nightDate(of: night, calendar: .current)) }

    private var summary: String {
        ([L("chart_stages_title") + " " + date] + (takeaway.map { [$0] } ?? []) + [
            Format.time(h.start) + " – " + Format.time(h.end),
            L("chart_window") + " " + Format.time(night.windowStart) + " – " + Format.time(night.target),
            totals,
        ]).joined(separator: ". ")
    }

    private func color(_ stage: SleepStage, _ palette: ChartPalette) -> Color {
        switch stage {
        case .deep: palette.echo
        case .rem: palette.quiet
        case .light: palette.wave
        default: palette.dot
        }
    }

    var body: some View {
        let palette = ChartPalette(scheme)
        let names = stageKeys.map { L($0) }
        let steps = h.steps.filter { ChartData.level($0.stage) != nil }
        ChartCard(title: L("chart_stages_title"), takeaway: takeaway, trailing: {
            Text(date).font(Fonts.manrope(12, .semibold, relativeTo: .caption)).foregroundStyle(.secondary)
        }) {
            Chart {
                ForEach(steps.indices, id: \.self) { i in
                    let step = steps[i]
                    let name = names[ChartData.level(step.stage)!]
                    RectangleMark(xStart: .value("from", step.fromMinute), xEnd: .value("to", max(step.toMinute, step.fromMinute + 0.5)),
                                  y: .value("stage", name), height: .ratio(0.5))
                        .foregroundStyle(color(step.stage, palette))
                        .cornerRadius(2)
                    // Dünne Verbindung zur nächsten Phase, wenn sie direkt anschliesst.
                    if i + 1 < steps.count, abs(steps[i + 1].fromMinute - step.toMinute) < 1 {
                        RuleMark(x: .value("at", step.toMinute), yStart: .value("stage", name),
                                 yEnd: .value("stage", names[ChartData.level(steps[i + 1].stage)!]))
                            .foregroundStyle(Color.secondary)
                            .lineStyle(StrokeStyle(lineWidth: 1))
                    }
                }
                RuleMark(x: .value("woke", h.wokeAt))
                    .foregroundStyle(Color.accentColor)
                    .lineStyle(StrokeStyle(lineWidth: 2))
            }
            .chartXScale(domain: 0...max(1, h.totalMinutes))
            // Weckfenster als Fläche hinter den Phasen.
            .chartBackground { proxy in
                GeometryReader { geo in
                    if let frame = proxy.plotFrame, let x0 = proxy.position(forX: h.windowFrom), let x1 = proxy.position(forX: h.windowTo) {
                        let plot = geo[frame]
                        Rectangle().fill(Color.accentColor.opacity(0.12))
                            .frame(width: max(0, x1 - x0), height: plot.height)
                            .position(x: plot.minX + (x0 + x1) / 2, y: plot.midY)
                    }
                }
            }
            .chartYScale(domain: names)
            .chartXAxis {
                AxisMarks(values: [0, h.totalMinutes]) { value in
                    AxisValueLabel {
                        if let m = value.as(Double.self) { Text(Format.time(h.start.addingTimeInterval(m * 60))) }
                    }
                }
            }
            .chartYAxis {
                AxisMarks(position: .leading) { _ in
                    AxisGridLine()
                    AxisValueLabel()
                }
            }
            .frame(height: chartHeight)
            .chartFadeIn()
            .chartSummary(summary)
            ChartLegend(items: [
                (symbol: AnyView(RoundedRectangle(cornerRadius: 2).fill(Color.accentColor.opacity(0.25)).frame(width: 14, height: 8)), text: L("chart_window")),
                (symbol: AnyView(Circle().fill(Color.accentColor).frame(width: 8, height: 8)), text: Format.time(night.wokeAt)),
            ])
            Text(totals).font(Fonts.manrope(11, .semibold, relativeTo: .caption2)).foregroundStyle(.secondary).accessibilityHidden(true)
        }
    }
}
