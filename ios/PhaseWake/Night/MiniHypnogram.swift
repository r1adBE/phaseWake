import PhaseWakeCore
import SwiftUI

/// Kompaktes Schlafprofil für die Nachtkarte im Verlauf: vier Stufen (Wach oben, Tief unten) mit abgerundeten
/// Blöcken in den Phasenfarben und dünnen Verbindungen – scharf ohne Schein und Verlauf, Kanten auf ganzen Pixeln,
/// kurze Blöcke mindestens 1 Pixel breit und nie aufgeblasen, Ecken nur ab `roundMin` gerundet. Nur Bild: Die
/// Summenzeile darunter trägt die Information für Bildschirmleser und ohne Farbe (Stufenhöhe und Text). Wie
/// `MiniHypnogram` (Android).
struct MiniHypnogram: View {
    /// Bekannte Phasen in Minuten ab Beginn.
    let steps: [HypnoStep]
    /// Länge der Nacht in Minuten (Breite der Zeichnung).
    let totalMinutes: Double
    /// Weckmoment in Minuten ab Beginn; `nil` bei Nächten ohne Phase-Wake-Wecker.
    var wokeAt: Double?
    @Environment(\.colorScheme) private var scheme
    @Environment(\.displayScale) private var displayScale

    /// Abstand zwischen den Stufen in Punkten.
    private static let rowGap: CGFloat = 3
    /// Mindestbreite eines Blocks für gerundete Ecken; schmalere Blöcke bleiben eckig (wie Android).
    private static let roundMin: CGFloat = 6

    var body: some View {
        Canvas { context, size in
            let total = max(1, totalMinutes)
            let rowHeight = (size.height - 3 * Self.rowGap) / 4
            func rowY(_ level: Int) -> CGFloat { CGFloat(level) * (rowHeight + Self.rowGap) }
            for level in 0..<4 {
                let row = CGRect(x: 0, y: rowY(level), width: size.width, height: rowHeight)
                context.fill(Path(roundedRect: row, cornerRadius: 3), with: .color(StageColors.track(scheme)))
            }
            let scale = max(1, displayScale)
            let pixel = 1 / scale
            // Auf ganze Pixel gesetzt, damit keine Kante verschwimmt.
            func snap(_ v: CGFloat) -> CGFloat { (v * scale).rounded() / scale }
            var previous: (x: CGFloat, level: Int)?
            for step in steps {
                guard let level = ChartData.level(step.stage) else { continue }
                let x0 = snap(size.width * step.fromMinute / total)
                let w = max(pixel, snap(size.width * step.toMinute / total) - x0)
                let rect = CGRect(x: x0, y: snap(rowY(level)), width: w, height: snap(rowHeight))
                if let previous, abs(previous.x - x0) < 1, previous.level != level {
                    // Haarlinie auf der Pixelmitte, ohne Schein.
                    var link = Path()
                    link.move(to: CGPoint(x: x0 + pixel / 2, y: rowY(previous.level) + rowHeight / 2))
                    link.addLine(to: CGPoint(x: x0 + pixel / 2, y: rowY(level) + rowHeight / 2))
                    context.stroke(link, with: .color(StageColors.link(scheme)), lineWidth: pixel)
                }
                let radius: CGFloat = w >= Self.roundMin ? min(3, w / 4) : 0
                context.fill(Path(roundedRect: rect, cornerRadius: radius), with: .color(StageColors.color(step.stage, scheme)))
                previous = (snap(size.width * step.toMinute / total), level)
            }
            if let wokeAt {
                let x = size.width * min(max(0, wokeAt), total) / total
                let d: CGFloat = 8
                let cx = min(max(d / 2, x), size.width - d / 2)
                var line = Path()
                line.move(to: CGPoint(x: cx, y: d / 2))
                line.addLine(to: CGPoint(x: cx, y: size.height))
                context.stroke(line, with: .color(StageColors.marker(scheme)), lineWidth: 1.5)
                context.fill(Path(ellipseIn: CGRect(x: cx - d / 2, y: 0, width: d, height: d)), with: .color(StageColors.marker(scheme)))
            }
        }
        .accessibilityHidden(true)
    }
}
