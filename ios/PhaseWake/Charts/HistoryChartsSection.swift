import PhaseWakeCore
import SwiftUI

// MARK: Bereich

/// «Diagramme» oben im Verlauf: Zeitraum 7 / 14 / 30 Nächte für alle Karten, Karten zum seitlichen Blättern, die
/// nächste schaut am Rand hervor. Antippen eines Werts zeigt eine Blase; ihr Antippen öffnet die Nacht (`onOpen`).
struct HistoryChartsSection: View {
    let data: HistoryChartsData
    /// Öffnet die Detailseite zum Nachtdatum «yyyy-MM-dd».
    let onOpen: (String) -> Void
    @State private var nights = ChartData.nights

    var body: some View {
        if !data.isEmpty {
            let period = data.period(nights)
            let longest = data.longest
            VStack(alignment: .leading, spacing: 8) {
                Text(L("charts_title")).font(Fonts.manrope(17, .bold, relativeTo: .title3)).padding(.horizontal, 20)
                Picker(L("charts_title"), selection: $nights) {
                    Text(L("chart_nights_7")).tag(ChartData.shortNights)
                    Text(L("chart_nights_14")).tag(ChartData.nights)
                    Text(L("chart_nights_30")).tag(ChartData.monthNights)
                }
                .pickerStyle(.segmented)
                // Grosse Segmente: Tippziel nahe 44 pt.
                .controlSize(.large)
                .padding(.horizontal, 16)
                ScrollView(.horizontal) {
                    HStack(alignment: .top, spacing: 12) {
                        if longest.bars != nil {
                            if let bars = period.bars { SleepBarsCard(bars: bars, onOpen: onOpen).page() } else { EmptyChartCard(title: L("chart_bars_title")).page() }
                        }
                        if longest.durations != nil {
                            if let d = period.durations { DurationsCard(d: d, onOpen: onOpen).page() } else { EmptyChartCard(title: L("chart_durations_title")).page() }
                        }
                        if longest.wake != nil {
                            if let wake = period.wake { WakeCard(wake: wake, onOpen: onOpen).page() } else { EmptyChartCard(title: L("chart_wake_title")).page() }
                        }
                        if let h = data.hypnogram, let night = data.hypnogramNight { HypnogramCard(h: h, night: night, onOpen: onOpen).page() }
                        if longest.feeling != nil {
                            if let feeling = period.feeling { FeelingCard(series: feeling, onOpen: onOpen).page() } else { EmptyChartCard(title: L("chart_feeling_title")).page() }
                        }
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

/// Karte eines Diagramms, das im gewählten Zeitraum nichts zu zeigen hat (in einem längeren schon).
private struct EmptyChartCard: View {
    let title: String

    var body: some View {
        ChartCard(title: title, takeaway: nil) {
            Text(L("chart_no_data"))
                .font(Fonts.manrope(14, relativeTo: .body))
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, minHeight: chartHeight)
        }
    }
}

private extension View {
    /// Eine Seite: Breite des Bildschirms ohne Ränder, abzüglich des Blicks auf die nächste Karte.
    func page() -> some View { containerRelativeFrame(.horizontal) { width, _ in max(0, width - 60) } }
}
