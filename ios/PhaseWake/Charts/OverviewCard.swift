import Charts
import PhaseWakeCore
import SwiftUI

/// Übersicht «Dein Schlaf» oben im Verlauf für die gewählte Einteilung: Ø Schlafdauer gross, Ø Schlafenszeit und
/// Ø Aufwachzeit, erreichtes Schlafziel, darunter Säulen (je Nacht, Woche bzw. Monat der Durchschnitt). Mit Phasen ist
/// jede Säule nach tief, leicht und REM aufgeteilt (Farben wie im Schlafprofil), Säulen ohne Phasen daneben neutral
/// (Woche/Monat: nur wenn alle Nächte Phasen haben, ``OverviewBar/stageMinutes``); ganz ohne Phasen in der
/// Markenfarbe. Erreichtes Ziel mit ✓. Antippen zeigt den Wert in der Blase; bei «Tage» öffnet sie die Nacht.
/// Wie `OverviewCard` (Android).
struct OverviewCard: View {
    /// Übersicht der gewählten Einteilung; `nil`: in dieser Einteilung keine Daten.
    let overview: SleepOverview?
    /// Öffnet die Detailseite zum Nachtdatum.
    let onOpen: (String) -> Void
    @Environment(\.colorScheme) private var scheme
    @State private var selected: String?

    /// Phasen von unten nach oben in einer Säule: tief, leicht, REM (Wach gehört nicht zur Schlafdauer).
    private static let stack: [SleepStage] = [.deep, .light, .rem]

    var body: some View {
        ChartCard(title: L("overview_title"), takeaway: nil) {
            if let o = overview {
                content(o)
            } else {
                Text(L("chart_no_data"))
                    .font(Fonts.manrope(14, relativeTo: .body))
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, minHeight: chartHeight)
            }
        }
        .onChange(of: overview?.unit) { _, _ in selected = nil }
    }

    @ViewBuilder
    private func content(_ o: SleepOverview) -> some View {
        let stacked = o.bars.contains { Self.total($0) > 0 }
        // Säulen mit Daten, aber ohne Phasen, neben solchen mit Phasen: neutral, Legende «Ohne Phasen».
        let neutral = stacked && o.bars.contains { $0.asleepMinutes != nil && Self.total($0) == 0 }
        averages(o)
        Text(LP("goal_week", o.counted, o.reachedNights, o.counted))
            .font(Fonts.manrope(14, relativeTo: .body))
            .padding(.top, 6)
        bubble(o)
        chart(o, stacked: stacked)
            .frame(height: chartHeight)
            .chartFadeIn()
            .sensoryFeedback(.selection, trigger: selected)
        ChartLegend(items: goalLegend(o))
        if stacked { ChartLegend(items: stageLegend(stacked: stacked, neutral: neutral)) }
    }

    /// Ø Schlafdauer gross, daneben Ø Schlafenszeit und Ø Aufwachzeit.
    private func averages(_ o: SleepOverview) -> some View {
        HStack(alignment: .bottom, spacing: 12) {
            value(Format.duration(minutes: o.averageMinutes), L("overview_asleep"), size: 24)
                .frame(maxWidth: .infinity, alignment: .leading)
                .layoutPriority(1)
            value(clock(o.bedtimeMinute), L("overview_bedtime"), size: 17)
                .frame(maxWidth: .infinity, alignment: .leading)
            value(clock(o.wakeMinute), L("overview_wake"), size: 17)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.top, 4)
    }

    private func value(_ text: String, _ label: String, size: CGFloat) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(text).font(Fonts.manrope(size, .bold, relativeTo: .title3)).lineLimit(1).minimumScaleFactor(0.7)
            Text(label).font(Fonts.manrope(11, .semibold, relativeTo: .caption2)).foregroundStyle(Brand.secondaryText(scheme))
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label + ": " + text)
    }

    private func chart(_ o: SleepOverview, stacked: Bool) -> some View {
        let palette = ChartPalette(scheme)
        let scale = Double(o.scaleMinutes) * 1.12
        let index = selectedIndex(o)
        let plain = LinearGradient(colors: [palette.active, StageColors.color(.deep, scheme)], startPoint: .top, endPoint: .bottom)
        return Chart {
            ForEach(o.bars.indices, id: \.self) { i in
                barMarks(o, i, stacked: stacked, dim: index != nil && index != i, plain: plain, scale: scale)
            }
            RuleMark(y: .value("goal", Double(o.goalMinutes)))
                .foregroundStyle(Color.accentColor)
                .lineStyle(StrokeStyle(lineWidth: 1.5, dash: [4, 3]))
                .accessibilityHidden(true)
            if let selected, index != nil {
                RuleMark(x: .value("bar", selected))
                    .foregroundStyle(Color.primary.opacity(0.25))
                    .accessibilityHidden(true)
            }
        }
        .chartYScale(domain: 0...scale)
        .chartYAxis(.hidden)
        .chartXAxis {
            AxisMarks(values: o.bars.map(\.first)) { value in
                AxisValueLabel {
                    if let first = value.as(String.self), let bar = o.bars.first(where: { $0.first == first }) {
                        Text(label(o.unit, bar))
                    }
                }
            }
        }
        .chartXSelection(value: $selected)
        .chartScrub()
    }

    /// Eine Säule: ganze Höhe (trägt ✓ und VoiceOver; mit Phasen durchsichtig, die Teile liegen darüber), ohne Daten ein
    /// flacher Strich.
    @ChartContentBuilder
    private func barMarks(_ o: SleepOverview, _ i: Int, stacked: Bool, dim: Bool, plain: LinearGradient, scale: Double) -> some ChartContent {
        let palette = ChartPalette(scheme)
        let bar = o.bars[i]
        let height = Double(bar.asleepMinutes ?? 0)
        let total = Self.total(bar)
        let style: AnyShapeStyle = bar.asleepMinutes == nil ? AnyShapeStyle(palette.track)
            : total > 0 ? AnyShapeStyle(Color.clear)
            : stacked ? AnyShapeStyle(Brand.secondaryText(scheme).opacity(0.3)) : AnyShapeStyle(plain)
        BarMark(x: .value("bar", bar.first), yStart: .value("minutes", 0.0),
                yEnd: .value("minutes", bar.asleepMinutes == nil ? scale * 0.02 : height), width: .ratio(0.55))
            .foregroundStyle(style)
            .cornerRadius(bar.asleepMinutes == nil ? 2 : 6)
            .opacity(dim ? 0.45 : 1)
            .annotation(position: .top, spacing: 2) {
                if bar.reached { Text(verbatim: "✓").font(.caption2.bold()).foregroundStyle(palette.active) }
            }
            .accessibilityLabel(Text(verbatim: period(o.unit, bar)))
            .accessibilityValue(Text(verbatim: barValue(bar)))
        ForEach(total > 0 && bar.asleepMinutes != nil ? segments(bar, height: height) : []) { part in
            BarMark(x: .value("bar", bar.first), yStart: .value("minutes", part.from), yEnd: .value("minutes", part.to), width: .ratio(0.55))
                .foregroundStyle(StageColors.color(part.stage, scheme))
                .cornerRadius(part.stage == .rem ? 6 : 2)
                .opacity(dim ? 0.45 : 1)
                .accessibilityHidden(true)
        }
    }

    /// Teile einer Säule mit Phasen, von unten: tief, leicht, REM, je im Verhältnis ihrer Minuten.
    private func segments(_ bar: OverviewBar, height: Double) -> [Segment] {
        let total = Double(Self.total(bar))
        var y = 0.0
        var out: [Segment] = []
        for stage in Self.stack {
            let part = height * Double(bar.stageMinutes[stage] ?? 0) / total
            if part > 0 {
                out.append(Segment(stage: stage, from: y, to: y + part))
                y += part
            }
        }
        return out
    }

    /// Ziellinie und «✓ Ziel erreicht».
    private func goalLegend(_ o: SleepOverview) -> [(symbol: AnyView, text: String)] {
        [
            (symbol: AnyView(DashSymbol(color: .accentColor)), text: L("chart_goal", Format.duration(minutes: o.goalMinutes))),
            // Das Zeichen steht schon im Text («✓ Ziel erreicht»).
            (symbol: AnyView(EmptyView()), text: L("chart_goal_met")),
        ]
    }

    /// Phasen der Säulen und «Ohne Phasen» (nur wenn solche Säulen zu sehen sind); leer ohne Phasen.
    private func stageLegend(stacked: Bool, neutral: Bool) -> [(symbol: AnyView, text: String)] {
        var items: [(symbol: AnyView, text: String)] = []
        if stacked {
            for stage in Self.stack {
                items.append((symbol: AnyView(RoundedRectangle(cornerRadius: 2).fill(StageColors.color(stage, scheme)).frame(width: 10, height: 10)),
                              text: stageName(stage)))
            }
        }
        if neutral {
            items.append((symbol: AnyView(RoundedRectangle(cornerRadius: 2).fill(Brand.secondaryText(scheme).opacity(0.3)).frame(width: 10, height: 10)),
                          text: L("overview_no_stages")))
        }
        return items
    }

    /// Ein Teil einer Säule (Phase von `from` bis `to` Minuten).
    private struct Segment: Identifiable {
        let stage: SleepStage
        let from: Double
        let to: Double
        var id: SleepStage { stage }
    }

    @ViewBuilder private func bubble(_ o: SleepOverview) -> some View {
        if let i = selectedIndex(o) {
            let bar = o.bars[i]
            ChartSelectionBubble(text: period(o.unit, bar) + " · " + barValue(bar), fraction: slotFraction(i, of: o.bars.count),
                                 onOpen: o.unit == .days && bar.asleepMinutes != nil ? { onOpen(bar.first) } : nil)
        } else {
            ChartTouchHint()
        }
    }

    private func selectedIndex(_ o: SleepOverview) -> Int? { selected.flatMap { s in o.bars.firstIndex { $0.first == s } } }

    /// Minuten tief + leicht + REM einer Säule (0 ohne Phasen).
    private static func total(_ bar: OverviewBar) -> Int { stack.reduce(0) { $0 + (bar.stageMinutes[$1] ?? 0) } }

    private func barValue(_ bar: OverviewBar) -> String {
        guard let minutes = bar.asleepMinutes else { return L("chart_no_data") }
        return Format.duration(minutes: minutes) + (bar.reached ? " · " + L("chart_goal_met") : "")
    }

    /// Beschriftung unter der Säule: Wochentag, Wochenbeginn bzw. Monat (je des Morgens).
    private func label(_ unit: OverviewUnit, _ bar: OverviewBar) -> String {
        switch unit {
        case .days: morning(bar.first).formatted(.dateTime.weekday(.abbreviated))
        case .weeks: shortDate(bar.first)
        case .months: morning(bar.first).formatted(.dateTime.month(.abbreviated))
        }
    }

    /// Zeitraum der Säule für Blase und VoiceOver: «Mo., 9. Nov.», «2. Nov. – 8. Nov.» bzw. «Oktober 2026».
    private func period(_ unit: OverviewUnit, _ bar: OverviewBar) -> String {
        switch unit {
        case .days: morning(bar.first).formatted(.dateTime.weekday(.abbreviated).day().month(.abbreviated))
        case .weeks: longDate(bar.first) + " – " + longDate(bar.last)
        case .months: morning(bar.first).formatted(.dateTime.month(.wide).year())
        }
    }
}
