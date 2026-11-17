import SwiftUI

/// Runder Tag-Schalter im Editor, wie `DayToggle` auf Android: alle sieben in einer Zeile, die ganze Fläche ist
/// Tippziel (mindestens 44 pt hoch, ohne Lücke zum Nachbarn), der sichtbare Kreis darin bleibt kleiner. Gewählt
/// Lavendel (hell #5546C8 mit weisser, dunkel #B9AEFF mit dunkler Schrift), nicht gewählt hell Caramel #F1E2CB,
/// dunkel Lavendel 12 %.
struct DayToggle: View {
    let label: String
    let on: Bool
    let toggle: () -> Void
    @Environment(\.colorScheme) private var scheme

    private var fill: Color {
        if on { return Brand.accent(scheme) }
        return scheme == .dark ? Brand.wave.opacity(0.12) : Color(hex: 0xF1E2CB)
    }

    var body: some View {
        Button(action: toggle) {
            ZStack {
                Circle().fill(fill).aspectRatio(1, contentMode: .fit).padding(.horizontal, 3)
                VStack(spacing: 2) {
                    Text(label).lineLimit(1).minimumScaleFactor(0.8)
                    // Punkt unter dem gewählten Tag: Form zusätzlich zur Füllung (Farbe nie allein).
                    Circle().frame(width: 4, height: 4).opacity(on ? 1 : 0).accessibilityHidden(true)
                }
                .foregroundStyle(on ? Brand.onAccent(scheme) : Color.primary)
            }
            .frame(maxWidth: .infinity, minHeight: 44)
            .contentShape(Rectangle())
        }
        .font(Fonts.manrope(13, .semibold, relativeTo: .footnote))
        .buttonStyle(.plain)
        .accessibilityAddTraits(on ? .isSelected : [])
    }
}
