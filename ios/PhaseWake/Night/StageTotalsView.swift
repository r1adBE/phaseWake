import PhaseWakeCore
import SwiftUI

/// Phasen-Summen der Detailseite: je Phase Name, Anteil, Dauer und Balken; dahinter heller, gestrichelt umrandeter
/// «üblicher Bereich für Erwachsene» mit Legende. Darunter klein und einmal die Quelle der Richtwerte samt «Keine
/// medizinische Bewertung.» – bewusst ohne Urteil über die Nacht, denn Uhren schätzen Phasen nur ungefähr.
/// Ohne Farbe lesbar: Prozent, Dauer und Bereich stehen als Text. Wie `StageTotals` (Android).
struct StageTotalsView: View {
    let stages: NightStages
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            ForEach(stages.totals, id: \.stage) { total in
                StageTotalRow(total: total)
            }
            HStack(spacing: 6) {
                TypicalSwatch().frame(width: 18, height: 10)
                Text(L("stages_typical"))
            }
            .font(Fonts.manrope(11, .semibold, relativeTo: .caption2))
            .foregroundStyle(Brand.secondaryText(scheme))
            .accessibilityHidden(true)
            Text(L("stages_source") + " " + L("stages_not_medical"))
                .font(Fonts.manrope(11, relativeTo: .caption2))
                .foregroundStyle(Brand.tertiaryText(scheme))
        }
    }
}

/// «Üblich 20–25 %» bzw. «Üblich unter 10 %», wenn der Bereich bei 0 beginnt (wach).
func typicalText(_ range: StageRange) -> String {
    range.low == 0 ? L("stages_typical_below", Int64(range.high + 1)) : L("stages_typical_range", Int64(range.low), Int64(range.high))
}

/// Eine Zeile: Name und Anteil links, Dauer rechts, darunter der Balken mit dem üblichen Bereich und dessen Text.
private struct StageTotalRow: View {
    let total: StageTotal
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let share = L("stages_share", Int64(total.percent))
        let duration = Format.duration(minutes: total.minutes)
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline) {
                Circle().fill(StageColors.color(total.stage, scheme)).frame(width: 10, height: 10)
                Text(stageName(total.stage)).font(Fonts.manrope(14, .semibold, relativeTo: .subheadline))
                Text(share).font(Fonts.manrope(14, .bold, relativeTo: .subheadline))
                Spacer()
                Text(duration).font(Fonts.manrope(14, relativeTo: .subheadline)).foregroundStyle(Brand.secondaryText(scheme))
            }
            ShareBar(percent: total.percent, range: total.range, color: StageColors.color(total.stage, scheme))
                .frame(height: 12)
            Text(typicalText(total.range))
                .font(Fonts.manrope(11, relativeTo: .caption2))
                .foregroundStyle(Brand.tertiaryText(scheme))
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(stageName(total.stage))
        .accessibilityValue([share, duration, typicalText(total.range)].joined(separator: ", "))
    }
}

/// Balken 0–100 %: Spur, üblicher Bereich (hell, gestrichelt umrandet), Anteil gefüllt in der Phasenfarbe.
private struct ShareBar: View {
    let percent: Int
    let range: StageRange
    let color: Color
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        Canvas { context, size in
            let r = size.height / 2
            context.fill(Path(roundedRect: CGRect(origin: .zero, size: size), cornerRadius: r), with: .color(StageColors.track(scheme)))
            // «Unter 10 %» reicht bis 10, sonst von Grenze zu Grenze.
            let upper = range.low == 0 ? range.high + 1 : range.high
            let band = CGRect(x: size.width * CGFloat(range.low) / 100, y: 0,
                              width: size.width * CGFloat(upper - range.low) / 100, height: size.height)
            context.fill(Path(roundedRect: band, cornerRadius: 3), with: .color(StageColors.typical(scheme)))
            context.stroke(Path(roundedRect: band.insetBy(dx: 0.5, dy: 0.5), cornerRadius: 3), with: .color(StageColors.typicalEdge(scheme)),
                           style: StrokeStyle(lineWidth: 1, dash: [3, 2]))
            let width = size.width * CGFloat(min(100, max(0, percent))) / 100
            if width > 0 {
                let bar = CGRect(x: 0, y: size.height * 0.2, width: max(width, size.height * 0.6), height: size.height * 0.6)
                context.fill(Path(roundedRect: bar, cornerRadius: bar.height / 2), with: .color(color))
            }
        }
        .accessibilityHidden(true)
    }
}

/// Zeichen des üblichen Bereichs in der Legende.
private struct TypicalSwatch: View {
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        RoundedRectangle(cornerRadius: 3)
            .fill(StageColors.typical(scheme))
            .overlay(RoundedRectangle(cornerRadius: 3).strokeBorder(StageColors.typicalEdge(scheme), style: StrokeStyle(lineWidth: 1, dash: [3, 2])))
    }
}
