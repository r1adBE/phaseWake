import Charts
import PhaseWakeCore
import SwiftUI

/// Was das grosse Schlafprofil zeichnet: Phasen in Minuten ab `start`, bei Phase-Wake-Nächten dazu Weckfenster,
/// Zielzeit und Weckmoment. Nächte aus anderen Apps haben nur Phasen.
struct StageTimeline: Equatable {
    /// Beginn der Zeitachse.
    let start: Date
    /// Länge der Zeitachse in Minuten.
    let totalMinutes: Double
    /// Bekannte Phasen in zeitlicher Reihenfolge (ohne «unbekannt»).
    let steps: [HypnoStep]
    /// Weckfenster in Minuten ab `start` (Beginn bis Zielzeit); `nil` ohne Phase-Wake-Wecker.
    var window: ClosedRange<Double>?
    /// Weckmoment in Minuten ab `start`; `nil` ohne Phase-Wake-Wecker.
    var wokeAt: Double?
    /// Phase beim Wecken für die Höhe des Weckpunkts; `nil` = unbekannt (Punkt auf der Zeile «Wach»).
    var stageAtWake: SleepStage?

    /// Uhrzeit `minute` Minuten nach `start`.
    func time(_ minute: Double) -> Date { start.addingTimeInterval(minute * 60) }

    /// Index der Phase, die `minute` enthält (Beginn einschliesslich); ausserhalb die nächstgelegene.
    func stepIndex(at minute: Double) -> Int? {
        if let i = steps.firstIndex(where: { minute >= $0.fromMinute && minute < $0.toMinute }) { return i }
        return steps.indices.min { distance(steps[$0], minute) < distance(steps[$1], minute) }
    }

    private func distance(_ step: HypnoStep, _ minute: Double) -> Double {
        minute < step.fromMinute ? step.fromMinute - minute : max(0, minute - step.toMinute)
    }
}

/// Reihenfolge der Zeilen von oben nach unten, wie `ChartData.level`.
let stageRows: [SleepStage] = [.awake, .rem, .light, .deep]

/// Name einer Phase für Achse, Blase und Bildschirmleser.
func stageName(_ stage: SleepStage) -> String {
    switch stage {
    case .awake: L("chart_stage_awake")
    case .rem: L("chart_stage_rem")
    case .light: L("chart_stage_light")
    case .deep: L("chart_stage_deep")
    case .unknown: ""
    }
}

/// Grosses, gestuftes Schlafprofil der Detailseite: vier beschriftete Zeilen, abgerundete Blöcke in den
/// Phasenfarben mit Stufenlinie, Zeitachse mit Beginn, zwei Zwischenzeiten und Ende; bei Phase-Wake-Nächten
/// Weckfenster als Band, Zielzeit als gestrichelte Linie und Weckpunkt als gefüllter Kreis (wie im Logo).
///
/// Bedienung: Finger kurz auflegen und ziehen (oder antippen) zeigt eine senkrechte Linie mit Blase
/// «05:42 · REM · seit 18 Min»; jeder Phasenwechsel gibt eine leichte Haptik. VoiceOver liest jede Phase
/// als eigenes Element («REM von 05:24 bis 05:46»). Wie `NightHypnogram` (Android).
struct StageChart: View {
    let timeline: StageTimeline
    /// Höhe der Zeichenfläche; auf der Karte oben kleiner.
    var height: CGFloat = StageChart.defaultHeight
    @Environment(\.colorScheme) private var scheme
    @State private var selected: Double?

    /// Höhe der Zeichenfläche in Punkten (Auftrag: 200–240).
    static let defaultHeight: CGFloat = 220

    private var names: [String] { stageRows.map(stageName) }

    private var selectedIndex: Int? { selected.flatMap { timeline.stepIndex(at: $0) } }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            bubble.frame(minHeight: 30, alignment: .leading)
            chart
                .frame(height: height)
                .sensoryFeedback(.selection, trigger: selectedIndex)
        }
    }

    private var chart: some View {
        Chart {
            if let window = timeline.window {
                RuleMark(x: .value("target", window.upperBound))
                    .foregroundStyle(StageColors.marker(scheme))
                    .lineStyle(StrokeStyle(lineWidth: 1.5, dash: [4, 3]))
                    .accessibilityHidden(true)
            }
            ForEach(timeline.steps.indices, id: \.self) { i in
                stepMarks(i)
            }
            if let wokeAt = timeline.wokeAt {
                let row = timeline.stageAtWake.flatMap { ChartData.level($0) == nil ? nil : $0 } ?? .awake
                PointMark(x: .value("woke", wokeAt), y: .value("stage", stageName(row)))
                    .symbol { WakeDot(scheme: scheme) }
                    .accessibilityLabel(Text(verbatim: L("night_woke", Format.time(timeline.time(wokeAt)))))
                    .accessibilityValue(Text(verbatim: windowText))
            }
            if let selected {
                RuleMark(x: .value("selected", selected))
                    .foregroundStyle(Color.primary.opacity(0.7))
                    .lineStyle(StrokeStyle(lineWidth: 1.5))
                    .accessibilityHidden(true)
            }
        }
        .chartXScale(domain: 0...max(1, timeline.totalMinutes))
        // Weckfenster als Band hinter den Phasen über die ganze Höhe.
        .chartBackground { proxy in
            GeometryReader { geo in
                if let window = timeline.window, let frame = proxy.plotFrame,
                   let x0 = proxy.position(forX: window.lowerBound), let x1 = proxy.position(forX: window.upperBound) {
                    let plot = geo[frame]
                    Rectangle().fill(StageColors.window(scheme))
                        .frame(width: max(0, x1 - x0), height: plot.height)
                        .position(x: plot.minX + (x0 + x1) / 2, y: plot.midY)
                }
            }
        }
        .chartYScale(domain: names)
        .chartXSelection(value: $selected)
        .chartScrub()
        .chartXAxis {
            AxisMarks(values: axisValues) { value in
                AxisGridLine()
                AxisValueLabel {
                    if let m = value.as(Double.self) { Text(Format.time(timeline.time(m))) }
                }
            }
        }
        .chartYAxis {
            AxisMarks(position: .leading) { value in
                AxisGridLine()
                AxisValueLabel {
                    if let name = value.as(String.self) { Text(name).font(Fonts.manrope(12, .semibold, relativeTo: .caption)) }
                }
            }
        }
        .chartFadeIn()
    }

    /// Block einer Phase und die senkrechte Stufe zur nächsten, wenn sie direkt anschliesst.
    @ChartContentBuilder
    private func stepMarks(_ i: Int) -> some ChartContent {
        let step = timeline.steps[i]
        let name = stageName(step.stage)
        RectangleMark(xStart: .value("from", step.fromMinute), xEnd: .value("to", max(step.toMinute, step.fromMinute + 0.5)),
                      y: .value("stage", name), height: .ratio(0.72))
            .foregroundStyle(StageColors.color(step.stage, scheme).opacity(selectedIndex == nil || selectedIndex == i ? 1 : 0.55))
            .cornerRadius(4)
            .accessibilityLabel(Text(verbatim: L("hypno_block", name, Format.time(timeline.time(step.fromMinute)), Format.time(timeline.time(step.toMinute)))))
        if i + 1 < timeline.steps.count, abs(timeline.steps[i + 1].fromMinute - step.toMinute) < 1 {
            RuleMark(x: .value("at", step.toMinute), yStart: .value("stage", name), yEnd: .value("stage", stageName(timeline.steps[i + 1].stage)))
                .foregroundStyle(StageColors.link(scheme))
                .lineStyle(StrokeStyle(lineWidth: 1.5))
                .accessibilityHidden(true)
        }
    }

    /// «Weckfenster 06:30 – 07:00» für VoiceOver beim Weckpunkt; leer ohne Fenster.
    private var windowText: String {
        guard let window = timeline.window else { return "" }
        return L("chart_window") + " " + Format.time(timeline.time(window.lowerBound)) + " – " + Format.time(timeline.time(window.upperBound))
    }

    /// Beginn, zwei Zwischenzeiten und Ende.
    private var axisValues: [Double] {
        let total = max(1, timeline.totalMinutes)
        return [0, total / 3, total * 2 / 3, total]
    }

    /// Blase über dem Diagramm: Uhrzeit, Phase und seit wann; ohne Auswahl der Hinweis zur Bedienung.
    @ViewBuilder private var bubble: some View {
        if let selected, let i = selectedIndex {
            let step = timeline.steps[i]
            let minute = min(max(selected, step.fromMinute), step.toMinute)
            let since = Int((minute - step.fromMinute).rounded(.down))
            Text(L("hypno_bubble", Format.time(timeline.time(minute)), stageName(step.stage), Format.duration(minutes: since)))
                .font(Fonts.manrope(13, .semibold, relativeTo: .footnote))
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .background(StageColors.color(step.stage, scheme).opacity(0.22), in: Capsule())
                .overlay(Capsule().stroke(StageColors.color(step.stage, scheme), lineWidth: 1))
                .accessibilityHidden(true)
        } else {
            Text(L("chart_touch_hint"))
                .font(Fonts.manrope(12, relativeTo: .caption))
                .foregroundStyle(Brand.secondaryText(scheme))
                .accessibilityHidden(true)
        }
    }
}

/// Weckpunkt: gefüllter Kreis mit hellem Ring, wie der Punkt im Logo.
private struct WakeDot: View {
    let scheme: ColorScheme
    var body: some View {
        ZStack {
            Circle().fill(scheme == .dark ? Brand.nightTop : Color.white).frame(width: 16, height: 16)
            Circle().fill(StageColors.marker(scheme)).frame(width: 11, height: 11)
        }
    }
}
