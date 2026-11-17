import SwiftUI
import UIKit

/// Solange die App nicht aktiv ist (App-Umschalter, Wechsel in den Hintergrund), liegt über allem – auch
/// über Sheets und Vollbild-Ansichten – eine Abdeckung mit Nachthimmel und Logo. So zeigt das Vorschaubild des Systems
/// keine Schlafdaten. Eigenes Fenster über der App, weil eine Überlagerung in SwiftUI Sheets nicht abdeckt.
@MainActor
enum PrivacyCover {
    private static var window: UIWindow?

    /// Zeigt die Abdeckung bei `phase != .active`, nimmt sie bei `.active` weg.
    static func update(_ phase: ScenePhase) {
        guard phase != .active else {
            window?.isHidden = true
            window = nil
            return
        }
        guard window == nil, let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first else { return }
        let cover = UIWindow(windowScene: scene)
        cover.windowLevel = UIWindow.Level(rawValue: UIWindow.Level.alert.rawValue + 1)
        cover.rootViewController = UIHostingController(rootView: PrivacyCoverView())
        cover.isHidden = false
        window = cover
    }

    /// Ob die Abdeckung gerade liegt (für Tests).
    static var isShown: Bool { window != nil }
}

/// Nachthimmel mit dem Logo in der Mitte, ohne Text und ohne Daten.
struct PrivacyCoverView: View {
    var body: some View {
        ZStack {
            LinearGradient(colors: [Brand.nightTop, Brand.nightBottom], startPoint: .top, endPoint: .bottom)
            LogoMark().frame(width: 96, height: 96)
        }
        .ignoresSafeArea()
        .accessibilityHidden(true)
    }
}
