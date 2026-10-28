import Foundation
import PhaseWakeCore
import WatchConnectivity

/// Verbindung zum iPhone: Wecker kommen als Anwendungskontext, Nächte gehen als Benutzerdaten,
/// Weck-Ereignisse als Nachricht (nur wenn das iPhone erreichbar ist).
final class WatchConnectivityBridge: NSObject, WCSessionDelegate, @unchecked Sendable {
    static let shared = WatchConnectivityBridge()

    func activate() {
        guard WCSession.isSupported() else { return }
        WCSession.default.delegate = self
        WCSession.default.activate()
    }

    func publish(_ snapshot: AlarmSnapshot) {
        guard WCSession.default.activationState == .activated else { return }
        try? WCSession.default.updateApplicationContext([
            SyncKeys.alarms: Codec.encodeAlarms(snapshot.alarms),
            SyncKeys.updatedAt: snapshot.updatedAt,
        ])
    }

    func send(night: NightRecord) {
        WCSession.default.transferUserInfo([SyncKeys.night: Codec.encodeNight(night)])
    }

    func send(command: String, alarmId: String = "") {
        guard WCSession.default.isReachable else { return }
        WCSession.default.sendMessage([SyncKeys.command: command, SyncKeys.alarms: alarmId], replyHandler: nil)
    }

    // MARK: WCSessionDelegate

    func session(_ session: WCSession, activationDidCompleteWith state: WCSessionActivationState, error: Error?) {
        if state == .activated { receive(session.receivedApplicationContext) }
    }

    func session(_ session: WCSession, didReceiveApplicationContext context: [String: Any]) {
        receive(context)
    }

    func session(_ session: WCSession, didReceiveMessage message: [String: Any]) {
        guard let command = message[SyncKeys.command] as? String else { return }
        Task { @MainActor in
            switch command {
            case SyncKeys.stop: SmartAlarm.shared.dismiss(notifyPhone: false)
            case SyncKeys.snooze: SmartAlarm.shared.snooze()
            case SyncKeys.clearHistory: WatchModel.shared.storage.clearNights()
            default: break
            }
        }
    }

    private func receive(_ context: [String: Any]) {
        guard let text = context[SyncKeys.alarms] as? String,
              let updatedAt = context[SyncKeys.updatedAt] as? Int64,
              let alarms = try? Codec.decodeAlarms(text) else { return }
        Task { @MainActor in WatchModel.shared.applyRemote(AlarmSnapshot(alarms: alarms, updatedAt: updatedAt)) }
    }
}
