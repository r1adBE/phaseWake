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

    /// Wecker und Feiertage als Anwendungskontext (ersetzt jeweils den ganzen Stand, darum immer beides).
    func publish(_ snapshot: AlarmSnapshot, holidays: HolidayList) {
        guard WCSession.default.activationState == .activated, WCSession.default.isPaired, WCSession.default.isWatchAppInstalled else { return }
        do {
            try WCSession.default.updateApplicationContext([
                SyncKeys.alarms: Codec.encodeAlarms(snapshot.alarms),
                SyncKeys.updatedAt: snapshot.updatedAt,
                SyncKeys.holidays: Holidays.encode(holidays),
            ])
            DiagStore.shared.log(.sync, "sent \(snapshot.alarms.count) alarms, \(holidays.dates.count) holidays")
        } catch {
            DiagStore.shared.log(.error, "context not sent: \(error.localizedDescription)")
        }
    }

    /// Befehle, die vor der Aktivierung kamen (z. B. Stopp am Wecker, App dafür im Hintergrund gestartet).
    /// Nur auf dem Main-Thread.
    private var pending: [[String: Any]] = []

    /// Sofort, wenn die Uhr erreichbar ist; sonst zugestellt, sobald sie es wieder ist.
    /// `extra`: Inhalt des Befehls (Plan, Kennung), siehe `SyncKeys`.
    @MainActor
    func send(command: String, extra: [String: String] = [:]) {
        guard WCSession.isSupported() else { return }
        var payload: [String: Any] = [SyncKeys.command: command, SyncKeys.sentAt: Int64(Date().timeIntervalSince1970 * 1000)]
        for (key, value) in extra { payload[key] = value }
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
            self.publish(PhoneModel.shared.storage.snapshot(), holidays: PhoneModel.shared.holidays.list)
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
        seenWatch()
        guard let text = userInfo[SyncKeys.night] as? String, let night = try? Codec.decodeNight(text) else { return }
        Task { @MainActor in
            PhoneModel.shared.add(night: night)
            // Die Uhr hat geweckt: Sicherung dieser Nacht aussetzen (falls «ring» verloren ging).
            await PhoneModel.shared.backup.watchHandled(alarmId: night.alarmId, target: night.target)
        }
    }

    func session(_ session: WCSession, didReceiveMessage message: [String: Any]) {
        seenWatch()
        guard let command = message[SyncKeys.command] as? String else { return }
        let alarmId = (message[SyncKeys.alarms] as? String).flatMap { $0.isEmpty ? nil : $0 }
        // Zielzeit der Nacht und Sendezeit; ältere Uhr-Versionen schicken sie nicht.
        let target = (message[SyncKeys.target] as? Int64).map { Date(timeIntervalSince1970: Double($0) / 1000) }
        let sentAt = message[SyncKeys.sentAt] as? Int64
        let snoozesLeft = (message[SyncKeys.snoozesLeft] as? Int64).map { Int($0) } ?? 0
        let id = message[SyncKeys.id] as? String
        let payload = message[SyncKeys.payload] as? String
        Task { @MainActor in
            let model = PhoneModel.shared
            switch command {
            case SyncKeys.ring:
                if let sentAt, Int64(Date().timeIntervalSince1970 * 1000) - sentAt > SyncKeys.maxRingAgeMs {
                    // Verspätet zugestellt (iPhone war ausser Reichweite): nicht mehr klingeln, nur diese Nacht als erledigt merken.
                    DiagStore.shared.log(.suppressed, "late ring")
                    await model.backup.watchHandled(alarmId: alarmId, target: target)
                } else {
                    model.watchRang()
                    DiagStore.shared.log(.ring, "watch rang, phone too")
                    let rang = await model.backup.ringNow(alarm: model.ringSpec(alarmId), target: target, snoozesLeft: snoozesLeft)
                    await model.transient.watchRang(alarmId, phoneRang: rang)
                }
            case SyncKeys.stop:
                DiagStore.shared.log(.stop, "from watch")
                await model.backup.stopRinging()
                await model.backup.watchHandled(alarmId: alarmId, target: target)
                await model.transient.stopArrived(alarmId)
            case SyncKeys.testAck:
                model.transient.received(id)
            case SyncKeys.nap:
                if let payload, let plan = try? Transient.decode(payload) { await model.transient.napFromWatch(plan) }
            case SyncKeys.transientCancel:
                await model.transient.cancelledOnWatch(id)
            default: break
            }
        }
    }

    /// Holt den Diagnose-Bericht der Uhr; `nil`, wenn sie nicht erreichbar ist oder nicht antwortet.
    @MainActor
    func requestWatchDiag() async -> DiagReport? {
        guard WCSession.isSupported(), WCSession.default.activationState == .activated, WCSession.default.isReachable else { return nil }
        let text: String? = await withCheckedContinuation { continuation in
            WCSession.default.sendMessage([SyncKeys.command: SyncKeys.diagRequest], replyHandler: { reply in
                continuation.resume(returning: reply[SyncKeys.payload] as? String)
            }, errorHandler: { _ in
                continuation.resume(returning: nil)
            })
        }
        return DiagReports.decode(text)
    }

    /// Zuletzt etwas von der Uhr empfangen (ms seit 1970), für die Diagnose.
    static let watchSeenKey = "diagWatchSeen"

    /// Für die Diagnose: wann zuletzt etwas von der Uhr kam (UserDefaults ist von jedem Thread aus sicher).
    private func seenWatch() {
        UserDefaults.standard.set(Date().timeIntervalSince1970 * 1000, forKey: Self.watchSeenKey)
    }

    private func receive(context: [String: Any]) {
        if !context.isEmpty { seenWatch() }
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
