import Foundation
import PhaseWakeCore
import WatchConnectivity

/// Verbindung zur Uhr: Wecker als Anwendungskontext, Nächte als Benutzerdaten, Weck-Ereignisse als Nachricht.
final class PhoneConnectivity: NSObject, WCSessionDelegate, @unchecked Sendable {
    static let shared = PhoneConnectivity()

    func activate() {
        guard WCSession.isSupported() else { return }
        WCSession.default.delegate = self
        WCSession.default.activate()
    }

    func publish(_ snapshot: AlarmSnapshot) {
        guard WCSession.default.activationState == .activated, WCSession.default.isPaired, WCSession.default.isWatchAppInstalled else { return }
        try? WCSession.default.updateApplicationContext([
            SyncKeys.alarms: Codec.encodeAlarms(snapshot.alarms),
            SyncKeys.updatedAt: snapshot.updatedAt,
        ])
    }

    func send(command: String) {
        guard WCSession.default.activationState == .activated, WCSession.default.isReachable else { return }
        WCSession.default.sendMessage([SyncKeys.command: command], replyHandler: nil)
    }

    // MARK: WCSessionDelegate

    func session(_ session: WCSession, activationDidCompleteWith state: WCSessionActivationState, error: Error?) {
        guard state == .activated else { return }
        receive(context: session.receivedApplicationContext)
        Task { @MainActor in self.publish(PhoneModel.shared.storage.snapshot()) }
    }

    func sessionDidBecomeInactive(_ session: WCSession) {}

    func sessionDidDeactivate(_ session: WCSession) {
        // Uhr gewechselt: neue Sitzung aktivieren.
        WCSession.default.activate()
    }

    func session(_ session: WCSession, didReceiveApplicationContext context: [String: Any]) {
        receive(context: context)
    }

    func session(_ session: WCSession, didReceiveUserInfo userInfo: [String: Any] = [:]) {
        guard let text = userInfo[SyncKeys.night] as? String, let night = try? Codec.decodeNight(text) else { return }
        Task { @MainActor in PhoneModel.shared.add(night: night) }
    }

    func session(_ session: WCSession, didReceiveMessage message: [String: Any]) {
        guard let command = message[SyncKeys.command] as? String else { return }
        let alarmId = message[SyncKeys.alarms] as? String
        Task { @MainActor in
            let model = PhoneModel.shared
            switch command {
            case SyncKeys.ring:
                model.watchRang()
                await model.backup.ringNow(alarm: model.alarms.first { $0.id == alarmId })
            case SyncKeys.stop: await model.backup.stopRinging()
            default: break
            }
        }
    }

    private func receive(context: [String: Any]) {
        guard let text = context[SyncKeys.alarms] as? String,
              let updatedAt = context[SyncKeys.updatedAt] as? Int64,
              let alarms = try? Codec.decodeAlarms(text) else { return }
        Task { @MainActor in PhoneModel.shared.applyRemote(AlarmSnapshot(alarms: alarms, updatedAt: updatedAt)) }
    }
}
