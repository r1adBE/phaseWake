import SwiftUI

/// Kleines Bild über dem Titel eines Einrichtungsschritts (Vollaudit Design 2, wie Android): die Welle aus dem Logo mit
/// dem Mond als Weckpunkt, der mit jedem Schritt weiter nach rechts wandert – so zeigt das Bild zugleich, wie weit die
/// Einrichtung ist. Nur Schmuck (für VoiceOver verborgen; «Schritt x von y» steht als Text darüber).
struct OnboardingGlyph: View {
    /// Aktueller Schritt (ab 0).
    let step: Int
    /// Anzahl der Schritte vor «Fertig».
    let steps: Int
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        Canvas { context, size in
            let fraction = Self.position(step: step, steps: steps)
            let mid = size.height * 0.62
            let amplitude = size.height * 0.24
            var wave = Path()
            for i in 0...60 {
                let x = size.width * CGFloat(i) / 60
                let y = mid + amplitude * sin(Double(i) / 60 * 3 * .pi)
                if i == 0 { wave.move(to: CGPoint(x: x, y: y)) } else { wave.addLine(to: CGPoint(x: x, y: y)) }
            }
            context.stroke(wave, with: .color(Brand.accent(scheme).opacity(0.85)), style: StrokeStyle(lineWidth: 3, lineCap: .round))
            // Mond auf der Welle; hell mit dunklem Rand, damit er auch auf Weiss sichtbar ist.
            let x = size.width * fraction
            let y = mid + amplitude * sin(Double(fraction) * 3 * .pi)
            let r: CGFloat = 7
            let disc = Path(ellipseIn: CGRect(x: x - r, y: y - r, width: 2 * r, height: 2 * r))
            context.fill(disc, with: .color(scheme == .dark ? Brand.moon : Brand.nightTop))
            context.stroke(disc, with: .color(Brand.accent(scheme)), lineWidth: 1.5)
        }
        .frame(width: 96, height: 36)
        .accessibilityHidden(true)
    }

    /// Lage des Monds auf der Welle (0–1): vom ersten bis zum letzten Schritt gleichmässig, an den Rändern etwas Luft.
    static func position(step: Int, steps: Int) -> Double {
        guard steps > 1 else { return 0.5 }
        let clamped = Double(min(max(step, 0), steps))
        return 0.1 + 0.8 * clamped / Double(steps)
    }
}
