import PhaseWakeCore
import SwiftUI

/// Einstieg der iPhone-App.
@main
struct PhaseWakeApp: App {
    @State private var model = PhoneModel.shared
    @AppStorage("themeMode") private var themeMode = ThemeMode.system

    init() {
        Fonts.register()
        PhoneConnectivity.shared.activate()
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .preferredColorScheme(themeMode.colorScheme)
                .task { await model.backup.reschedule(model.alarms) }
        }
    }
}

/// Wie die App aussehen soll; Standard folgt dem System.
enum ThemeMode: String, CaseIterable, Identifiable {
    case system, light, dark
    var id: String { rawValue }
    var colorScheme: ColorScheme? {
        switch self {
        case .system: nil
        case .light: .light
        case .dark: .dark
        }
    }
}

/// Reiter: Wecker, Verlauf, Einstellungen.
struct RootView: View {
    var body: some View {
        TabView {
            HomeView().tabItem { Label(L("nav_alarms"), systemImage: "alarm") }
            HistoryView().tabItem { Label(L("nav_history"), systemImage: "chart.bar") }
            SettingsView().tabItem { Label(L("nav_settings"), systemImage: "slider.horizontal.3") }
        }
        .tint(Brand.accentDark)
    }
}
