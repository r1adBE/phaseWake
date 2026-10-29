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
    /// Öffnet die Detailseite zum Nachtdatum.
    let onOpen: (String) -> Void
    @Environment(\.colorScheme) private var scheme
    @State private var selected: Double?

    private var takeaway: String { L("history_average", wake.averageBefore) }

    /// «12 Min vor der Zielzeit · Leichter Schlaf» wie im Verlauf.
    private func timing(_ p: WakeMoment) -> String {
        // Gespeicherter Grund statt Art: «Genau» und «Kurze Nacht» sind auch Zielzeit, aber mit eigenem Text.
        let reason = reasonText(p.reason)
        if p.minutesBefore > 0 { return L("woke_early", p.minutesBefore, reason) }
        if p.minutesAfter > 0 { return LP("woke_late", p.minutesAfter, Int64(p.minutesAfter), reason) }
        return L("woke_on_time", reason)
    }

    private var selectedIndex: Int? {
        guard let selected, !wake.points.isEmpty else { return nil }
        return min(wake.points.count - 1, max(0, Int(selected.rounded())))
    }

    var body: some View {
        let palette = ChartPalette(scheme)
        ChartCard(title: L("chart_wake_title"), takeaway: takeaway) {
            bubble
            chart(palette)
                .frame(height: chartHeight)
                .chartFadeIn()
                .sensoryFeedback(.selection, trigger: selectedIndex)
            let present = Set(wake.points.map(\.kind))
            let all: [(WakeKind, String)] = [(.light, "reason_light"), (.awake, "reason_awake"), (.target, "chart_wake_target"), (.late, "chart_wake_late")]
            ChartLegend(items: all.filter { present.contains($0.0) }.map { (symbol: AnyView(WakeSymbol(kind: $0.0, palette: palette, size: 8)), text: L($0.1)) }
                + [(symbol: AnyView(AverageSymbol()), text: L("chart_week_average"))])
        }
    }

    private func chart(_ palette: ChartPalette) -> some View {
        let n = wake.points.count
        let average = ChartData.weekAverage(wake.points.map { Double($0.minutesBefore) as Double? })
        return Chart {
            ForEach(Array(wake.points.enumerated()), id: \.offset) { i, p in
                if let avg = average[i] {
                    LineMark(x: .value("night", Double(i)), y: .value("average", avg))
                        .foregroundStyle(averageColor(scheme))
                        .lineStyle(averageStroke)
                        .interpolationMethod(.monotone)
                        .accessibilityHidden(true)
                }
                PointMark(x: .value("night", Double(i)), y: .value("minutes", Double(p.minutesBefore)))
                    .symbol { WakeSymbol(kind: p.kind, palette: palette, size: selectedIndex == i ? 14 : 10) }
                    .accessibilityLabel(Text(verbatim: longDate(p.date)))
                    .accessibilityValue(Text(verbatim: timing(p)))
            }
            if let i = selectedIndex {
                RuleMark(x: .value("night", Double(i)))
                    .foregroundStyle(Color.primary.opacity(0.25))
                    .accessibilityHidden(true)
            }
        }
        .chartXScale(domain: -0.5...(Double(n) - 0.5))
        .chartYScale(domain: 0...Double(wake.scaleMinutes))
        .chartXAxis {
            AxisMarks(values: n > 1 ? [0, Double(n - 1)] : [0]) { value in
                AxisValueLabel {
                    if let v = value.as(Double.self), wake.points.indices.contains(Int(v.rounded())) { Text(shortDate(wake.points[Int(v.rounded())].date)) }
                }
            }
        }
        .chartYAxis {
            AxisMarks(position: .leading, values: [0, Double(wake.scaleMinutes / 2), Double(wake.scaleMinutes)]) { value in
                AxisGridLine()
                AxisValueLabel { if let m = value.as(Double.self) { Text(Format.duration(minutes: Int(m))) } }
            }
        }
        .chartXSelection(value: $selected)
        .chartScrub()
    }

    @ViewBuilder private var bubble: some View {
        if let i = selectedIndex {
            let p = wake.points[i]
            ChartSelectionBubble(text: longDate(p.date) + " · " + Format.time(p.wokeAt) + " · " + timing(p),
                                 fraction: slotFraction(i, of: wake.points.count), onOpen: { onOpen(p.date) })
        } else {
            ChartTouchHint()
        }
    }
}
