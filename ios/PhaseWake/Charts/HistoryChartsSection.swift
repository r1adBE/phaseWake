import PhaseWakeCore
import SwiftUI

// MARK: Bereich

/// Oben im Verlauf: Auswahl «Tage / Wochen / Monate» (gilt für alles darunter), die Übersicht «Dein Schlaf» und die
/// «Diagramme» als Karten zum seitlichen Blättern (Zeitraum 7, 14 bzw. 30 Nächte, ``HistoryChartsData/nights(_:)``), die
/// nächste schaut am Rand hervor. Antippen eines Werts zeigt eine Blase; ihr Antippen öffnet die Nacht (`onOpen`).
/// Wie Android (11k).
struct HistoryChartsSection: View {
    let data: HistoryChartsData
    /// Öffnet die Detailseite zum Nachtdatum «yyyy-MM-dd».
    let onOpen: (String) -> Void
    @State private var unit = OverviewUnit.days

    var body: some View {
        if !data.isEmpty || !data.overview.isEmpty {
            let period = data.period(HistoryChartsData.nights(unit))
            let longest = data.longest
            VStack(alignment: .leading, spacing: 8) {
                Picker(L("overview_title"), selection: $unit) {
                    Text(L("overview_days")).tag(OverviewUnit.days)
                    Text(L("overview_weeks")).tag(OverviewUnit.weeks)
                    Text(L("overview_months")).tag(OverviewUnit.months)
                }
                .pickerStyle(.segmented)
                // Grosse Segmente: Tippziel nahe 44 pt.
                .controlSize(.large)
                .padding(.horizontal, 16)
                if !data.overview.isEmpty {
                    OverviewCard(overview: data.overview[unit], onOpen: onOpen).padding(.horizontal, 16)
                }
                if !data.isEmpty {
                    Text(L("charts_title")).font(Fonts.manrope(17, .bold, relativeTo: .title3)).padding(.horizontal, 20).padding(.top, 4)
                    ScrollView(.horizontal) {
                        HStack(alignment: .top, spacing: 12) {
                            if longest.bars != nil {
                                if let bars = period.bars { SleepBarsCard(bars: bars, onOpen: onOpen).page() } else { EmptyChartCard(title: L("chart_bars_title")).page() }
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
