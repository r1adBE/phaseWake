import Foundation
import PhaseWakeCore
import UserNotifications

/// Mitteilungen der Uhr-App. Ohne diesen Delegate zeigt watchOS eine Mitteilung bei offener App gar nicht – ausgerechnet
/// der Rückfall-Wecker (`phasewake.fallback…`) bliebe dann stumm, wenn die Sitzung nicht geweckt hat.
final class WatchNotificationRouter: NSObject, UNUserNotificationCenterDelegate, @unchecked Sendable {
    static let shared = WatchNotificationRouter()

    /// Ob die Mitteilung ein Rückfall-Wecker ist (echter Wecker, Probewecker/Powernap oder nach abgebrochener Sitzung).
    static func isFallback(_ id: String) -> Bool { id.hasPrefix(SmartAlarm.fallbackId) }

    /// Bei offener App: Ein Rückfall erscheint mit Ton und weckt direkt (`SmartAlarm.fallbackArrived`). Alle anderen
    /// Mitteilungen bleiben wie bisher stumm, solange die App offen ist.
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async
        -> UNNotificationPresentationOptions {
        let id = notification.request.identifier
        guard Self.isFallback(id) else { return [] }
        await MainActor.run { SmartAlarm.shared.fallbackArrived(id) }
        return [.banner, .sound]
    }

    /// Tipp auf einen Rückfall: Weckbildschirm mit Stopp und Schlummern, falls die Sitzung nicht geweckt hat. Ein Tipp
    /// auf das Wecken nach abgebrochener Sitzung heisst wach: Der Rest seiner Kette fällt weg.
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        let id = response.notification.request.identifier
        guard Self.isFallback(id), response.actionIdentifier == UNNotificationDefaultActionIdentifier else { return }
        await MainActor.run {
            SmartAlarm.shared.fallbackArrived(id)
            if NotificationChain.baseId(id) == SmartAlarm.interruptedId { SmartAlarm.shared.removeInterrupted() }
        }
    }
}
