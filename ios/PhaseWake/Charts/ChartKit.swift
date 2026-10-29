import PhaseWakeCore
import SwiftUI

/// Farben der Diagramme, gleich wie die Markenfarben auf Android (`BrandColors`).
struct ChartPalette {
    let active: Color, quiet: Color, track: Color, wave: Color, echo: Color, dot: Color

    init(_ scheme: ColorScheme) {
        if scheme == .dark {
            active = Color(hex: 0xB9AEFF); quiet = Color(hex: 0x4A43A0); track = Color(hex: 0x2A2D55)
            wave = Color(hex: 0xB9AEFF); echo = Color(hex: 0x7B6CF6); dot = Brand.moon
        } else {
            active = Color(hex: 0x7B6CF6); quiet = Color(hex: 0xDAD4FA); track = Color(hex: 0xECE8F5)
            wave = Color(hex: 0x7B6CF6); echo = Color(hex: 0xB3A9F7); dot = Color(hex: 0x3B3486)
        }
    }
}

/// Höhe der Zeichenfläche, gleich auf allen Karten.
let chartHeight: CGFloat = 168

// MARK: Hilfen

/// Nachtdatum «yyyy-MM-dd» als Datum des Morgens (wie die Nächte im Verlauf).
func morning(_ night: String) -> Date {
    let parts = NightDate.adding(1, to: night).split(separator: "-").compactMap { Int($0) }
    guard parts.count == 3 else { return Date() }
    return Calendar.current.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2], hour: 12)) ?? Date()
}

/// «28. Okt.»
func longDate(_ night: String) -> String { morning(night).formatted(.dateTime.day().month(.abbreviated)) }

/// «28.10.»
func shortDate(_ night: String) -> String { morning(night).formatted(.dateTime.day().month(.defaultDigits)) }

/// Minuten ab Mittag als Uhrzeit.
func clock(_ minutesFromNoon: Int) -> String {
    let m = ((720 + minutesFromNoon) % 1440 + 1440) % 1440
    return Format.time(hour: m / 60, minute: m % 60)
}

extension View {
    /// Kurz einblenden; mit «Bewegung reduzieren» sofort sichtbar.
    func chartFadeIn() -> some View { modifier(ChartFadeIn()) }
}

struct ChartFadeIn: ViewModifier {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var shown = false

    func body(content: Content) -> some View {
        content
            .opacity(shown ? 1 : 0)
            .onAppear {
                if reduceMotion { shown = true } else { withAnimation(.easeOut(duration: 0.3)) { shown = true } }
            }
    }
}

/// Karte einer Seite: Titel, eine Zeile Fazit, Diagramm.
struct ChartCard<Content: View, Trailing: View>: View {
    let title: String
    let takeaway: String?
    @ViewBuilder var trailing: () -> Trailing
    @ViewBuilder var content: () -> Content
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(title).font(Fonts.manrope(15, .semibold, relativeTo: .headline))
                Spacer()
                trailing()
            }
            if let takeaway {
                Text(takeaway).font(Fonts.manrope(15, relativeTo: .body)).padding(.bottom, 6)
            }
            content()
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .topLeading)
        .background(Brand.card(scheme), in: RoundedRectangle(cornerRadius: Brand.cardRadius))
    }
}

extension ChartCard where Trailing == EmptyView {
    init(title: String, takeaway: String?, @ViewBuilder content: @escaping () -> Content) {
        self.init(title: title, takeaway: takeaway, trailing: { EmptyView() }, content: content)
    }
}

/// Legende: Zeichen und Text; für VoiceOver steht alles schon in der Beschreibung.
struct ChartLegend: View {
    let items: [(symbol: AnyView, text: String)]

    var body: some View {
        // Nebeneinander, wenn es passt, sonst untereinander.
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 12) { entries }
            VStack(alignment: .leading, spacing: 4) { entries }
        }
        .font(Fonts.manrope(11, .semibold, relativeTo: .caption2))
        .foregroundStyle(.secondary)
        .padding(.top, 6)
        .accessibilityHidden(true)
    }
}

extension ChartLegend {
    @ViewBuilder var entries: some View {
        ForEach(items.indices, id: \.self) { i in
            HStack(spacing: 4) {
                items[i].symbol.frame(width: 16, height: 12)
                Text(items[i].text)
            }
        }
    }
}

struct DashSymbol: View {
    let color: Color
    var body: some View {
        Path { p in p.move(to: CGPoint(x: 0, y: 6)); p.addLine(to: CGPoint(x: 16, y: 6)) }
            .stroke(color, style: StrokeStyle(lineWidth: 1.5, dash: [4, 3]))
    }
}
