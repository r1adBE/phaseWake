import PhaseWakeCore
import SwiftUI

/// Was die Detailseite einer Nacht zeigt, aus einem Verlaufseintrag abgeleitet: Zeichnung, Auswertung, Zeiten.
/// Phase-Wake-Nächte und Nächte aus anderen Apps gleich aufgebaut. Wie `NightDetail` (Android).
struct NightDetailData {
    let item: HistoryItem
    /// Grosses Schlafprofil; `nil` ohne bekannte Phasen.
    let timeline: StageTimeline?
    /// Phasen-Summen und Kennzahlen; `nil` ohne bekannte Phasen.
    let stages: NightStages?
    /// «Im Bett» von–bis: Phasenbereich, sonst Sitzung bzw. Weckfenster.
    let inBed: (start: Date, end: Date)
    /// Geschlafene Minuten; `nil`, wenn unbekannt (eigene Nacht ohne Gesundheitsdaten).
    let asleepMinutes: Int?

    /// Nachtdatum des Eintrags («yyyy-MM-dd»).
    var nightDate: String { item.date }

    /// Datum wie auf der Karte: Tag des Weckens bzw. des Sitzungsendes.
    var title: String { item.at.formatted(date: .abbreviated, time: .omitted) }

    /// `stages` = alle Phasen aus Apple Health (für eigene Nächte, ``HealthHistory/stages(_:clearedAt:calendar:)``).
    init(item: HistoryItem, stages allStages: [StageSpan]) {
        self.item = item
        if let night = item.night {
            let h = ChartData.hypnogram(allStages, night: night)
            let stages = StageStats.forNight(allStages, night: night)
            timeline = h.map { h in
                StageTimeline(start: h.start, totalMinutes: h.totalMinutes, steps: h.steps,
                              window: h.windowFrom <= h.windowTo ? h.windowFrom...h.windowTo : nil,
                              wokeAt: h.wokeAt, stageAtWake: h.stageAtWake)
            }
            self.stages = stages
            inBed = stages.map { (start: $0.start, end: $0.end) } ?? (start: night.windowStart, end: night.wokeAt)
            asleepMinutes = stages?.asleepMinutes
        } else if let session = item.session {
            let stages = StageStats.forSession(session)
            timeline = session.hasStages
                ? StageTimeline(start: session.start, totalMinutes: max(1, session.end.timeIntervalSince(session.start) / 60), steps: session.steps)
                : nil
            self.stages = stages
            inBed = (start: session.start, end: session.end)
            asleepMinutes = stages?.asleepMinutes ?? session.asleepMinutes
        } else {
            timeline = nil
            stages = nil
            inBed = (start: item.at, end: item.at)
            asleepMinutes = nil
        }
    }
}

/// Kennzahlen als Kacheln: Einschlafdauer (falls bekannt), Schlafeffizienz, Wachphasen, längster Tiefschlaf.
struct NightMetricTiles: View {
    let stages: NightStages

    private var tiles: [(title: String, value: String, symbol: String)] {
        var out: [(String, String, String)] = []
        if let latency = stages.latencyMinutes {
            out.append((L("night_latency"), Format.duration(minutes: latency), "moon.zzz"))
        }
        out.append((L("night_efficiency"), L("stages_share", Int64(stages.efficiencyPercent)), "gauge.medium"))
        out.append((L("night_awakenings"), stages.awakenings.formatted(), "eye"))
        out.append((L("night_longest_deep"), Format.duration(minutes: stages.longestDeepMinutes), "water.waves"))
        return out.map { (title: $0.0, value: $0.1, symbol: $0.2) }
    }

    var body: some View {
        LazyVGrid(columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)], spacing: 12) {
            ForEach(tiles, id: \.title) { tile in
                MetricTile(title: tile.title, value: tile.value, symbol: tile.symbol)
            }
        }
    }
}

/// Eine Kachel: Symbol und Titel klein, Wert gross.
private struct MetricTile: View {
    let title: String
    let value: String
    let symbol: String
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label(title, systemImage: symbol)
                .font(Fonts.manrope(12, .semibold, relativeTo: .caption))
                .foregroundStyle(Brand.secondaryText(scheme))
            Text(value).font(Fonts.manrope(20, .bold, relativeTo: .title3))
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(StageColors.track(scheme).opacity(scheme == .dark ? 0.6 : 0.7), in: RoundedRectangle(cornerRadius: 16))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(title)
        .accessibilityValue(value)
    }
}
