import PhaseWakeCore
import SwiftUI
import UIKit

// Seiten im Stil der Startseite (Verlauf, Detail, Einstellungen, Editor, Einrichtung, Diagnose …): derselbe Himmel,
// dieselben Karten. Wie `SkyPage.kt` auf Android.

extension Brand {
    /// Karten der Seiten ausser der Startseite: dunkel die Diagrammkarte #161B3A mit 72 % als dichteres Glas – darauf
    /// behalten Phasenfarben und Ränder auch über dem Abendrot ≥ 3:1 und Schrift ≥ 4.5:1 (auf Android mit
    /// SkyPageContrastTest geprüft, gleiche Werte); hell weiss.
    static func panel(_ scheme: ColorScheme) -> Color { scheme == .dark ? Color(hex: 0x161B3A, opacity: 0.72) : .white }

    /// Hauptfarbe, die sich nach dem Modus ihrer Umgebung richtet: hell Indigo, dunkel Lavendel. Im hellen Modus steht
    /// der Kopf (Navigationsleiste) im Nachthimmel und ist dunkel – Zurück und Knöpfe dort werden so Lavendel (≥ 4.5:1).
    static let accentAdaptive = Color(UIColor { traits in
        traits.userInterfaceStyle == .dark
            ? UIColor(red: CGFloat(0xB9) / 255, green: CGFloat(0xAE) / 255, blue: CGFloat(0xFF) / 255, alpha: 1)
            : UIColor(red: CGFloat(0x3B) / 255, green: CGFloat(0x34) / 255, blue: CGFloat(0x86) / 255, alpha: 1)
    })
}

/// Ob Karten die Fläche der Seiten (``Brand/panel(_:)``) statt der Startseite (``Brand/card(_:)``) tragen.
private struct SkyPanelKey: EnvironmentKey {
    static let defaultValue = false
}

extension EnvironmentValues {
    var skyPanel: Bool {
        get { self[SkyPanelKey.self] }
        set { self[SkyPanelKey.self] = newValue }
    }
}

/// Fläche einer Karte: auf den Seiten ``Brand/panel(_:)``, auf der Startseite ``Brand/card(_:)``.
struct CardFill: View {
    @Environment(\.colorScheme) private var scheme
    @Environment(\.skyPanel) private var panel

    var body: some View { panel ? Brand.panel(scheme) : Brand.card(scheme) }
}

/// Seite im Stil der Startseite: dunkel ganzflächig der Nachthimmel mit der Stimmung je Tageszeit, hell die helle Fläche
/// und der Kopf (Navigationsleiste) im Nachthimmel mit heller Schrift. Listen und Formulare ohne eigenen Grund, ihre
/// Zeilen in der Kartenfläche (``skyRows()``).
struct SkyPageStyle: ViewModifier {
    @Environment(PhoneModel.self) private var model
    @Environment(\.colorScheme) private var scheme

    func body(content: Content) -> some View {
        let dark = scheme == .dark
        content
            .scrollContentBackground(.hidden)
            .background {
                if dark {
                    TimelineView(.everyMinute) { c in NightSky(mood: .at(now: c.date, plan: model.nextPlan(now: c.date))) }
                } else {
                    Brand.paper.ignoresSafeArea()
                }
            }
            // Dunkel wie gewohnt (beim Scrollen die Leiste), hell immer der Himmel.
            .toolbarBackground(dark ? AnyShapeStyle(Material.bar) : AnyShapeStyle(header), for: .navigationBar)
            .toolbarBackground(dark ? .automatic : .visible, for: .navigationBar)
            .toolbarColorScheme(dark ? nil : .dark, for: .navigationBar)
            .tint(Brand.accentAdaptive)
            .environment(\.skyPanel, true)
    }

    /// Hell: Nachthimmel im Kopf, mit der Stimmung jetzt (Verlauf von oben nach unten wie auf der Startseite).
    private var header: LinearGradient {
        let mood = SkyMood.at(now: Date(), plan: model.nextPlan())
        return LinearGradient(colors: [mood.top, Brand.nightBottom], startPoint: .top, endPoint: .bottom)
    }
}

extension View {
    /// Himmel und Kopf wie auf der Startseite (``SkyPageStyle``).
    func skyPage() -> some View { modifier(SkyPageStyle()) }

    /// Zeilen einer Liste oder eines Formulars in der Kartenfläche der Seiten.
    func skyRows() -> some View { listRowBackground(CardFill()) }
}
