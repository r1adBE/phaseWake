import PhaseWakeCore
import SwiftUI
import WatchKit

/// Einstieg der Uhr-App.
@main
struct PhaseWakeWatchApp: App {
    @WKApplicationDelegateAdaptor(AppDelegate.self) private var delegate

    init() { Fonts.register() }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(WatchModel.shared)
        }
    }
}

/// Nimmt geplante Smart-Alarm-Sitzungen entgegen, wenn das System die App dafür startet.
final class AppDelegate: NSObject, WKApplicationDelegate {
    func applicationDidFinishLaunching() {
        WatchConnectivityBridge.shared.activate()
    }

    func handle(_ extendedRuntimeSession: WKExtendedRuntimeSession) {
        Task { @MainActor in SmartAlarm.shared.adopt(extendedRuntimeSession) }
    }
}

/// Zeigt den Weckbildschirm, solange geweckt wird, sonst den Startbildschirm.
struct RootView: View {
    @Environment(WatchModel.self) private var model

    var body: some View {
        if SmartAlarm.shared.phase.isRinging || SmartAlarm.shared.phase.isSnoozed {
            RingView()
        } else {
            HomeView()
        }
    }
}
