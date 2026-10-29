import Charts
import SwiftUI

/// Blase einer Auswahl in den Diagrammen oben: Datum und Wert(e) der gewählten Nacht; ein Tipp öffnet die
/// Detailseite dieser Nacht (sichtbarer Pfeil). Steht über der Zeichenfläche, damit sie die Geste nicht verdeckt;
/// ein kleines Dreieck zeigt auf die gewählte Stelle (`fraction` 0–1 der Breite). Wie `ChartBubble` (Android).
struct ChartSelectionBubble: View {
    /// Fertiger Text, z. B. «28. Okt. · 7 Std. 10 Min».
    let text: String
    /// Waagrechte Lage der Auswahl, 0 = links, 1 = rechts.
    let fraction: Double
    /// Öffnet die Detailseite; `nil` = für diese Nacht gibt es keine (Blase ohne Pfeil).
    let onOpen: (() -> Void)?
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Spacer(minLength: 0)
            Button { onOpen?() } label: {
                HStack(spacing: 6) {
                    Text(text).multilineTextAlignment(.leading)
                    Spacer(minLength: 4)
                    if onOpen != nil {
                        Text(L("night_details")).foregroundStyle(.tint)
                        Image(systemName: "chevron.right").foregroundStyle(.tint)
                    }
                }
                .font(Fonts.manrope(13, .semibold, relativeTo: .footnote))
                .padding(.horizontal, 12)
                .frame(minHeight: 44)
                .background(Brand.chipSelected(scheme), in: RoundedRectangle(cornerRadius: 12))
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(onOpen == nil)
            .accessibilityHint(onOpen == nil ? "" : L("night_open"))
            GeometryReader { geo in
                Pointer()
                    .fill(Brand.chipSelected(scheme))
                    .frame(width: 14, height: 7)
                    .position(x: min(max(10, geo.size.width * fraction), geo.size.width - 10), y: 3.5)
            }
            .frame(height: 7)
            .accessibilityHidden(true)
        }
        .frame(minHeight: chartBubbleHeight)
    }
}

/// Höhe der Blase samt Spitze; der Hinweis ohne Auswahl ist gleich hoch, damit nichts springt.
let chartBubbleHeight: CGFloat = 51

/// Ohne Auswahl: «Diagramm antippen oder darüberziehen für Werte.» an der Stelle der Blase.
struct ChartTouchHint: View {
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        Text(L("chart_touch_hint"))
            .font(Fonts.manrope(12, relativeTo: .caption))
            .foregroundStyle(Brand.secondaryText(scheme))
            .frame(maxWidth: .infinity, minHeight: chartBubbleHeight, alignment: .bottomLeading)
            .padding(.bottom, 4)
            .accessibilityHidden(true)
    }
}

/// Farbe der Linie «Wochendurchschnitt»: Nebentext, ≥ 4.5:1 zur Karte und anders als die Ziellinie (Hauptfarbe).
func averageColor(_ scheme: ColorScheme) -> Color { Brand.secondaryText(scheme) }

/// Strich der Linie «Wochendurchschnitt»: gepunktet, damit sie ohne Farbe von der gestrichelten Ziellinie abweicht.
let averageStroke = StrokeStyle(lineWidth: 2, lineCap: .round, dash: [0.5, 4])

/// Zeichen der Linie «Wochendurchschnitt» in der Legende.
struct AverageSymbol: View {
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        Path { p in p.move(to: CGPoint(x: 1, y: 6)); p.addLine(to: CGPoint(x: 15, y: 6)) }
            .stroke(averageColor(scheme), style: averageStroke)
    }
}

/// Dreieck mit der Spitze nach unten.
private struct Pointer: Shape {
    func path(in rect: CGRect) -> Path {
        Path { p in
            p.move(to: CGPoint(x: rect.minX, y: rect.minY))
            p.addLine(to: CGPoint(x: rect.maxX, y: rect.minY))
            p.addLine(to: CGPoint(x: rect.midX, y: rect.maxY))
            p.closeSubpath()
        }
    }
}

/// Lage des Elements `index` von `count` gleich breiten Plätzen (Mitte), 0–1.
func slotFraction(_ index: Int, of count: Int) -> Double {
    count <= 0 ? 0.5 : (Double(index) + 0.5) / Double(count)
}

/// Haltedauer, bevor Ziehen über ein Diagramm auswählt: so bleiben Blättern (senkrecht) und Wischen zwischen den
/// Karten bzw. Nächten (seitlich) frei; ein kurzer Tipp wählt sofort.
let chartHoldSeconds = 0.2

extension View {
    /// Auswahl in einem Diagramm mit `chartXSelection` bzw. `chartYSelection` (`vertical`): Antippen wählt die Stelle,
    /// kurz halten und ziehen fährt über die Werte. Ersetzt die Standardgeste, die in seitlich blätternden Ansichten
    /// mit dem Wischen streitet.
    func chartScrub(vertical: Bool = false) -> some View {
        chartGesture { proxy in
            LongPressGesture(minimumDuration: chartHoldSeconds)
                .sequenced(before: DragGesture(minimumDistance: 0))
                .onChanged { value in
                    if case .second(true, let drag?) = value { select(proxy, at: drag.location, vertical: vertical) }
                }
                .exclusively(before: SpatialTapGesture().onEnded { select(proxy, at: $0.location, vertical: vertical) })
        }
    }
}

/// Wählt den Wert an `location` (Koordinaten des Diagramms) auf der x- bzw. y-Achse.
private func select(_ proxy: ChartProxy, at location: CGPoint, vertical: Bool) {
    if vertical { proxy.selectYValue(at: location.y) } else { proxy.selectXValue(at: location.x) }
}
