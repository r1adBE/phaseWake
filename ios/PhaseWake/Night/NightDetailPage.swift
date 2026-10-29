import PhaseWakeCore
import SwiftUI

/// Inhalt der Detailseite einer Nacht: Kopf (Datum, Schlafdauer gross, «Im Bett», Weckmoment und Grund bzw. Vermerk
/// «Ohne Phase-Wake-Wecker» mit Quelle), grosses Schlafprofil, Phasen-Summen mit üblichem Bereich und Einordnung,
/// Kennzahlen, dann Befinden, Notizen und Geräusche wie bisher. Wie `NightDetailPage` (Android).
struct NightDetailPage: View {
    @Environment(PhoneModel.self) private var model
    @Environment(\.colorScheme) private var scheme
    let data: NightDetailData
    /// Anzeigename der Quelle bei Nächten aus anderen Apps; `nil` = unbekannt.
    let sourceName: String?

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                header.card()
                VStack(alignment: .leading, spacing: 8) {
                    Text(L("chart_stages_title")).font(Fonts.manrope(15, .semibold, relativeTo: .headline))
                    if let timeline = data.timeline {
                        StageChart(timeline: timeline)
                        if timeline.window != nil { legend }
                    } else {
                        Text(L("night_no_stages")).font(Fonts.manrope(14, relativeTo: .body)).foregroundStyle(Brand.secondaryText(scheme))
                    }
                    if let night = data.item.night {
                        NightBars(epochs: night.epochs, totalEpochs: Int(night.target.timeIntervalSince(night.windowStart) / 30),
                                  unmeasured: unmeasuredEpochs(night))
                            .frame(height: 44)
                            .padding(.top, 4)
                    }
                }
                .card()
                if let stages = data.stages {
                    StageTotalsView(stages: stages).card()
                    NightMetricTiles(stages: stages).card()
                }
                extras.card()
            }
            .padding(.vertical)
        }
    }

    // MARK: Kopf

    private var header: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(data.title).font(Fonts.manrope(15, .semibold, relativeTo: .headline)).foregroundStyle(Brand.secondaryText(scheme))
            if let asleep = data.asleepMinutes {
                Text(Format.duration(minutes: asleep))
                    .font(Fonts.manrope(34, .bold, relativeTo: .largeTitle))
                    .accessibilityLabel(L("night_asleep") + " " + Format.duration(minutes: asleep))
            }
            Text(L("night_in_bed", Format.time(data.inBed.start), Format.time(data.inBed.end)))
                .font(Fonts.manrope(14, relativeTo: .subheadline))
            if let night = data.item.night {
                ownLines(night)
            } else {
                Label(L("history_health_mark"), systemImage: "heart.text.square")
                    .font(Fonts.manrope(12, .semibold, relativeTo: .caption))
                    .foregroundStyle(Brand.secondaryText(scheme))
                Text(healthLive(sourceName: sourceName))
                    .font(Fonts.manrope(11, relativeTo: .caption2))
                    .foregroundStyle(Brand.tertiaryText(scheme))
            }
        }
        .accessibilityElement(children: .combine)
    }

    /// Weckmoment, Grund und Zielzeit einer Phase-Wake-Nacht, dazu der Abgleich mit der Phase beim Wecken.
    @ViewBuilder
    private func ownLines(_ night: NightRecord) -> some View {
        Text(L("night_woke", Format.time(night.wokeAt)) + " · " + L("night_target", Format.time(night.target)))
            .font(Fonts.manrope(14, .semibold, relativeTo: .subheadline))
            .foregroundStyle(.tint)
        Text(nightSummary(night)).font(Fonts.manrope(13, relativeTo: .footnote)).foregroundStyle(Brand.secondaryText(scheme))
        if let verdict = wakeVerdict(data.timeline?.stageAtWake) {
            Text(verdict.text)
                .font(Fonts.manrope(13, .semibold, relativeTo: .footnote))
                .foregroundStyle(verdict.good ? Color.accentColor : .red)
        }
    }

    /// Legende des Profils; «Genau» hat kein Weckfenster, dann ohne dessen Zeichen.
    private var legend: some View {
        var items: [(symbol: AnyView, text: String)] = []
        if data.item.night?.wasExact != true {
            items.append((symbol: AnyView(RoundedRectangle(cornerRadius: 2).fill(StageColors.marker(scheme).opacity(0.25)).frame(width: 14, height: 8)),
                          text: L("chart_window")))
        }
        items.append((symbol: AnyView(DashSymbol(color: StageColors.marker(scheme))),
                      text: data.item.night.map { L("night_target", Format.time($0.target)) } ?? ""))
        items.append((symbol: AnyView(Circle().fill(StageColors.marker(scheme)).frame(width: 8, height: 8)),
                      text: data.item.night.map { L("night_woke", Format.time($0.wokeAt)) } ?? ""))
        return ChartLegend(items: items)
    }

    // MARK: Befinden, Notizen, Geräusche

    @ViewBuilder private var extras: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let night = data.item.night {
                Text(nightDetails(current(night))).font(Fonts.manrope(13, relativeTo: .footnote)).foregroundStyle(Brand.secondaryText(scheme))
                if let unmeasured = unmeasuredText(night) {
                    Text(unmeasured).font(Fonts.manrope(12, relativeTo: .caption)).foregroundStyle(Brand.secondaryText(scheme))
                }
                if current(night).feeling == nil {
                    FeelingPicker(onPick: { model.setFeeling($0, for: night) })
                }
            } else if let feeling = model.healthFeelings[data.nightDate] {
                Text(L("feeling_value", feelingName(feeling))).font(Fonts.manrope(13, relativeTo: .footnote)).foregroundStyle(Brand.secondaryText(scheme))
            } else {
                FeelingPicker(onPick: { model.setHealthFeeling($0, nightDate: data.nightDate) }, showsHint: false)
            }
            NightNotesButton(nightDate: data.nightDate, title: data.title)
            NightSoundsButton(night: data.nightDate, title: data.title)
        }
    }

    /// Die Nacht mit dem neuesten Befinden aus dem Modell (die Seite bleibt offen, während es gesetzt wird).
    private func current(_ night: NightRecord) -> NightRecord {
        model.nights.first { $0.alarmId == night.alarmId && $0.windowStart == night.windowStart } ?? night
    }
}

/// «Im leichten Schlaf geweckt» bzw. «Aus dem Tiefschlaf geweckt» nach der Phase beim Wecken; `nil` ohne Phase.
/// Gleiche Texte wie die Nachtkarte.
func wakeVerdict(_ stage: SleepStage?) -> (text: String, good: Bool)? {
    switch stage {
    case .light: (L("verdict_good", L("stage_light")), true)
    case .rem: (L("verdict_good", L("stage_rem")), true)
    case .awake: (L("verdict_good", L("stage_awake")), true)
    case .deep: (L("verdict_deep"), false)
    case .unknown, nil: nil
    }
}
