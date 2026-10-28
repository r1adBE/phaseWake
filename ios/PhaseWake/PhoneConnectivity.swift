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

    /// Befehle, die vor der Aktivierung kamen (z. B. Stopp am Wecker, App dafür im Hintergrund gestartet).
    /// Nur auf dem Main-Thread.
    private var pending: [[String: Any]] = []

    /// Sofort, wenn die Uhr erreichbar ist; sonst zugestellt, sobald sie es wieder ist.
    @MainActor
    func send(command: String) {
        guard WCSession.isSupported() else { return }
        let payload: [String: Any] = [SyncKeys.command: command, SyncKeys.sentAt: Int64(Date().timeIntervalSince1970 * 1000)]
        guard WCSession.default.activationState == .activated else {
            pending.append(payload)
            if WCSession.default.delegate == nil { activate() }
            return
        }
        deliver(payload)
    }

    private func deliver(_ payload: [String: Any]) {
        if WCSession.default.isReachable {
            WCSession.default.sendMessage(payload, replyHandler: nil) { _ in WCSession.default.transferUserInfo(payload) }
        } else {
            WCSession.default.transferUserInfo(payload)
        }
    }

    // MARK: WCSessionDelegate

    func session(_ session: WCSession, activationDidCompleteWith state: WCSessionActivationState, error: Error?) {
        guard state == .activated else { return }
        receive(context: session.receivedApplicationContext)
        Task { @MainActor in
            self.publish(PhoneModel.shared.storage.snapshot())
            let queued = self.pending
            self.pending = []
            queued.forEach(self.deliver)
        }
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
        if userInfo[SyncKeys.command] != nil {
            // Befehl, der die Uhr nicht sofort erreichen konnte und nachgeliefert wird.
            self.session(session, didReceiveMessage: userInfo)
            return
        }
        guard let text = userInfo[SyncKeys.night] as? String, let night = try? Codec.decodeNight(text) else { return }
        Task { @MainActor in
            PhoneModel.shared.add(night: night)
            // Die Uhr hat geweckt: Sicherung dieser Nacht aussetzen (falls «ring» verloren ging).
            await PhoneModel.shared.backup.watchHandled(alarmId: night.alarmId, target: night.target)
        }
    }

    func session(_ session: WCSession, didReceiveMessage message: [String: Any]) {
        guard let command = message[SyncKeys.command] as? String else { return }
        let alarmId = (message[SyncKeys.alarms] as? String).flatMap { $0.isEmpty ? nil : $0 }
        // Zielzeit der Nacht und Sendezeit; ältere Uhr-Versionen schicken sie nicht.
        let target = (message[SyncKeys.target] as? Int64).map { Date(timeIntervalSince1970: Double($0) / 1000) }
        let sentAt = message[SyncKeys.sentAt] as? Int64
        let snoozesLeft = (message[SyncKeys.snoozesLeft] as? Int64).map { Int($0) } ?? 0
        Task { @MainActor in
            let model = PhoneModel.shared
            switch command {
            case SyncKeys.ring:
                if let sentAt, Int64(Date().timeIntervalSince1970 * 1000) - sentAt > SyncKeys.maxRingAgeMs {
                    // Verspätet zugestellt (iPhone war ausser Reichweite): nicht mehr klingeln, nur diese Nacht als erledigt merken.
                    await model.backup.watchHandled(alarmId: alarmId, target: target)
                } else {
                    model.watchRang()
                    await model.backup.ringNow(alarm: model.alarms.first { $0.id == alarmId }, target: target, snoozesLeft: snoozesLeft)
                }
            case SyncKeys.stop:
                await model.backup.stopRinging()
                await model.backup.watchHandled(alarmId: alarmId, target: target)
            default: break
            }
        }
    }

    private func receive(context: [String: Any]) {
        if let armed = context[SyncKeys.armedTarget] as? Int64 {
            let date = armed > 0 ? Date(timeIntervalSince1970: Double(armed) / 1000) : nil
            Task { @MainActor in PhoneModel.shared.watchArmed(date) }
        }
        // Ohne Akkustand (Uhr lädt oder kennt ihn nicht) gilt er als unbekannt.
        var battery: (percent: Int, at: Date)?
        if let percent = context[SyncKeys.battery] as? Int64, let at = context[SyncKeys.batteryAt] as? Int64 {
            battery = (percent: Int(percent), at: Date(timeIntervalSince1970: Double(at) / 1000))
        }
        let level = battery
        if !context.isEmpty {
            Task { @MainActor in PhoneModel.shared.setWatchBattery(level) }
        }
        guard let text = context[SyncKeys.alarms] as? String,
              let updatedAt = context[SyncKeys.updatedAt] as? Int64,
              let alarms = try? Codec.decodeAlarms(text) else { return }
        Task { @MainActor in PhoneModel.shared.applyRemote(AlarmSnapshot(alarms: alarms, updatedAt: updatedAt)) }
    }
}
