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

    /// Letzter Stand, der als Anwendungskontext geht (Wecker und Scharf-Status gemeinsam).
    private var context: [String: Any] = [:]

    func publish(_ snapshot: AlarmSnapshot) {
        context[SyncKeys.alarms] = Codec.encodeAlarms(snapshot.alarms)
        context[SyncKeys.updatedAt] = snapshot.updatedAt
        sendContext()
    }

    /// Für welche Zielzeit die Uhr scharf ist (Sitzung geplant oder laufend); 0 = nicht scharf.
    func publish(armedTarget: Date?) {
        context[SyncKeys.armedTarget] = Int64((armedTarget?.timeIntervalSince1970 ?? 0) * 1000)
        if context[SyncKeys.alarms] == nil {
            let snapshot = PhaseWakeStorage().snapshot()
            context[SyncKeys.alarms] = Codec.encodeAlarms(snapshot.alarms)
            context[SyncKeys.updatedAt] = snapshot.updatedAt
        }
        sendContext()
    }

    private func sendContext() {
        guard WCSession.default.activationState == .activated else { return }
        try? WCSession.default.updateApplicationContext(context)
    }

    func send(night: NightRecord) {
        WCSession.default.transferUserInfo([SyncKeys.night: Codec.encodeNight(night)])
    }

    /// Sofort, wenn das iPhone erreichbar ist; sonst zugestellt, sobald es das wieder ist.
    func send(command: String, alarmId: String = "") {
        let payload: [String: Any] = [SyncKeys.command: command, SyncKeys.alarms: alarmId]
        if WCSession.default.isReachable {
            WCSession.default.sendMessage(payload, replyHandler: nil) { _ in WCSession.default.transferUserInfo(payload) }
        } else {
            WCSession.default.transferUserInfo(payload)
        }
    }

    // MARK: WCSessionDelegate

    func session(_ session: WCSession, activationDidCompleteWith state: WCSessionActivationState, error: Error?) {
        if state == .activated { receive(session.receivedApplicationContext) }
    }

    func session(_ session: WCSession, didReceiveApplicationContext context: [String: Any]) {
        receive(context)
    }

    func session(_ session: WCSession, didReceiveUserInfo userInfo: [String: Any] = [:]) {
        self.session(session, didReceiveMessage: userInfo)
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
