import PhaseWakeCore
import SwiftUI

/// Befinden, Datenqualität und Trends – gleiche Texte und Regeln wie Android.
func feelingName(_ feeling: Int) -> String { L("feeling_\(min(5, max(1, feeling)))") }

/// Die Smart-Alarm-Sitzung der Apple Watch beginnt höchstens 25 Minuten vor der Zielzeit
/// (`SmartAlarm.sessionLead`), das System darf sie bis 2 Minuten später starten (`startTolerance`).
let appleSessionLeadMinutes = 25
private let appleStartToleranceSeconds = 120

/// Epochen am Fensterbeginn, die vor dem Start der Sitzung lagen: ungemessen, nicht «kein leichter
/// Schlaf» und keine Lücke. Nur bei Fenstern über 25 Minuten und nur, solange sie ganz leer sind.
func unmeasuredEpochs(_ night: NightRecord) -> Int {
    let beforeSession = Int(night.target.timeIntervalSince(night.windowStart)) - appleSessionLeadMinutes * 60
    guard beforeSession > 0 else { return 0 }
    let limit = (beforeSession + appleStartToleranceSeconds) / DetectorConfig.epochSeconds
    return min(limit, night.epochs.prefix { $0.motion == nil && $0.heartRate == nil }.count)
}

/// Die Nacht ab Start der Messung, für Datenqualität und Hinweise. Die Erkennung auf der Uhr wärmt
/// ebenfalls erst danach auf; die Regeln selbst bleiben unverändert.
func measuredPart(_ night: NightRecord) -> NightRecord {
    let skip = unmeasuredEpochs(night)
    guard skip > 0 else { return night }
    return NightRecord(alarmId: night.alarmId, windowStart: night.windowStart, target: night.target, wokeAt: night.wokeAt,
                       reason: night.reason, snoozes: night.snoozes, epochs: Array(night.epochs.dropFirst(skip)), feeling: night.feeling)
}

func qualityText(_ night: NightRecord) -> String {
    switch NightQuality.of(measuredPart(night)) {
    case .full: L("quality_full")
    case .motionOnly: L("quality_motion_only")
    case .partial: L("quality_partial")
    case .none: L("quality_none")
    }
}

func nightDetails(_ night: NightRecord) -> String {
    ([qualityText(night)] + (night.feeling.map { [L("feeling_value", feelingName($0))] } ?? [])).joined(separator: " · ")
}

/// «Erste 5 Min nicht gemessen …» oder `nil`, wenn die Uhr das ganze Fenster gemessen hat.
func unmeasuredText(_ night: NightRecord) -> String? {
    let epochs = unmeasuredEpochs(night)
    guard epochs > 0 else { return nil }
    return L("night_unmeasured_apple", max(1, epochs * DetectorConfig.epochSeconds / 60), appleSessionLeadMinutes)
}

/// Fünf runde Knöpfe von «erschöpft» bis «erholt»; ein Tipp genügt.
struct FeelingPicker: View {
    let onPick: (Int) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L("feeling_question")).font(Fonts.manrope(15, .semibold, relativeTo: .headline))
            HStack(spacing: 8) {
                ForEach(NightRecord.feelingRange, id: \.self) { value in
                    Button { onPick(value) } label: {
                        Text("\(value)")
                            .font(Fonts.manrope(18, .bold, relativeTo: .title3))
                            .frame(maxWidth: .infinity)
                            .aspectRatio(1, contentMode: .fit)
                            .overlay(Circle().stroke(Color.secondary.opacity(0.5), lineWidth: 1))
                            .contentShape(Circle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(L("feeling_rate", value, feelingName(value)))
                }
            }
            HStack {
                Text(L("feeling_1"))
                Spacer()
                Text(L("feeling_5"))
            }
            .font(Fonts.manrope(11, relativeTo: .caption2))
            .foregroundStyle(.secondary)
            .accessibilityHidden(true)
            Text(L("feeling_hint")).font(Fonts.manrope(12, relativeTo: .caption)).foregroundStyle(.secondary)
        }
    }
}

/// Wochenvergleich: Schlafdauer, Regelmässigkeit, Befinden – nur was die Daten tragen.
struct TrendsCard: View {
    let trends: Trends
    /// «Schlafziel erreicht: N von M Nächten»; `nil` ohne Daten aus Apple Health.
    let goalWeek: (reached: Int, total: Int)?
    let healthConnected: Bool
    let onConnectHealth: () -> Void
    /// Gewohnheiten (Regelmässigkeit, Serien, Abendroutine), fertig formuliert.
    var habits: [String] = []
    /// Zeilen, die ein Diagramm oben schon zeigt: hier weggelassen.
    var covered: Set<TrendLine> = []

    /// Ab dieser Schwankung der Einschlafzeit gibt es den Hinweis auf regelmässige Schlafzeiten.
    private static let irregularMinutes = 45

    private var lines: [String] {
        var out: [String] = []
        if let asleep = trends.asleepThisWeek {
            if !covered.contains(.asleep) { out.append(L("trends_asleep", Format.duration(minutes: asleep))) }
            if let change = trends.asleepChange {
                if abs(change) < 5 { out.append(L("trends_asleep_change_same")) }
                else if change > 0 { out.append(L("trends_asleep_change_more", Format.duration(minutes: change))) }
                else { out.append(L("trends_asleep_change_less", Format.duration(minutes: -change))) }
            }
        }
        if let goalWeek, !covered.contains(.goal) {
            out.append(LP("goal_week", goalWeek.total, goalWeek.reached, goalWeek.total))
        }
        if let spread = trends.bedtimeSpreadMinutes {
            out.append(L("trends_bedtime", Format.duration(minutes: spread)))
            if spread > Self.irregularMinutes { out.append(L("trends_bedtime_hint")) }
        }
        if let feeling = trends.feelingThisWeek, !covered.contains(.feeling) {
            let last = trends.feelingLastWeek.map { " · " + L("trends_feeling_change", decimal($0)) } ?? ""
            out.append(L("trends_feeling", decimal(feeling)) + last)
        }
        out += habits
        return out
    }

    var body: some View {
        // Alles steht schon in den Diagrammen: keine leere Karte.
        if !(lines.isEmpty && !covered.isEmpty && healthConnected) { card }
    }

    private var card: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(L("trends_title")).font(Fonts.manrope(15, .semibold, relativeTo: .headline))
            if lines.isEmpty {
                Text(L("trends_not_enough")).font(Fonts.manrope(13, relativeTo: .footnote)).foregroundStyle(.secondary)
            }
            ForEach(lines, id: \.self) { Text($0).font(Fonts.manrope(15, relativeTo: .body)) }
            if !healthConnected {
                HStack {
                    Text(L("trends_health_hint_apple")).font(Fonts.manrope(12, relativeTo: .caption)).foregroundStyle(.secondary)
                    Spacer()
                    Button(L("health_connect"), action: onConnectHealth).font(Fonts.manrope(13, .semibold, relativeTo: .footnote))
                }
            }
        }
        .card()
    }

    private func decimal(_ value: Double) -> String { value.formatted(.number.precision(.fractionLength(1))) }
}

/// Verlauf als echte .csv-Datei zum Teilen (nicht als Text).
func exportFile(_ nights: [NightRecord], notes: [String: [String]]) -> URL {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent("phasewake-nights.csv")
    try? NightHistory.exportCsv(nights, notes: notes).write(to: url, atomically: true, encoding: .utf8)
    return url
}
