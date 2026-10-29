import SwiftUI

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
