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
        // Bestehende Installation mit Weckern oder Verlauf: gilt als eingerichtet, keine Ersteinrichtung (wie Android).
        if !PhoneModel.shared.alarms.isEmpty || !PhoneModel.shared.nights.isEmpty {
            UserDefaults.standard.set(true, forKey: OnboardingView.doneKey)
        }
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .preferredColorScheme(themeMode.colorScheme)
                // Start: Protokoll, Feiertage laden, Sicherung, Erinnerungen und laufende Probewecker.
                .task { await model.startUp() }
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
    @Environment(\.colorScheme) private var scheme
    @AppStorage(OnboardingView.doneKey) private var onboardingDone = false

    var body: some View {
        @Bindable var model = model
        TabView(selection: $model.selectedTab) {
            HomeView().tabItem { Label(L("nav_alarms"), systemImage: "alarm") }.tag(AppTab.alarms)
            HistoryView().tabItem { Label(L("nav_history"), systemImage: "chart.bar") }.tag(AppTab.history)
            SettingsView().tabItem { Label(L("nav_settings"), systemImage: "slider.horizontal.3") }.tag(AppTab.settings)
        }
        .tint(Brand.accent(scheme))
        .fullScreenCover(isPresented: Binding(get: { !onboardingDone }, set: { onboardingDone = !$0 })) {
            OnboardingView().environment(model)
        }
        .onChange(of: scenePhase) { _, phase in
            guard phase == .active else { return }
            // Beim Öffnen: alte Ausschnitte weg, Wochenrückblick mit dem neuesten Stand.
            model.recorder.expire()
            model.becameActive()
            Task { await model.replanWeekly() }
        }
    }
}
