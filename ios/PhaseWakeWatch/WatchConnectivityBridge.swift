import Foundation
import PhaseWakeCore
import UserNotifications
import WatchConnectivity
import WatchKit

/// Verbindung zum iPhone: Wecker, Feiertage und Schlafziel kommen als Anwendungskontext, Nächte gehen als Benutzerdaten,
/// Weck-Ereignisse als Nachricht, ohne Verbindung als Benutzerdaten nachgereicht.
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
            let snapshot = PhaseWakeStorage.watch.snapshot()
            context[SyncKeys.alarms] = Codec.encodeAlarms(snapshot.alarms)
            context[SyncKeys.updatedAt] = snapshot.updatedAt
        }
        sendContext()
    }

    /// Vor der Aktivierung (z. B. beim Hintergrundstart für eine Sitzung) bleibt der Stand gemerkt und
    /// geht nach `activationDidComplete` nach. Jedes Senden nimmt den aktuellen Akkustand der Uhr mit
    /// (Nacht-Check auf dem iPhone).
    private func sendContext() {
        guard WCSession.default.activationState == .activated, !context.isEmpty else { return }
        // Formatfähigkeit: Das iPhone erkennt so eine ältere Uhr ohne Überspringen und Feiertage.
        context[SyncKeys.alarmFields] = Int64(Codec.alarmFields)
        // Stand der Verbindung: Das iPhone erkennt so eine ältere Uhr-App (Nacht-Check «Uhr-App veraltet»).
        context[SyncKeys.watchProtocol] = Int64(WatchProtocol.current)
        let device = WKInterfaceDevice.current()
        device.isBatteryMonitoringEnabled = true
        if device.batteryState == .unplugged, device.batteryLevel >= 0 {
            context[SyncKeys.battery] = Int64((device.batteryLevel * 100).rounded())
            context[SyncKeys.batteryAt] = Int64(Date().timeIntervalSince1970 * 1000)
        } else {
            // Am Laden oder unbekannt: nichts melden, das iPhone warnt dann nicht.
            context[SyncKeys.battery] = nil
            context[SyncKeys.batteryAt] = nil
        }
        try? WCSession.default.updateApplicationContext(context)
    }

    /// Zuletzt protokollierter Stand der Mitteilungen (Diagnose nur bei einer Änderung). Nur auf dem Main-Thread.
    private var loggedNotifications: WatchNotifications?

    /// Erlaubnis für Mitteilungen (Rückfall-Wecker) ans iPhone melden (`SyncKeys.watchNotifications`), mit einer Zeile im
    /// Protokoll, wenn sie sich ändert. Noch nicht gefragt: nichts melden, das iPhone warnt dann nicht.
    @MainActor
    func publishNotificationState() async {
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        let state: WatchNotifications?
        switch settings.authorizationStatus {
        case .authorized:
            state = settings.timeSensitiveSetting == .disabled ? .notTimeSensitive : .allowed
        case .denied, .provisional:
            state = .blocked
        case .notDetermined:
            state = nil
        @unknown default:
            state = nil
        }
        if let state, state != loggedNotifications {
            loggedNotifications = state
            let text = switch state {
            case .allowed: "allowed"
            case .blocked: "blocked"
            case .notTimeSensitive: "not time-sensitive"
            }
            DiagStore.shared.log(state.isProblem ? .error : .sync, "notifications \(text)")
        }
        if let state {
            context[SyncKeys.watchNotifications] = state.rawValue
        } else {
            context[SyncKeys.watchNotifications] = nil
        }
        publishBattery()
    }

    /// Beim Öffnen der App: Akkustand neu melden, auch wenn sich sonst nichts geändert hat.
    func publishBattery() {
        if context[SyncKeys.alarms] == nil {
            let snapshot = PhaseWakeStorage.watch.snapshot()
            context[SyncKeys.alarms] = Codec.encodeAlarms(snapshot.alarms)
            context[SyncKeys.updatedAt] = snapshot.updatedAt
        }
        sendContext()
    }

    @MainActor
    func send(night: NightRecord) {
        deliver([SyncKeys.night: Codec.encodeNight(night)], userInfoOnly: true)
    }

    /// Sofort, wenn das iPhone erreichbar ist; sonst zugestellt, sobald es das wieder ist.
    /// Mit Zielzeit der Nacht und Sendezeit: Das iPhone verwirft ein verspätetes «klingeln» und setzt
    /// die Sicherung genau für diese Nacht aus. `snoozeUntil` nur bei «stop» nach dem Schlummern: Das iPhone stellt
    /// dann eine Sicherung kurz nach dem Schlummer-Ende, statt die Nacht als beendet zu nehmen.
    @MainActor
    func send(command: String, alarmId: String = "", target: Date? = nil, snoozesLeft: Int? = nil, snoozeUntil: Date? = nil) {
        var payload: [String: Any] = [SyncKeys.command: command, SyncKeys.alarms: alarmId,
                                      SyncKeys.sentAt: Int64(Date().timeIntervalSince1970 * 1000)]
        if let target { payload[SyncKeys.target] = Int64(target.timeIntervalSince1970 * 1000) }
        if let snoozesLeft { payload[SyncKeys.snoozesLeft] = Int64(snoozesLeft) }
        if let snoozeUntil { payload[SyncKeys.snoozeUntil] = Int64(snoozeUntil.timeIntervalSince1970 * 1000) }
        deliver(payload)
    }

    /// Befehl mit Inhalt (Powernap, Abbruch, Bestätigung des Probeweckers); sofort oder nachgereicht.
    @MainActor
    func send(command: String, extra: [String: String]) {
        var payload: [String: Any] = [SyncKeys.command: command, SyncKeys.sentAt: Int64(Date().timeIntervalSince1970 * 1000)]
        for (key, value) in extra { payload[key] = value }
        deliver(payload)
    }

    /// Was vor der Aktivierung anfiel (z. B. App für eine Sitzung im Hintergrund gestartet und gleich geklingelt): Vorher
    /// verwirft WatchConnectivity Nachrichten und Benutzerdaten bzw. wirft einen Fehler. Geht nach `activationDidComplete`
    /// raus, wie auf dem iPhone (`PhoneConnectivity.pending`). Nur auf dem Main-Thread.
    private var pending: [(payload: [String: Any], userInfoOnly: Bool)] = []

    /// Sofort als Nachricht, wenn das iPhone erreichbar ist (bei einem Fehler als Benutzerdaten), sonst als Benutzerdaten;
    /// `userInfoOnly` immer als Benutzerdaten (Nächte). Vor der Aktivierung gemerkt (`pending`). Die Sendezeit bleibt
    /// die ursprüngliche: Das iPhone verwirft ein zu spätes «klingeln» selbst.
    @MainActor
    private func deliver(_ payload: [String: Any], userInfoOnly: Bool = false) {
        guard WCSession.isSupported() else { return }
        guard WCSession.default.activationState == .activated else {
            pending.append((payload: payload, userInfoOnly: userInfoOnly))
            if WCSession.default.delegate == nil { activate() }
            return
        }
        if !userInfoOnly, WCSession.default.isReachable {
            WCSession.default.sendMessage(payload, replyHandler: nil) { _ in WCSession.default.transferUserInfo(payload) }
        } else {
            WCSession.default.transferUserInfo(payload)
        }
    }

    // MARK: WCSessionDelegate

    func session(_ session: WCSession, activationDidCompleteWith state: WCSessionActivationState, error: Error?) {
        guard state == .activated else { return }
        receive(session.receivedApplicationContext)
        // `context` und `pending` werden auf dem Main-Thread geschrieben.
        Task { @MainActor in
            self.sendContext()
            let queued = self.pending
            self.pending = []
            for item in queued { self.deliver(item.payload, userInfoOnly: item.userInfoOnly) }
        }
    }

    func session(_ session: WCSession, didReceiveApplicationContext context: [String: Any]) {
        receive(context)
    }

    func session(_ session: WCSession, didReceiveUserInfo userInfo: [String: Any] = [:]) {
        self.session(session, didReceiveMessage: userInfo)
    }

    /// Anfrage mit Antwort: nur der Diagnose-Bericht. Alles andere wie eine Nachricht ohne Antwort.
    func session(_ session: WCSession, didReceiveMessage message: [String: Any], replyHandler: @escaping ([String: Any]) -> Void) {
        guard message[SyncKeys.command] as? String == SyncKeys.diagRequest else {
            self.session(session, didReceiveMessage: message)
            replyHandler([:])
            return
        }
        // Aus Quellen, die von jedem Thread lesbar sind; die Antwort geht sofort zurück.
        replyHandler([SyncKeys.payload: DiagReports.encode(DiagReport(status: WatchDiagStatus.shared.current, entries: DiagStore.shared.entries()))])
    }

    func session(_ session: WCSession, didReceiveMessage message: [String: Any]) {
        guard let command = message[SyncKeys.command] as? String else { return }
        if receiveTransient(command, message) { return }
        // Verspätet zugestelltes Stopp oder Schlummern vom iPhone träfe sonst eine spätere Nacht.
        if command != SyncKeys.clearHistory, let sentAt = message[SyncKeys.sentAt] as? Int64,
           Int64(Date().timeIntervalSince1970 * 1000) - sentAt > SyncKeys.maxRingAgeMs { return }
        // Nacht des iPhone-Alarms (fehlt bei älteren iPhone-Versionen): Stopp oder Schlummern nur für dieselbe Nacht.
        let alarmId = (message[SyncKeys.alarms] as? String).flatMap { $0.isEmpty ? nil : $0 }
        let target = (message[SyncKeys.target] as? Int64).map { Date(timeIntervalSince1970: Double($0) / 1000) }
        Task { @MainActor in
            let alarm = SmartAlarm.shared
            if command == SyncKeys.stop || command == SyncKeys.snooze, !alarm.isThisNight(alarmId: alarmId, target: target) {
                DiagStore.shared.log(.suppressed, "phone \(command) for other night")
                return
            }
            switch command {
            case SyncKeys.stop: alarm.dismiss(notifyPhone: false)
            case SyncKeys.snooze: alarm.snoozeFromPhone()
            case SyncKeys.clearHistory: WatchModel.shared.storage.clearNights()
            default: break
            }
        }
    }

    /// Probewecker, Powernap und Abbruch vom iPhone. Nicht nach Alter verworfen: Kommt ein Plan spät an
    /// (Uhr-App war zu), gilt er nur, solange seine Zielzeit noch vorn liegt. - Returns: ob behandelt.
    private func receiveTransient(_ command: String, _ message: [String: Any]) -> Bool {
        switch command {
        case SyncKeys.test, SyncKeys.nap:
            guard let text = message[SyncKeys.payload] as? String, let plan = try? Transient.decode(text) else { return true }
            guard plan.plan.target > Date() else {
                DiagStore.shared.log(plan.kind == .test ? .test : .nap, "too late")
                return true
            }
            // Kollidiert er mit einem Wecker (Fenster läuft, klingelt, überdeckt das Fenster): verwerfen, ohne
            // Bestätigung – das iPhone zeigt dann «Uhr hat den Test empfangen» als nicht erfüllt.
            Task { @MainActor in
                guard WatchModel.shared.accept(plan), plan.kind == .test else { return }
                self.send(command: SyncKeys.testAck, extra: [SyncKeys.id: plan.plan.alarmId])
            }
            return true
        case SyncKeys.transientCancel:
            let id = message[SyncKeys.id] as? String
            Task { @MainActor in
                WatchModel.shared.endTransient(id)
                WatchModel.shared.transientEnded()
            }
            return true
        default:
            return false
        }
    }

    private func receive(_ context: [String: Any]) {
        if let text = context[SyncKeys.holidays] as? String {
            let list = Holidays.decode(text)
            Task { @MainActor in WatchModel.shared.applyHolidays(list) }
        }
        // Schlafziel nur für den Einschlafpunkt; fehlt es (älteres iPhone), bleibt der gespeicherte Stand.
        if let goal = context[SyncKeys.sleepGoal] as? Int64 {
            Task { @MainActor in WatchModel.shared.applySleepGoal(Int(goal)) }
        }
        guard let text = context[SyncKeys.alarms] as? String,
              let updatedAt = context[SyncKeys.updatedAt] as? Int64,
              let alarms = try? Codec.decodeAlarms(text) else { return }
        Task { @MainActor in WatchModel.shared.applyRemote(AlarmSnapshot(alarms: alarms, updatedAt: updatedAt)) }
    }
}
