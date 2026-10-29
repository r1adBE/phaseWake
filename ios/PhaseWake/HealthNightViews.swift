import PhaseWakeCore
import SwiftUI

private let stageOrder: [SleepStage] = [.awake, .rem, .light, .deep]
private let stageKeys = ["chart_stage_awake", "chart_stage_rem", "chart_stage_light", "chart_stage_deep"]

/// «Wach 20 Min · REM 1 Std. 20 Min · Leicht 3 Std. · Tief 1 Std.» (nur Phasen mit Zeit). Wie Android.
func sessionStageTotals(_ session: HealthSession) -> String {
    stageOrder.indices.compactMap { i in
        let minutes = session.minutesOf(stageOrder[i])
        return minutes > 0 ? L(stageKeys[i]) + " " + Format.duration(minutes: minutes) : nil
    }.joined(separator: " · ")
}

/// Dasselbe aus der Auswertung einer Nacht (``NightStages``), für die Karte einer Phase-Wake-Nacht.
func stageTotalsLine(_ stages: NightStages) -> String {
    stages.totals.compactMap { total in
        total.minutes > 0 ? stageName(total.stage) + " " + Format.duration(minutes: total.minutes) : nil
    }.joined(separator: " · ")
}

/// «Aus Apple Health. Änderungen und Löschungen dort übernimmt Phase Wake automatisch.» bzw. «Aus deinen
/// Gesundheitsdaten. …» ohne bekannte Quelle: Fremd-Nächte sind ein Spiegel der Quelle. Wie Android.
func healthLive(sourceName: String?) -> String {
    sourceName.map { L("history_health_live", $0) } ?? L("history_health_live_unknown")
}

/// Nacht aus Apple Health ohne Phase-Wake-Wecker: Datum und Aufwachzeit, Vermerk mit Herz-Symbol (nicht nur
/// Farbe), Quelle nur einmal im Hinweis, Zeiten, Dauern, Hinweis auf den Spiegel der Quelle, kleines Schlafprofil
/// mit Summen, Befinden (falls gewählt), Notizen und Geräusche kompakt. Kein Weckmoment. Antippen öffnet die
/// Detailseite; dort wird das Befinden gewählt. Wie Android.
struct HealthNightCard: View {
    let session: HealthSession
    /// Nachtdatum für Befinden, Notizen und Geräusche (`HistoryItem.date`).
    let nightDate: String
    /// Anzeigename der Quelle; `nil` = unbekannt.
    let sourceName: String?
    /// Befinden (1–5), nur bei Phase Wake gespeichert (nie in HealthKit); `nil`: keine Zeile (gewählt wird auf der Detailseite).
    let feeling: Int?
    /// Öffnet die Detailseite dieser Nacht.
    let onOpen: () -> Void

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
        VStack(alignment: .leading, spacing: 8) {
            Button(action: onOpen) { summary }
                .buttonStyle(.plain)
                .accessibilityHint(L("night_open"))
            if let feeling {
                Text(L("feeling_value", feelingName(feeling))).font(Fonts.manrope(12, relativeTo: .caption)).foregroundStyle(.secondary)
            }
            NightNotesButton(nightDate: nightDate, title: title)
            NightSoundsButton(night: nightDate, title: title)
        }
        .card()
    }

    /// Kopf, Zeiten, Quelle und Schlafprofil: der Teil, der die Detailseite öffnet.
    private var summary: some View {
        let lines = self.lines
        return VStack(alignment: .leading, spacing: 8) {
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
                MiniHypnogram(steps: session.steps, totalMinutes: session.end.timeIntervalSince(session.start) / 60)
                    .frame(height: miniHypnogramHeight)
                Text(sessionStageTotals(session)).font(Fonts.manrope(12, .semibold, relativeTo: .caption)).foregroundStyle(.secondary)
            }
            DetailsRow()
        }
        .contentShape(Rectangle())
    }
}

/// Höhe des kleinen Schlafprofils auf den Nachtkarten in Punkten (Auftrag: ≈ 64).
let miniHypnogramHeight: CGFloat = 64

/// «Details ›» unten rechts auf einer Nachtkarte: zeigt, dass ein Tipp die Detailseite öffnet.
struct DetailsRow: View {
    /// In einer Karte, die als Ganzes öffnet, trägt der Hinweis der Karte den Text; als eigener Knopf `false`.
    var hiddenForVoiceOver = true

    var body: some View {
        HStack(spacing: 4) {
            Spacer()
            Text(L("night_details"))
            Image(systemName: "chevron.right")
        }
        .font(Fonts.manrope(13, .semibold, relativeTo: .footnote))
        .foregroundStyle(.tint)
        .frame(minHeight: 44)
        .accessibilityHidden(hiddenForVoiceOver)
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
