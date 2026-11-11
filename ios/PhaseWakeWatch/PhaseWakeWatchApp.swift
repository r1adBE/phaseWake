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
        // Lernphase: Rekorder am Abend starten, Nacht nach dem Wecken auswerten (`LearnRecorder`). Die Uhr stellt nur
        // diese eine Hintergrund-Aktualisierung.
        // Dabei auch neu planen (Audit AP-2): Rückfälle der nächsten Nächte nachführen, auch wenn die App nie offen ist.
        .backgroundTask(.appRefresh) { _ in
            await MainActor.run { WatchModel.shared.reschedule(recorder: false) }
            await LearnRecorder.shared.refreshTask()
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

    /// Jedes Aktivwerden, auch ein Kaltstart über die Erinnerung am Abend, bei dem `scenePhase` der Startseite den
    /// Wechsel nicht sicher meldet (Audit AP-5): zuerst gemerkte, nicht mehr gültige Sitzungen beenden (nur bei aktiver
    /// App erlaubt), dann neu planen – nur bei aktiver App lässt sich die Smart-Alarm-Sitzung planen.
    func applicationDidBecomeActive() {
        MainActor.assumeIsolated {
            SmartAlarm.shared.invalidateStaleSessions()
            WatchModel.shared.reschedule()
        }
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
