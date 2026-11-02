import PhaseWakeCore
import SwiftUI
import UserNotifications
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
                // Lavendel wie auf dem Handy und der Galaxy Watch.
                .tint(Brand.wave)
        }
    }
}

/// Nimmt geplante Smart-Alarm-Sitzungen entgegen, wenn das System die App dafür startet.
final class AppDelegate: NSObject, WKApplicationDelegate {
    func applicationDidFinishLaunching() {
        // Vor dem Ende des Starts setzen: Sonst geht ein Tipp, der die App startet, verloren.
        UNUserNotificationCenter.current().delegate = WatchNotificationRouter.shared
        WatchConnectivityBridge.shared.activate()
    }

    /// Der Delegate muss gesetzt sein, bevor diese Methode zurückkehrt; sonst beendet das System die Sitzung.
    func handle(_ extendedRuntimeSession: WKExtendedRuntimeSession) {
        MainActor.assumeIsolated { SmartAlarm.shared.adopt(extendedRuntimeSession) }
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
