import PhaseWakeCore
import SwiftUI

/// Befinden, Datenqualität und Trends – gleiche Texte und Regeln wie Android.
func feelingName(_ feeling: Int) -> String { L("feeling_\(min(5, max(1, feeling)))") }

func qualityText(_ night: NightRecord) -> String {
    switch NightQuality.of(night) {
    case .full: L("quality_full")
    case .motionOnly: L("quality_motion_only")
    case .partial: L("quality_partial")
    case .none: L("quality_none")
    }
}

func nightDetails(_ night: NightRecord) -> String {
    ([qualityText(night)] + (night.feeling.map { [L("feeling_value", feelingName($0))] } ?? [])).joined(separator: " · ")
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
    let healthConnected: Bool
    let onConnectHealth: () -> Void

    /// Ab dieser Schwankung der Einschlafzeit gibt es den Hinweis auf regelmässige Schlafzeiten.
    private static let irregularMinutes = 45

    private var lines: [String] {
        var out: [String] = []
        if let asleep = trends.asleepThisWeek {
            out.append(L("trends_asleep", Format.duration(minutes: asleep)))
            if let change = trends.asleepChange {
                if abs(change) < 5 { out.append(L("trends_asleep_change_same")) }
                else if change > 0 { out.append(L("trends_asleep_change_more", Format.duration(minutes: change))) }
                else { out.append(L("trends_asleep_change_less", Format.duration(minutes: -change))) }
            }
        }
        if let spread = trends.bedtimeSpreadMinutes {
            out.append(L("trends_bedtime", Format.duration(minutes: spread)))
            if spread > Self.irregularMinutes { out.append(L("trends_bedtime_hint")) }
        }
        if let feeling = trends.feelingThisWeek {
            let last = trends.feelingLastWeek.map { " · " + L("trends_feeling_change", decimal($0)) } ?? ""
            out.append(L("trends_feeling", decimal(feeling)) + last)
        }
        return out
    }

    var body: some View {
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
