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
            let snapshot = PhaseWakeStorage.watchShared.snapshot()
            context[SyncKeys.alarms] = Codec.encodeAlarms(snapshot.alarms)
            context[SyncKeys.updatedAt] = snapshot.updatedAt
        }
        sendContext()
    }

    /// Vor der Aktivierung (z. B. beim Hintergrundstart für eine Sitzung) bleibt der Stand gemerkt und
    /// geht nach `activationDidComplete` nach.
    private func sendContext() {
        guard WCSession.default.activationState == .activated, !context.isEmpty else { return }
        try? WCSession.default.updateApplicationContext(context)
    }

    func send(night: NightRecord) {
        WCSession.default.transferUserInfo([SyncKeys.night: Codec.encodeNight(night)])
    }

    /// Sofort, wenn das iPhone erreichbar ist; sonst zugestellt, sobald es das wieder ist.
    /// Mit Zielzeit der Nacht und Sendezeit: Das iPhone verwirft ein verspätetes «klingeln» und setzt
    /// die Sicherung genau für diese Nacht aus.
    func send(command: String, alarmId: String = "", target: Date? = nil, snoozesLeft: Int? = nil) {
        var payload: [String: Any] = [SyncKeys.command: command, SyncKeys.alarms: alarmId,
                                      SyncKeys.sentAt: Int64(Date().timeIntervalSince1970 * 1000)]
        if let target { payload[SyncKeys.target] = Int64(target.timeIntervalSince1970 * 1000) }
        if let snoozesLeft { payload[SyncKeys.snoozesLeft] = Int64(snoozesLeft) }
        if WCSession.default.isReachable {
            WCSession.default.sendMessage(payload, replyHandler: nil) { _ in WCSession.default.transferUserInfo(payload) }
        } else {
            WCSession.default.transferUserInfo(payload)
        }
    }

    // MARK: WCSessionDelegate

    func session(_ session: WCSession, activationDidCompleteWith state: WCSessionActivationState, error: Error?) {
        guard state == .activated else { return }
        receive(session.receivedApplicationContext)
        // `context` wird auf dem Main-Thread geschrieben.
        DispatchQueue.main.async { self.sendContext() }
    }

    func session(_ session: WCSession, didReceiveApplicationContext context: [String: Any]) {
        receive(context)
    }

    func session(_ session: WCSession, didReceiveUserInfo userInfo: [String: Any] = [:]) {
        self.session(session, didReceiveMessage: userInfo)
    }

    func session(_ session: WCSession, didReceiveMessage message: [String: Any]) {
        guard let command = message[SyncKeys.command] as? String else { return }
        // Verspätet zugestelltes Stopp oder Schlummern vom iPhone träfe sonst eine spätere Nacht.
        if command != SyncKeys.clearHistory, let sentAt = message[SyncKeys.sentAt] as? Int64,
           Int64(Date().timeIntervalSince1970 * 1000) - sentAt > SyncKeys.maxRingAgeMs { return }
        Task { @MainActor in
            switch command {
            case SyncKeys.stop: SmartAlarm.shared.dismiss(notifyPhone: false)
            case SyncKeys.snooze: SmartAlarm.shared.snoozeFromPhone()
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
