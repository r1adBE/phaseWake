import PhaseWakeCore
import SwiftUI
import UserNotifications

/// Einstieg der iPhone-App.
@main
struct PhaseWakeApp: App {
    @State private var model = PhoneModel.shared
    @AppStorage("themeMode") private var themeMode = ThemeMode.system

    init() {
        Fonts.register()
        PhoneConnectivity.shared.activate()
        UNUserNotificationCenter.current().delegate = NotificationRouter.shared
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .preferredColorScheme(themeMode.colorScheme)
                .task {
                    await model.backup.reschedule(model.alarms)
                    await model.replanBedtime()
                    await model.replanWeekly()
                }
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
    @Environment(PhoneModel.self) private var model
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        @Bindable var model = model
        TabView(selection: $model.selectedTab) {
            HomeView().tabItem { Label(L("nav_alarms"), systemImage: "alarm") }.tag(AppTab.alarms)
            HistoryView().tabItem { Label(L("nav_history"), systemImage: "chart.bar") }.tag(AppTab.history)
            SettingsView().tabItem { Label(L("nav_settings"), systemImage: "slider.horizontal.3") }.tag(AppTab.settings)
        }
        .tint(Brand.accentDark)
        .onChange(of: scenePhase) { _, phase in
            guard phase == .active else { return }
            // Beim Öffnen: alte Ausschnitte weg, Wochenrückblick mit dem neuesten Stand.
            model.recorder.expire()
            Task { await model.replanWeekly() }
        }
    }
}
