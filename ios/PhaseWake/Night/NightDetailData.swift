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

    /// Zone für Datum und Uhrzeiten: bei einer Nacht aus der Gesundheits-App die ihrer Aufzeichnung, sonst `nil`
    /// (Gerät).
    var timeZone: TimeZone? { item.session?.timeZone }

    /// Datum wie auf der Karte: Tag des Weckens bzw. des Sitzungsendes.
    var title: String { Format.day(item.at, in: timeZone) }

    /// `stages` = alle Phasen aus Apple Health (für eigene Nächte, ``HealthHistory/stages(_:clearedAt:calendar:)``).
    init(item: HistoryItem, stages allStages: [StageSpan]) {
        self.item = item
        if let night = item.night {
            let h = ChartData.hypnogram(allStages, night: night)
            let stages = StageStats.forNight(allStages, night: night)
            timeline = h.map { h in
                StageTimeline(start: h.start, totalMinutes: h.totalMinutes, steps: h.steps,
                              window: stageWindow(h, exact: night.wasExact),
                              wokeAt: h.wokeAt, stageAtWake: h.stageAtWake)
            }
            self.stages = stages
            inBed = stages.map { (start: $0.start, end: $0.end) } ?? (start: night.windowStart, end: night.wokeAt)
            asleepMinutes = stages?.asleepMinutes
        } else if let session = item.session {
            let stages = StageStats.forSession(session)
            timeline = session.hasStages
                ? StageTimeline(start: session.start,
                                totalMinutes: max(1, session.end.timeIntervalSince(session.start) / 60),
                                steps: session.steps,
                                timeZone: session.timeZone)
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

/// Kennzahlen als Kacheln: Einschlafdauer (falls bekannt), Schlafeffizienz, Wachmomente (sonst nur kurze, oder
/// «davon N länger als 5 Min»), längster Tiefschlaf. Wie Android `NightTiles`.
struct NightMetricTiles: View {
    let stages: NightStages
    /// `false`, wenn die Einschlafdauer schon als Faktor beim Schlafwert steht.
    var withLatency = true

    private var tiles: [MetricTile] {
        var out: [MetricTile] = []
        if withLatency, let latency = stages.latencyMinutes {
            out.append(MetricTile(title: L("night_latency"), value: Format.duration(minutes: latency),
                                  symbol: "moon.zzz"))
        }
        out.append(MetricTile(title: L("night_efficiency"), value: L("stages_share", Int64(stages.efficiencyPercent)),
                              symbol: "gauge.medium"))
        let long = stages.longAwakenings
        out.append(MetricTile(title: L(long > 0 ? "night_wake_moments" : "night_wake_brief"),
                              value: stages.awakenings.formatted(), symbol: "eye",
                              note: long > 0 ? LP("night_wake_long", long, long, StageStats.longWakeMinutes) : nil))
        out.append(MetricTile(title: L("night_longest_deep"),
                              value: Format.duration(minutes: stages.longestDeepMinutes), symbol: "water.waves"))
        return out
    }

    var body: some View {
        // Gleich hohe Kacheln je Reihe, auch wenn eine eine Zusatzzeile hat.
        Grid(horizontalSpacing: 12, verticalSpacing: 12) {
            ForEach(Array(stride(from: 0, to: tiles.count, by: 2)), id: \.self) { i in
                GridRow {
                    tiles[i]
                    if i + 1 < tiles.count { tiles[i + 1] } else { Color.clear.gridCellUnsizedAxes(.vertical) }
                }
            }
        }
    }
}

/// Eine Kachel: Symbol und Titel klein, Wert gross, darunter falls vorhanden `note` noch leiser (wie Android).
private struct MetricTile: View {
    let title: String
    let value: String
    let symbol: String
    var note: String?
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label(title, systemImage: symbol)
                .font(Fonts.manrope(12, .semibold, relativeTo: .caption))
                .foregroundStyle(Brand.secondaryText(scheme))
            Text(value).font(Fonts.manrope(28, .heavy, relativeTo: .title))
            if let note {
                Text(note)
                    .font(Fonts.manrope(11, relativeTo: .caption2))
                    .foregroundStyle(Brand.tertiaryText(scheme))
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(StageColors.track(scheme).opacity(scheme == .dark ? 0.6 : 0.7),
                    in: RoundedRectangle(cornerRadius: 16))
        // Hauch der Hauptfarbe oben (dunkel 18 %, hell 10 %, wie Android `tileTop`).
        .background(LinearGradient(colors: [Brand.accent(scheme).opacity(scheme == .dark ? 0.18 : 0.10), .clear],
                                   startPoint: .top, endPoint: .bottom),
                    in: RoundedRectangle(cornerRadius: 16))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(title)
        .accessibilityValue(note.map { value + ", " + $0 } ?? value)
    }
}
