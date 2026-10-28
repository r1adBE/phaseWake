import Charts
import PhaseWakeCore
import SwiftUI

/// Trends-Zeilen, die ein Diagramm schon zeigt (dort weggelassen). Wie `TrendLine` (Android).
enum TrendLine: Hashable { case asleep, goal, regular, feeling }

/// Daten der Diagramme im Verlauf; jedes `nil` = Diagramm verborgen. Wie `HistoryCharts` (Android).
struct HistoryChartsData {
    var bars: SleepBars?
    var week: Durations?
    var twoWeeks: Durations?
    var wake: WakeMoments?
    var hypnogram: Hypnogram?
    var hypnogramNight: NightRecord?
    var feeling: FeelingSeries?

    var isEmpty: Bool { bars == nil && twoWeeks == nil && wake == nil && hypnogram == nil && feeling == nil }

    /// Was die Trends-Karte nicht mehr wiederholen muss.
    var covered: Set<TrendLine> {
        var out: Set<TrendLine> = []
        if week != nil { out.formUnion([.asleep, .goal]) }
        if bars != nil { out.insert(.regular) }
        if feeling != nil { out.insert(.feeling) }
        return out
    }

    /// Schlafprofil: die jüngste der letzten 14 Uhr-Nächte mit Schlafphasen.
    static func of(nights: [NightRecord], sleeps: [SleepSummary], stages: [StageSpan], notes: [String: [String]],
                   nextTarget: Date?, goalMinutes: Int, now: Date, calendar: Calendar) -> HistoryChartsData {
        let today = SleepNotes.nightDate(now, calendar: calendar)
        let night = stages.isEmpty ? nil : nights.sorted { $0.wokeAt > $1.wokeAt }.prefix(ChartData.nights)
            .first { ChartData.hypnogram(stages, night: $0) != nil }
        return HistoryChartsData(
            bars: ChartData.sleepBars(sleeps, calendar: calendar, today: today, nextTarget: nextTarget, goalMinutes: goalMinutes),
            week: ChartData.durations(sleeps, calendar: calendar, today: today, goalMinutes: goalMinutes, nights: ChartData.shortNights),
            twoWeeks: ChartData.durations(sleeps, calendar: calendar, today: today, goalMinutes: goalMinutes, nights: ChartData.nights),
            wake: ChartData.wakeMoments(nights, calendar: calendar),
            hypnogram: night.flatMap { ChartData.hypnogram(stages, night: $0) },
            hypnogramNight: night,
            feeling: ChartData.feelingSeries(nights, notes: notes, calendar: calendar, today: today)
        )
    }
}

/// Farben der Diagramme, gleich wie die Markenfarben auf Android (`BrandColors`).
private struct ChartPalette {
    let active: Color, quiet: Color, track: Color, wave: Color, echo: Color, dot: Color

    init(_ scheme: ColorScheme) {
        if scheme == .dark {
            active = Color(hex: 0x7DB3FF); quiet = Color(hex: 0x2F4FB8); track = Color(hex: 0x24345A)
            wave = Color(hex: 0x7DB3FF); echo = Color(hex: 0x2F4FB8); dot = .white
        } else {
            active = Color(hex: 0x2F4FB8); quiet = Color(hex: 0xB7C5EE); track = Color(hex: 0xE1E6F0)
            wave = Color(hex: 0x2F4FB8); echo = Color(hex: 0x9FB4F2); dot = Color(hex: 0x0B1A4A)
        }
    }
}

/// Höhe der Zeichenfläche, gleich auf allen Karten.
private let chartHeight: CGFloat = 168

// MARK: Hilfen

/// Nachtdatum «yyyy-MM-dd» als Datum des Morgens (wie die Nächte im Verlauf).
private func morning(_ night: String) -> Date {
    let parts = NightDate.adding(1, to: night).split(separator: "-").compactMap { Int($0) }
    guard parts.count == 3 else { return Date() }
    return Calendar.current.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2], hour: 12)) ?? Date()
}

/// «28. Okt.»
private func longDate(_ night: String) -> String { morning(night).formatted(.dateTime.day().month(.abbreviated)) }

/// «28.10.»
private func shortDate(_ night: String) -> String { morning(night).formatted(.dateTime.day().month(.defaultDigits)) }

/// Minuten ab Mittag als Uhrzeit.
private func clock(_ minutesFromNoon: Int) -> String {
    let m = ((720 + minutesFromNoon) % 1440 + 1440) % 1440
    return Format.time(hour: m / 60, minute: m % 60)
}

private extension View {
    /// Kurz einblenden; mit «Bewegung reduzieren» sofort sichtbar.
    func chartFadeIn() -> some View { modifier(ChartFadeIn()) }

    /// Für VoiceOver eine Textfassung statt der einzelnen Marken.
    func chartSummary(_ text: String) -> some View {
        accessibilityElement(children: .ignore).accessibilityLabel(text)
    }
}

private struct ChartFadeIn: ViewModifier {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var shown = false

    func body(content: Content) -> some View {
        content
            .opacity(shown ? 1 : 0)
            .onAppear {
                if reduceMotion { shown = true } else { withAnimation(.easeOut(duration: 0.3)) { shown = true } }
            }
    }
}

/// Karte einer Seite: Titel, eine Zeile Fazit, Diagramm.
private struct ChartCard<Content: View, Trailing: View>: View {
    let title: String
    let takeaway: String?
    @ViewBuilder var trailing: () -> Trailing
    @ViewBuilder var content: () -> Content
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(title).font(Fonts.manrope(15, .semibold, relativeTo: .headline))
                Spacer()
                trailing()
            }
            if let takeaway {
                Text(takeaway).font(Fonts.manrope(15, relativeTo: .body)).padding(.bottom, 6)
            }
            content()
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .topLeading)
        .background(scheme == .dark ? Color.white.opacity(0.07) : Color.white, in: RoundedRectangle(cornerRadius: 20))
    }
}

private extension ChartCard where Trailing == EmptyView {
    init(title: String, takeaway: String?, @ViewBuilder content: @escaping () -> Content) {
        self.init(title: title, takeaway: takeaway, trailing: { EmptyView() }, content: content)
    }
}

/// Legende: Zeichen und Text; für VoiceOver steht alles schon in der Beschreibung.
private struct ChartLegend: View {
    let items: [(symbol: AnyView, text: String)]

    var body: some View {
        // Nebeneinander, wenn es passt, sonst untereinander.
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 12) { entries }
            VStack(alignment: .leading, spacing: 4) { entries }
        }
        .font(Fonts.manrope(11, .semibold, relativeTo: .caption2))
        .foregroundStyle(.secondary)
        .padding(.top, 6)
        .accessibilityHidden(true)
    }
}

private extension ChartLegend {
    @ViewBuilder var entries: some View {
        ForEach(items.indices, id: \.self) { i in
            HStack(spacing: 4) {
                items[i].symbol.frame(width: 16, height: 12)
                Text(items[i].text)
            }
        }
    }
}

private struct DashSymbol: View {
    let color: Color
    var body: some View {
        Path { p in p.move(to: CGPoint(x: 0, y: 6)); p.addLine(to: CGPoint(x: 16, y: 6)) }
            .stroke(color, style: StrokeStyle(lineWidth: 1.5, dash: [4, 3]))
    }
}

// MARK: Bereich

/// «Diagramme» oben im Verlauf: Karten zum seitlichen Blättern, die nächste schaut am Rand hervor.
struct HistoryChartsSection: View {
    let data: HistoryChartsData

    var body: some View {
        if !data.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                Text(L("charts_title")).font(Fonts.manrope(17, .bold, relativeTo: .title3)).padding(.horizontal, 20)
                ScrollView(.horizontal) {
                    HStack(alignment: .top, spacing: 12) {
                        if let bars = data.bars { SleepBarsCard(bars: bars).page() }
                        if let twoWeeks = data.twoWeeks { DurationsCard(week: data.week, twoWeeks: twoWeeks).page() }
                        if let wake = data.wake { WakeCard(wake: wake).page() }
                        if let h = data.hypnogram, let night = data.hypnogramNight { HypnogramCard(h: h, night: night).page() }
                        if let feeling = data.feeling { FeelingCard(series: feeling).page() }
                    }
                    .scrollTargetLayout()
                }
                .scrollTargetBehavior(.viewAligned)
                .contentMargins(.horizontal, 16, for: .scrollContent)
                .scrollIndicators(.hidden)
            }
        }
    }
}

private extension View {
    /// Eine Seite: Breite des Bildschirms ohne Ränder, abzüglich des Blicks auf die nächste Karte.
    func page() -> some View { containerRelativeFrame(.horizontal) { width, _ in max(0, width - 60) } }
}

// MARK: 1. Schlaf-Streifen

private struct SleepBarsCard: View {
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

// MARK: 2. Schlafdauer pro Nacht

private struct DurationsCard: View {
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
                        BarMark(x: .value("night", bar.date), y: .value("minutes", Double(minutes)))
                            .foregroundStyle(bar.reached ? palette.active : palette.quiet)
                            .cornerRadius(4)
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
            ChartLegend(items: [(symbol: AnyView(DashSymbol(color: .accentColor)), text: L("chart_goal", Format.duration(minutes: d.goalMinutes)))])
        }
    }
}

// MARK: 3. Weckmoment im Fenster

/// Form je Weckart: Form allein unterscheidet schon (nicht nur Farbe). Wie Android.
private struct WakeSymbol: View {
    let kind: WakeKind
    let palette: ChartPalette
    var size: CGFloat = 10

    var body: some View {
        switch kind {
        case .light: Circle().fill(palette.active).frame(width: size, height: size)
        case .awake: Rectangle().fill(Color.secondary).frame(width: size * 0.75, height: size * 0.75).rotationEffect(.degrees(45))
        case .target: Circle().strokeBorder(Color.secondary, lineWidth: 2).frame(width: size, height: size)
        case .late: Image(systemName: "xmark").font(.system(size: size * 0.9, weight: .bold)).foregroundStyle(.red)
        }
    }
}

private struct WakeCard: View {
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

// MARK: 4. Schlafphasen der Nacht

private let stageOrder: [SleepStage] = [.awake, .rem, .light, .deep]
private let stageKeys = ["chart_stage_awake", "chart_stage_rem", "chart_stage_light", "chart_stage_deep"]

private struct HypnogramCard: View {
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

// MARK: 5. Befinden

private struct FeelingCard: View {
    @Environment(PhoneModel.self) private var model
    @Environment(\.colorScheme) private var scheme
    let series: FeelingSeries
    @State private var selected: Int?

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

    private var summary: String {
        ([L("chart_feeling_title")] + (takeaway.map { [$0] } ?? []) + series.points.filter { $0.feeling != nil }.map(line))
            .joined(separator: ". ")
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

    var body: some View {
        let palette = ChartPalette(scheme)
        let n = series.points.count
        let withNotes = series.points.contains { $0.feeling != nil && !names($0).isEmpty }
        ChartCard(title: L("chart_feeling_title"), takeaway: takeaway) {
            Chart {
                ForEach(segments, id: \.index) { p in
                    LineMark(x: .value("night", Double(p.index)), y: .value("feeling", Double(p.value)), series: .value("segment", p.segment))
                        .foregroundStyle(palette.quiet)
                        .lineStyle(StrokeStyle(lineWidth: 2, lineCap: .round))
                    PointMark(x: .value("night", Double(p.index)), y: .value("feeling", Double(p.value)))
                        .symbol {
                            ZStack {
                                if p.index == selected { Circle().fill(Color.accentColor.opacity(0.2)).frame(width: 24, height: 24) }
                                Circle().fill(palette.active).frame(width: 10, height: 10)
                                // Nächte mit Notiz: kleiner Ring.
                                if !names(series.points[p.index]).isEmpty { Circle().stroke(Color.accentColor, lineWidth: 1.5).frame(width: 17, height: 17) }
                            }
                        }
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
            .chartOverlay { proxy in
                GeometryReader { geo in
                    Rectangle().fill(.clear).contentShape(Rectangle())
                        .onTapGesture { location in
                            guard let frame = proxy.plotFrame else { return }
                            let x = location.x - geo[frame].origin.x
                            guard let value: Double = proxy.value(atX: x) else { return }
                            let i = min(n - 1, max(0, Int(value.rounded())))
                            selected = series.points[i].feeling != nil && selected != i ? i : nil
                        }
                }
            }
            .frame(height: chartHeight)
            .chartFadeIn()
            .chartSummary(summary)
            if let selected, series.points.indices.contains(selected) {
                Text(line(series.points[selected])).font(Fonts.manrope(13, relativeTo: .footnote)).padding(.top, 6)
            } else if withNotes {
                Text(L("chart_feeling_hint")).font(Fonts.manrope(12, relativeTo: .caption)).foregroundStyle(.secondary)
                    .padding(.top, 6).accessibilityHidden(true)
            }
        }
    }
}
