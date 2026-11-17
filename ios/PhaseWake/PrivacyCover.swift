import SwiftUI
import UIKit

/// Solange die App nicht aktiv ist (App-Umschalter, Wechsel in den Hintergrund), liegt über allem – auch
/// über Sheets und Vollbild-Ansichten – eine Abdeckung mit dem Logo. So zeigt das Vorschaubild des Systems keine
/// Schlafdaten. Eigenes Fenster über der App, weil eine Überlagerung in SwiftUI Sheets nicht abdeckt.
/// Während einer eigenen Abfrage (``PermissionPrompt``) bleibt «inaktiv» ohne Abdeckung: Der erklärende Text hinter
/// der Abfrage bleibt sichtbar. Hell/dunkel wie die App gerade aussieht (Design, «Abends dunkel ab»).
@MainActor
enum PrivacyCover {
    private static var window: UIWindow?

    /// Zeigt die Abdeckung bei `phase != .active`, nimmt sie bei `.active` weg. «Inaktiv» während einer eigenen
    /// Abfrage deckt nicht ab; der Hintergrund immer.
    static func update(_ phase: ScenePhase) {
        guard phase != .active, !(phase == .inactive && PermissionPrompt.isAsking) else {
            window?.isHidden = true
            window = nil
            return
        }
        guard window == nil,
              let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first else {
            return
        }
        let cover = UIWindow(windowScene: scene)
        cover.windowLevel = UIWindow.Level(rawValue: UIWindow.Level.alert.rawValue + 1)
        // Darstellung des App-Fensters übernehmen (dort setzt SwiftUI Design und «Abends dunkel ab»).
        let app = scene.windows.first { $0.windowLevel == .normal }
        cover.overrideUserInterfaceStyle = app?.traitCollection.userInterfaceStyle ?? .unspecified
        cover.rootViewController = UIHostingController(rootView: PrivacyCoverView())
        cover.isHidden = false
        window = cover
    }

    /// Ob die Abdeckung gerade liegt (für Tests).
    static var isShown: Bool { window != nil }

    /// Hell oder dunkel der liegenden Abdeckung (für Tests).
    static var style: UIUserInterfaceStyle? { window?.overrideUserInterfaceStyle }
}

/// Logo in der Mitte, ohne Text und ohne Daten: dunkel auf dem Nachthimmel, hell auf Creme mit dunklem Mond und
/// Welle in Lavendel (wie die Einrichtung).
struct PrivacyCoverView: View {
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        ZStack {
            if scheme == .dark {
                LinearGradient(colors: [Brand.nightTop, Brand.nightBottom], startPoint: .top, endPoint: .bottom)
                LogoMark().frame(width: 96, height: 96)
            } else {
                Brand.paper
                LogoMark(moon: Brand.ink, wave: Brand.lavenderDeep).frame(width: 96, height: 96)
            }
        }
        .ignoresSafeArea()
        .accessibilityHidden(true)
    }
}
