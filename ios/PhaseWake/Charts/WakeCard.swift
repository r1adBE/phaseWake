import Charts
import PhaseWakeCore
import SwiftUI

// MARK: 3. Weckmoment im Fenster

/// Form je Weckart, damit die Art ohne Farbe erkennbar ist (WCAG 1.4.1), wie Android: Kreis = leichter
/// Schlaf, Dreieck = wach, Quadrat = Zielzeit, Raute = nach der Zielzeit. Die Legende zeigt dieselben Formen.
private struct WakeSymbol: View {
    let kind: WakeKind
    let palette: ChartPalette
    var size: CGFloat = 10

    var body: some View {
        switch kind {
        case .light: Circle().fill(palette.active).frame(width: size, height: size)
        case .awake: Triangle().fill(Color.secondary).frame(width: size * 1.1, height: size)
        case .target: Rectangle().strokeBorder(Color.secondary, lineWidth: 2).frame(width: size * 0.85, height: size * 0.85)
        case .late: Rectangle().fill(Color.red).frame(width: size * 0.75, height: size * 0.75).rotationEffect(.degrees(45))
        }
    }
}

/// Gleichseitig wirkendes Dreieck mit der Spitze oben.
private struct Triangle: Shape {
    func path(in rect: CGRect) -> Path {
        Path { p in
            p.move(to: CGPoint(x: rect.midX, y: rect.minY))
            p.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
            p.addLine(to: CGPoint(x: rect.minX, y: rect.maxY))
            p.closeSubpath()
        }
    }
}

struct WakeCard: View {
    let wake: WakeMoments
    @Environment(\.colorScheme) private var scheme

    private var takeaway: String { L("history_average", wake.averageBefore) }

    private var summary: String {
        ([L("chart_wake_title"), takeaway] + wake.points.map { p in
            let reason: String = switch p.kind {
            case .light: L("reason_light")
            case .awake: L("reason_awake")
            case .target, .late: L("reason_deadline")
            }
            let timing: String = if p.minutesBefore > 0 {
                L("woke_early", p.minutesBefore, reason)
            } else if p.minutesAfter > 0 {
                LP("woke_late", p.minutesAfter, Int64(p.minutesAfter), reason)
            } else {
                L("woke_on_time", reason)
            }
            return longDate(p.date) + ": " + timing
        }).joined(separator: ". ")
    }

    var body: some View {
        let palette = ChartPalette(scheme)
        let n = wake.points.count
        ChartCard(title: L("chart_wake_title"), takeaway: takeaway) {
            Chart {
                ForEach(Array(wake.points.enumerated()), id: \.offset) { i, p in
                    PointMark(x: .value("night", Double(i)), y: .value("minutes", p.minutesBefore))
                        .symbol { WakeSymbol(kind: p.kind, palette: palette) }
                }
            }
            .chartXScale(domain: -0.5...(Double(n) - 0.5))
            .chartYScale(domain: 0...wake.scaleMinutes)
            .chartXAxis {
                AxisMarks(values: n > 1 ? [0, Double(n - 1)] : [0]) { value in
                    AxisValueLabel {
                        if let v = value.as(Double.self), wake.points.indices.contains(Int(v.rounded())) { Text(shortDate(wake.points[Int(v.rounded())].date)) }
                    }
                }
            }
            .chartYAxis {
                AxisMarks(position: .leading, values: [0, wake.scaleMinutes / 2, wake.scaleMinutes]) { value in
                    AxisGridLine()
                    AxisValueLabel { if let m = value.as(Int.self) { Text(Format.duration(minutes: m)) } }
                }
            }
            .frame(height: chartHeight)
            .chartFadeIn()
            .chartSummary(summary)
            let present = Set(wake.points.map(\.kind))
            let all: [(WakeKind, String)] = [(.light, "reason_light"), (.awake, "reason_awake"), (.target, "chart_wake_target"), (.late, "chart_wake_late")]
            ChartLegend(items: all.filter { present.contains($0.0) }.map { (symbol: AnyView(WakeSymbol(kind: $0.0, palette: palette, size: 8)), text: L($0.1)) })
        }
    }
}
