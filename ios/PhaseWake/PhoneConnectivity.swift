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

    /// Wecker, Feiertage, Schlafziel (Minuten) und Stand der Lernphase als Anwendungskontext (ersetzt jeweils den ganzen
    /// Stand, darum immer alles).
    func publish(_ snapshot: AlarmSnapshot, holidays: HolidayList, sleepGoal: Int, learning: LearningSnapshot) {
        guard WCSession.default.activationState == .activated, WCSession.default.isPaired, WCSession.default.isWatchAppInstalled else { return }
        var context: [String: Any] = [
            SyncKeys.alarms: Codec.encodeAlarms(snapshot.alarms),
            SyncKeys.updatedAt: snapshot.updatedAt,
            SyncKeys.holidays: Holidays.encode(holidays),
            SyncKeys.sleepGoal: Int64(sleepGoal),
        ]
        learning.apply(to: &context)
        context[SyncKeys.autoOnset] = WatchNight.autoOnset
        do {
            try WCSession.default.updateApplicationContext(context)
            DiagStore.shared.log(.sync, "sent \(snapshot.alarms.count) alarms, \(holidays.dates.count) holidays")
        } catch {
            DiagStore.shared.log(.error, "context not sent: \(error.localizedDescription)")
        }
    }

    /// Befehle, die vor der Aktivierung kamen (z. B. Stopp am Wecker, App dafür im Hintergrund gestartet).
    /// Nur auf dem Main-Thread.
    private var pending: [[String: Any]] = []

    /// Sofort, wenn die Uhr erreichbar ist; sonst zugestellt, sobald sie es wieder ist.
    /// `extra`: Inhalt des Befehls (Plan, Kennung), siehe `SyncKeys`. `night`: Nacht, zu der «stop» bzw. «schlummern»
    /// gehört (Kennung in `SyncKeys.alarms`, Zielzeit in ms in `SyncKeys.target`, wie die Uhr sie schickt); die Uhr
    /// folgt nur, wenn sie gerade dieselbe Nacht weckt.
    @MainActor
    func send(command: String, extra: [String: String] = [:], night: RingNight? = nil) {
        guard WCSession.isSupported() else { return }
        var payload: [String: Any] = [SyncKeys.command: command, SyncKeys.sentAt: Int64(Date().timeIntervalSince1970 * 1000)]
        for (key, value) in extra { payload[key] = value }
        if let alarmId = night?.alarmId { payload[SyncKeys.alarms] = alarmId }
        if let targetMs = night?.targetMs { payload[SyncKeys.target] = targetMs }
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
        // Gekoppelt und App installiert? Bestimmt die Nachfrist des Zweitweckers (ohne Uhr 0 s).
        BackupAlarm.noteWatchState(session)
        receive(context: session.receivedApplicationContext)
        Task { @MainActor in
            PhoneModel.shared.publishToWatch()
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

    /// Uhr gekoppelt oder entkoppelt, App auf der Uhr installiert oder entfernt: Nachfrist des Zweitweckers anpassen.
    func sessionWatchStateDidChange(_ session: WCSession) {
        BackupAlarm.noteWatchState(session)
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
        // Aufzeichnung der Lernphase (Uhr-App ab `WatchProtocol.learning`).
        if let text = userInfo[SyncKeys.learnRecording] as? String {
            Task { @MainActor in await PhoneLearning.shared.receive(text) }
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
        seenWatch()
        guard let command = message[SyncKeys.command] as? String else { return }
        let alarmId = (message[SyncKeys.alarms] as? String).flatMap { $0.isEmpty ? nil : $0 }
        // Zielzeit der Nacht (fehlt, wenn die Uhr keine Nacht kennt) und Sendezeit.
        let target = (message[SyncKeys.target] as? Int64).map { Date(timeIntervalSince1970: Double($0) / 1000) }
        let sentAt = message[SyncKeys.sentAt] as? Int64
        // «klingeln» und «stop» ohne Sendezeit (bzw. «klingeln» ohne Kennung oder Schlummer-Runden) stammen von einer
        // veralteten Uhr-App: verwerfen, kein Rückfall (der Nacht-Check meldet sie als veraltet). Wie Android.
        let snoozesLeftValue = (message[SyncKeys.snoozesLeft] as? Int64).map { Int($0) }
        if command == SyncKeys.ring || command == SyncKeys.stop,
           sentAt == nil || (command == SyncKeys.ring && (alarmId == nil || snoozesLeftValue == nil)) {
            DiagStore.shared.log(.suppressed, "\(command) from outdated watch")
            return
        }
        // Älter als 2 Minuten (nachgereicht, z. B. per `transferUserInfo`): klingelt bzw. stoppt nicht mehr.
        let stale = sentAt.map { Int64(Date().timeIntervalSince1970 * 1000) - $0 > SyncKeys.maxRingAgeMs } ?? false
        // Nur bei «stop» nach dem Schlummern auf der Uhr.
        let snoozeUntil = (message[SyncKeys.snoozeUntil] as? Int64).map { Date(timeIntervalSince1970: Double($0) / 1000) }
        let snoozesLeft = snoozesLeftValue ?? 0
        let id = message[SyncKeys.id] as? String
        let payload = message[SyncKeys.payload] as? String
        Task { @MainActor in
            let model = PhoneModel.shared
            switch command {
            case SyncKeys.ring:
                if stale {
                    // Verspätet zugestellt (iPhone war ausser Reichweite): nicht mehr klingeln – und die Sicherung dieser
                    // Nacht bleibt (Entscheid r1AD): Ob die Uhr wirklich geweckt hat, zeigt erst ihre Nacht oder ihr «stop».
                    DiagStore.shared.log(.suppressed, "late ring, backup kept")
                } else {
                    model.watchRang()
                    DiagStore.shared.log(.ring, "watch rang, phone too")
                    let ring = await model.backup.ringNow(alarmId: alarmId, alarm: model.ringSpec(alarmId), target: target,
                                                          snoozesLeft: snoozesLeft)
                    await model.transient.watchRang(alarmId, phoneRing: ring)
                }
            case SyncKeys.stop:
                let night = RingNight(alarmId: alarmId, target: target)
                if stale {
                    // Ein spätes «stop» träfe sonst ein Klingeln, das inzwischen zu einer anderen Nacht gehört.
                    DiagStore.shared.log(.suppressed, "late stop")
                } else {
                    DiagStore.shared.log(.stop, snoozeUntil == nil ? "from watch" : "watch snoozed")
                    // Nur das Klingeln dieser Nacht; ein zweiter Wecker klingelt weiter.
                    await model.backup.stopRinging(night: night, snoozed: snoozeUntil != nil)
                }
                // Geschlummert: Sicherung kurz nach dem Schlummer-Ende, die Nacht läuft weiter.
                if let snoozeUntil { await model.backup.watchSnoozed(night: night, until: snoozeUntil) }
                await model.backup.watchHandled(alarmId: alarmId, target: target)
                if snoozeUntil == nil { await model.transient.stopArrived(alarmId) }
            case SyncKeys.testAck:
                model.transient.received(id)
            case SyncKeys.nap:
                if let payload, let plan = try? Transient.decode(payload) { await model.transient.napFromWatch(plan) }
            case SyncKeys.transientCancel:
                await model.transient.cancelledOnWatch(id)
            case SyncKeys.onsetShift:
                // Nicht nach Alter verworfen: Die Meldung gilt für ihre Nacht, solange die läuft (`OnsetShift.expired`).
                if let shift = OnsetShift.decode(payload) { await WatchNight.shared.received(shift) }
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
        // «Nacht aufzeichnen» auf der Uhr (fehlt bei älteren Uhr-Versionen).
        if let since = context[SyncKeys.recordSince] as? Int64 {
            let date = since > 0 ? Date(timeIntervalSince1970: Double(since) / 1000) : nil
            Task { @MainActor in WatchNight.shared.noteRecording(since: date) }
        }
        // Rekorder für eine Nacht nicht gestartet (Audit F-06); fehlt bei älteren Uhren (dann keine Warnung).
        if let text = context[SyncKeys.recorderSkip] as? String {
            let skip = RecorderSkip.decode(text)
            Task { @MainActor in NightCheck.watchRecorderSkip = skip }
        }
        // Ohne Akkustand (Uhr lädt oder kennt ihn nicht) gilt er als unbekannt.
        var battery: (percent: Int, at: Date)?
        if let percent = context[SyncKeys.battery] as? Int64, let at = context[SyncKeys.batteryAt] as? Int64 {
            battery = (percent: Int(percent), at: Date(timeIntervalSince1970: Double(at) / 1000))
        }
        let level = battery
        // Stand der Verbindung; fehlt er in einem Kontext der Uhr (sehr alte Uhr-App), zählt er als 0: Der Nacht-Check
        // meldet «Uhr-App veraltet» (wie Android, Status ohne Angabe).
        let version = Int((context[SyncKeys.watchProtocol] as? Int64) ?? 0)
        // Erlaubnis der Mitteilungen auf der Uhr; fehlt sie (ältere Uhr, noch nicht gefragt), warnt der Nacht-Check nicht.
        // Mit dem Zeitpunkt der Prüfung auf der Uhr; ohne ihn (ältere Uhr) kein Hinweis «veraltet».
        let checkedAt = (context[SyncKeys.watchNotificationsAt] as? Int64).map { Date(timeIntervalSince1970: Double($0) / 1000) }
        let notifications: (state: WatchNotifications, checkedAt: Date?)? = (context[SyncKeys.watchNotifications] as? Int64)
            .flatMap(WatchNotifications.init(rawValue:)).map { (state: $0, checkedAt: checkedAt) }
        if !context.isEmpty {
            Task { @MainActor in PhoneModel.shared.setWatchStatus(battery: level, version: version, notifications: notifications) }
        }
        guard let text = context[SyncKeys.alarms] as? String,
              let updatedAt = context[SyncKeys.updatedAt] as? Int64,
              let alarms = try? Codec.decodeAlarms(text) else { return }
        Task { @MainActor in PhoneModel.shared.applyRemote(AlarmSnapshot(alarms: alarms, updatedAt: updatedAt)) }
    }
}
