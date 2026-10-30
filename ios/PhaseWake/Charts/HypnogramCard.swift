import PhaseWakeCore
import SwiftUI

// MARK: 4. Schlafphasen der Nacht

/// Schlafprofil der jüngsten Uhr-Nacht mit Phasen: dasselbe interaktive Profil wie auf der Detailseite (kleiner),
/// Summen und «Details ›», das die Nacht öffnet.
struct HypnogramCard: View {
    let h: Hypnogram
    let night: NightRecord
    /// Öffnet die Detailseite zum Nachtdatum.
    let onOpen: (String) -> Void
    @Environment(\.colorScheme) private var scheme

    /// «Wach 20 Min · REM 1 Std. 20 Min · Leicht 2 Std. 10 Min · Tief 1 Std.»
    private var totals: String {
        stageRows.compactMap { stage in
            let minutes = h.minutesOf(stage)
            return minutes > 0 ? stageName(stage) + " " + Format.duration(minutes: minutes) : nil
        }.joined(separator: " · ")
    }

    private var nightDate: String { SleepNotes.nightDate(of: night, calendar: .current) }

    private var timeline: StageTimeline {
        StageTimeline(start: h.start, totalMinutes: h.totalMinutes, steps: h.steps,
                      window: stageWindow(h, exact: night.wasExact),
                      wokeAt: h.wokeAt, stageAtWake: h.stageAtWake)
    }

    /// Weckfenster und Weckpunkt; «Genau» hat kein Weckfenster, dann ohne dessen Zeichen.
    private var legendItems: [(symbol: AnyView, text: String)] {
        var items: [(symbol: AnyView, text: String)] = []
        if !night.wasExact {
            items.append((symbol: AnyView(RoundedRectangle(cornerRadius: 2).fill(StageColors.marker(scheme).opacity(0.25)).frame(width: 14, height: 8)),
                          text: L("chart_window")))
        }
        items.append((symbol: AnyView(Circle().fill(StageColors.marker(scheme)).frame(width: 8, height: 8)), text: Format.time(night.wokeAt)))
        return items
    }

    var body: some View {
        ChartCard(title: L("chart_stages_title"), takeaway: nil, trailing: {
            Text(longDate(nightDate)).font(Fonts.manrope(12, .semibold, relativeTo: .caption)).foregroundStyle(.secondary)
        }) {
            StageChart(timeline: timeline, height: chartHeight)
            ChartLegend(items: legendItems)
            Text(totals).font(Fonts.manrope(11, .semibold, relativeTo: .caption2)).foregroundStyle(.secondary)
            Button { onOpen(nightDate) } label: { DetailsRow(hiddenForVoiceOver: false) }
                .buttonStyle(.plain)
                .accessibilityLabel(L("night_open"))
        }
    }
}
