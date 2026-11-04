import Foundation
import PhaseWakeCore
import UserNotifications

/// Mitteilungen anfragen und stellen, mit einer Zeile im Diagnose-Protokoll, wenn es scheitert (iPhone und Uhr). Für
/// dich ändert sich nichts; das Protokoll zeigt nur, warum ein Wecker per Mitteilung fehlen könnte. Ohne Inhalte der
/// Mitteilung, nur Herkunft (`source`) und Fehlercode.
@MainActor
enum NotificationLog {
    /// Ablehnung schon protokolliert (einmal je Start der App, sonst stünde sie bei jedem Neuplanen da).
    private static var deniedLogged = false

    /// Fragt die Erlaubnis für Mitteilungen an (bei schon entschiedener Erlaubnis ohne Rückfrage). Ein Fehler steht immer
    /// im Protokoll, eine Ablehnung einmal je Start. - Returns: ob Mitteilungen erlaubt sind.
    @discardableResult
    static func requestPermission(_ source: String) async -> Bool {
        do {
            let granted = try await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])
            if !granted, !deniedLogged {
                deniedLogged = true
                DiagStore.shared.log(.error, "\(source) notifications not allowed")
            }
            return granted
        } catch {
            DiagStore.shared.log(.error, "\(source) notification permission failed \((error as NSError).code)")
            return false
        }
    }

    /// Stellt `request`; scheitert es, eine Zeile im Protokoll.
    static func add(_ request: UNNotificationRequest, _ source: String) async {
        do {
            try await UNUserNotificationCenter.current().add(request)
        } catch {
            DiagStore.shared.log(.error, "\(source) notification not set \((error as NSError).code)")
        }
    }

    /// Protokolliert eine gescheiterte Anfrage einer anderen Erlaubnis (Gesundheitsdaten, AlarmKit).
    nonisolated static func permissionFailed(_ what: String, _ error: Error) {
        DiagStore.shared.log(.error, "\(what) permission failed \((error as NSError).code)")
    }
}
