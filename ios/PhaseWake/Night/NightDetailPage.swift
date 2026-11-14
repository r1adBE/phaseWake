import PhaseWakeCore
import SwiftUI

/// Inhalt der Detailseite einer Nacht: Kopf (Datum, Schlafdauer gross, «Im Bett», Weckmoment und Grund bzw. Vermerk
/// «Ohne Phase-Wake-Wecker» mit Quelle), Schlafwert mit Faktoren, grosses Schlafprofil, Puls (mit Aufzeichnung der Uhr
/// über die ganze Nacht samt Bewegung, sonst im Weckfenster),
/// Phasen-Summen mit üblichem Bereich und Einordnung, Kennzahlen, dann Befinden, Notizen und Geräusche wie bisher.
/// Wie `NightDetailPage` (Android).
struct NightDetailPage: View {
    @Environment(PhoneModel.self) private var model
    @Environment(\.colorScheme) private var scheme
    let data: NightDetailData
    /// Anzeigename der Quelle bei Nächten aus anderen Apps; `nil` = unbekannt.
    let sourceName: String?
    /// Quelle unter dem Schlafwert einer eigenen Nacht («Aus Apple Health.»); bei Nächten aus anderen Apps `nil`.
    var scoreSource: String?

    var body: some View {
        // Aufzeichnungen der Uhr: Puls und Bewegung über die ganze Nacht, Schlafwert ohne Phasen (wie Android).
        let recordings = PhoneLearning.shared.recordings
        let score = nightScore(stages: data.stages, session: data.item.session, goalMinutes: model.sleepGoal, night: data.item.night,
                               recordings: recordings)
        let series = nightSeries(data.item, recordings: recordings)
        let pulse = series.flatMap { SleepOverviews.pulse($0.epochs, start: $0.start) }
        ScrollView {
            VStack(spacing: 12) {
                header.card()
                if let score {
                    let source = score.source == .recording ? L("score_source_recording") : scoreSource
                    ScoreCard(score: score, source: data.item.night != nil ? source : nil, goalMinutes: model.sleepGoal)
                }
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
                if let pulse, let series { PulseCard(curve: pulse, fullNight: series.fullNight) }
                // Bewegung über die ganze Nacht nur mit Aufzeichnung; das Weckfenster zeigt schon das Feld beim Profil.
                if let series, series.fullNight, !series.epochs.isEmpty { MotionCard(series: series) }
                if let stages = data.stages {
                    StageTotalsView(stages: stages).card()
                    // Die Einschlafdauer steht schon als Faktor beim Schlafwert.
                    NightMetricTiles(stages: stages, withLatency: score?.result.factor(.latency) == nil).card()
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
            Text(L("night_in_bed", Format.time(data.inBed.start, in: data.timeZone), Format.time(data.inBed.end, in: data.timeZone)))
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

    /// Weckmoment, Grund und Zielzeit einer Phase-Wake-Nacht.
    @ViewBuilder
    private func ownLines(_ night: NightRecord) -> some View {
        Text(L("night_woke", Format.time(night.wokeAt)) + " · " + L("night_target", Format.time(night.target)))
            .font(Fonts.manrope(14, .semibold, relativeTo: .subheadline))
            .foregroundStyle(.tint)
        Text(nightSummary(night)).font(Fonts.manrope(13, relativeTo: .footnote)).foregroundStyle(Brand.secondaryText(scheme))
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
                if let battery = batteryLine(current(night)) {
                    Text(battery).font(Fonts.manrope(13, relativeTo: .footnote)).foregroundStyle(Brand.secondaryText(scheme))
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

/// Akku je Nacht (r1AD 14.11.2026): «Akku: Uhr −14 % · Handy −3 %», nur die bekannten Teile (``BatteryTrack/used(_:)``);
/// ohne Wert `nil` (keine Zeile). Wie `batteryLine` (Android).
func batteryLine(_ night: NightRecord) -> String? {
    let parts = [
        BatteryTrack.used(night.watchBattery).map { L("battery_watch", Int64($0)) },
        BatteryTrack.used(night.phoneBattery).map { L("battery_phone", Int64($0)) },
    ].compactMap { $0 }
    return parts.isEmpty ? nil : L("battery_night", parts.joined(separator: " · "))
}
