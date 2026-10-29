import PhaseWakeCore
import SwiftUI

private let stageOrder: [SleepStage] = [.awake, .rem, .light, .deep]
private let stageKeys = ["chart_stage_awake", "chart_stage_rem", "chart_stage_light", "chart_stage_deep"]

/// Farbe einer Schlafphase in Band und Schlafprofil; nie allein – Namen und Summen stehen als Text daneben.
func stageColor(_ stage: SleepStage, _ palette: ChartPalette) -> Color {
    switch stage {
    case .deep: palette.echo
    case .rem: palette.quiet
    case .light: palette.wave
    default: palette.dot
    }
}

/// «Wach 20 Min · REM 1 Std. 20 Min · Leicht 3 Std. · Tief 1 Std.» (nur Phasen mit Zeit). Wie Android.
func sessionStageTotals(_ session: HealthSession) -> String {
    stageOrder.indices.compactMap { i in
        let minutes = session.minutesOf(stageOrder[i])
        return minutes > 0 ? L(stageKeys[i]) + " " + Format.duration(minutes: minutes) : nil
    }.joined(separator: " · ")
}

/// «Aus Apple Health. Änderungen und Löschungen dort übernimmt Phase Wake automatisch.» bzw. «Aus deinen
/// Gesundheitsdaten. …» ohne bekannte Quelle: Fremd-Nächte sind ein Spiegel der Quelle. Wie Android.
func healthLive(sourceName: String?) -> String {
    sourceName.map { L("history_health_live", $0) } ?? L("history_health_live_unknown")
}

/// Nacht aus Apple Health ohne Phase-Wake-Wecker: Datum und Aufwachzeit, Vermerk mit Herz-Symbol (nicht nur
/// Farbe), Quelle nur einmal im Hinweis, Zeiten, Dauern, Hinweis auf den Spiegel der Quelle, Phasen-Band mit Summen,
/// Befinden, Notizen und Geräusche. Kein Weckmoment. Wie Android.
struct HealthNightCard: View {
    let session: HealthSession
    /// Nachtdatum für Befinden, Notizen und Geräusche (`HistoryItem.date`).
    let nightDate: String
    /// Anzeigename der Quelle; `nil` = unbekannt.
    let sourceName: String?
    /// Befinden (1–5), nur bei Phase Wake gespeichert (nie in HealthKit); `nil`: die Auswahl zeigen.
    let feeling: Int?
    /// Speichert das gewählte Befinden.
    let onFeeling: (Int) -> Void

    private var title: String { session.end.formatted(date: .abbreviated, time: .omitted) }

    /// «Im Bett 23:10 · eingeschlafen 23:24 · auf 06:52» und «8 Std. im Bett · …»; ohne Phasen die kurzen Fassungen.
    private var lines: (times: String, amounts: String) {
        let s = session
        if let awake = s.awakeMinutes {
            return (L("history_health_times", Format.time(s.start), Format.time(s.asleepAt), Format.time(s.end)),
                    L("history_health_amounts", Format.duration(minutes: s.durationMinutes), Format.duration(minutes: s.asleepMinutes),
                      Format.duration(minutes: awake)))
        }
        return (L("history_health_times_simple", Format.time(s.start), Format.time(s.end)),
                L("history_health_amount_simple", Format.duration(minutes: s.durationMinutes)))
    }

    var body: some View {
        let lines = self.lines
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(title).font(Fonts.manrope(15, .semibold, relativeTo: .headline))
                Spacer()
                Text(Format.time(session.end)).font(Fonts.manrope(16, .bold, relativeTo: .headline)).foregroundStyle(.secondary)
            }
            Label(L("history_health_mark"), systemImage: "heart.text.square")
                .font(Fonts.manrope(12, .semibold, relativeTo: .caption))
                .foregroundStyle(.secondary)
            VStack(alignment: .leading, spacing: 2) {
                Text(lines.times)
                Text(lines.amounts)
            }
            .font(Fonts.manrope(12, relativeTo: .caption))
            .foregroundStyle(.secondary)
            Text(healthLive(sourceName: sourceName)).font(Fonts.manrope(11, relativeTo: .caption2)).foregroundStyle(.secondary)
            if session.hasStages {
                let totals = sessionStageTotals(session)
                StageStrip(session: session)
                    .frame(height: 16)
                    .accessibilityElement()
                    .accessibilityLabel(L("chart_stages_title") + ". " + totals)
                Text(totals).font(Fonts.manrope(11, .semibold, relativeTo: .caption2)).foregroundStyle(.secondary).accessibilityHidden(true)
            }
            if let feeling {
                Text(L("feeling_value", feelingName(feeling))).font(Fonts.manrope(12, relativeTo: .caption)).foregroundStyle(.secondary)
            } else {
                FeelingPicker(onPick: onFeeling, showsHint: false).padding(.top, 4)
            }
            NightNotesButton(nightDate: nightDate, title: title)
            NightSoundsButton(night: nightDate, title: title)
        }
        .card()
    }
}

/// Phasen einer Sitzung als schmales Band über die ganze Sitzung (bekannte Phasen, auf die Sitzung zugeschnitten).
private struct StageStrip: View {
    let session: HealthSession
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let palette = ChartPalette(scheme)
        let total = max(1, session.end.timeIntervalSince(session.start) / 60)
        Canvas { context, size in
            context.fill(Path(roundedRect: CGRect(origin: .zero, size: size), cornerRadius: 3), with: .color(palette.track))
            for step in session.steps {
                let x = size.width * step.fromMinute / total
                let w = max(1, size.width * (step.toMinute - step.fromMinute) / total)
                context.fill(Path(CGRect(x: x, y: 0, width: w, height: size.height)), with: .color(stageColor(step.stage, palette)))
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 3))
    }
}

/// Leerer Verlauf: Grundtext; ohne Freigabe dazu der Hinweis auf Apple Health und der Knopf «Mit Gesundheitsdaten
/// abgleichen»; mit Freigabe, aber ohne Daten, was in Apple Health nötig ist. Wie `HistoryEmptyCard` (Android).
struct HistoryEmptyCard: View {
    let healthConnected: Bool
    /// Apple Health ist gelesen; vorher kein Hinweis «keine Daten».
    let loaded: Bool
    let onConnect: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(L("history_empty_title")).font(Fonts.manrope(17, .semibold, relativeTo: .headline))
            Group {
                Text(L("history_empty_text"))
                if !healthConnected {
                    Text(L("history_empty_connect_apple"))
                } else if loaded {
                    Text(L("history_empty_no_data_apple"))
                }
            }
            .font(Fonts.manrope(14, relativeTo: .body))
            .foregroundStyle(.secondary)
            if !healthConnected {
                Button(action: onConnect) {
                    Label(L("health_connect"), systemImage: "heart.text.square")
                }
                .buttonStyle(.bordered)
                .controlSize(.large)
                .font(Fonts.manrope(14, .semibold, relativeTo: .body))
                .padding(.top, 6)
            }
        }
        .card()
    }
}
