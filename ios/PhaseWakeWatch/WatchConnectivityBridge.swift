import Foundation
import PhaseWakeCore
import UserNotifications
import WatchConnectivity
import WatchKit

/// Verbindung zum iPhone: Wecker, Feiertage und Schlafziel kommen als Anwendungskontext, Nächte gehen als
/// Benutzerdaten, Weck-Ereignisse als Nachricht, ohne Verbindung als Benutzerdaten nachgereicht.
final class WatchConnectivityBridge: NSObject, WCSessionDelegate, @unchecked Sendable {
    static let shared = WatchConnectivityBridge()

    func activate() {
        guard WCSession.isSupported() else { return }
        WCSession.default.delegate = self
        WCSession.default.activate()
    }

    /// Letzter Stand, der als Anwendungskontext geht (Wecker und Scharf-Status gemeinsam); nur Main-Thread.
    @MainActor private var context: [String: Any] = [:]

    @MainActor func publish(_ snapshot: AlarmSnapshot) {
        AlarmStamps.apply(snapshot, to: &context)
        sendContext()
    }

    /// Ohne Wecker im Kontext (erster Versand seit dem Start) den gespeicherten Stand dazulegen.
    @MainActor private func ensureAlarms() {
        if context[SyncKeys.alarms] == nil { AlarmStamps.apply(PhaseWakeStorage.watch.snapshot(), to: &context) }
    }

    /// Für welche Zielzeit die Uhr scharf ist (Sitzung geplant oder laufend); 0 = nicht scharf.
    @MainActor func publish(armedTarget: Date?) {
        context[SyncKeys.armedTarget] = armedTarget.map(TimeBounds.millis) ?? 0
        ensureAlarms()
        sendContext()
    }

    /// Laufende Aufzeichnung («Nacht aufzeichnen» oder Lernphase) seit `since`; `nil` = keine (iPhone: «Aufzeichnung
    /// läuft seit …»).
    @MainActor func publish(recordSince since: Date?) {
        context[SyncKeys.recordSince] = Int64((since?.timeIntervalSince1970 ?? 0) * 1000)
        ensureAlarms()
        sendContext()
    }

    /// Rekorder für eine Nacht nicht gestartet (``RecorderSkip``) bzw. `nil`, wenn er doch läuft: gespeichert
    /// (geht mit jedem Anwendungskontext ans iPhone, auch nach einem Neustart der App) und nur bei einer Änderung
    /// gesendet.
    @MainActor func publish(recorderSkip skip: RecorderSkip?) {
        let text = skip?.encode() ?? ""
        guard text != Self.recorderSkip else { return }
        SettingsFile.watchSession.set(text, forKey: Self.recorderSkipKey)
        ensureAlarms()
        sendContext()
    }

    /// Schlüssel der Meldung ``publish(recorderSkip:)`` in `SettingsFile.watchSession`.
    private static let recorderSkipKey = "recorderSkip"
    /// Gespeicherte Meldung (``RecorderSkip/encode()``); leer = keine.
    private static var recorderSkip: String { SettingsFile.watchSession.string(forKey: recorderSkipKey) ?? "" }

    /// Frühere Zielzeit aus «Gute Nacht» (bzw. ihr Aufheben) ans iPhone, das den Zweitwecker dieser Nacht verschiebt;
    /// sofort oder nachgereicht.
    @MainActor
    func send(onsetShift shift: OnsetShift) {
        deliver([SyncKeys.command: SyncKeys.onsetShift, SyncKeys.payload: shift.encode(),
                 SyncKeys.sentAt: Int64(Date().timeIntervalSince1970 * 1000)])
    }

    /// Vor der Aktivierung (z. B. beim Hintergrundstart für eine Sitzung) bleibt der Stand gemerkt und
    /// geht nach `activationDidComplete` nach. Jedes Senden nimmt den aktuellen Akkustand der Uhr mit
    /// (Nacht-Check auf dem iPhone).
    @MainActor private func sendContext() {
        guard WCSession.default.activationState == .activated, !context.isEmpty else { return }
        // Stand der Verbindung und Build-Nummer: Das iPhone erkennt so eine ältere oder andere Uhr-App (Nacht-Check).
        BuildNumber.applyWatch(to: &context)
        // Zuletzt geprüfte Erlaubnis für Mitteilungen samt Zeitpunkt, gespeichert: auch nach einem Neustart vor einer
        // neuen Prüfung dabei (sonst verlöre das iPhone Warnung und Hinweis «veraltet»).
        WatchNotificationsCheck.apply(Self.notificationsCheck, to: &context)
        // Rekorder nicht gestartet (Nacht-Check auf dem iPhone rot), gespeichert wie die Mitteilungen.
        context[SyncKeys.recorderSkip] = Self.recorderSkip
        let device = WKInterfaceDevice.current()
        device.isBatteryMonitoringEnabled = true
        // Auch am Laden, mit Ladezustand: Dann misst die Uhr trotz wenig Akku, und das iPhone warnt
        // nicht (Nacht-Check). Unbekannt: nichts melden.
        if device.batteryState != .unknown, device.batteryLevel >= 0 {
            context[SyncKeys.battery] = Int64((device.batteryLevel * 100).rounded())
            context[SyncKeys.batteryAt] = Int64(Date().timeIntervalSince1970 * 1000)
            context[SyncKeys.batteryCharging] = device.batteryState == .charging || device.batteryState == .full
        } else {
            for key in [SyncKeys.battery, SyncKeys.batteryAt, SyncKeys.batteryCharging] { context[key] = nil }
        }
        // Gescheitert: protokolliert wie am iPhone; der nächste Kontext ersetzt ihn ohnehin.
        do { try WCSession.default.updateApplicationContext(context) } catch {
            DiagStore.shared.log(.error, "context not sent: \(error.localizedDescription)")
        }
    }

    /// Zuletzt protokollierter Stand der Mitteilungen (Diagnose nur bei einer Änderung). Nur auf dem Main-Thread.
    @MainActor private var loggedNotifications: WatchNotifications?
    /// Wann der Stand der Mitteilungen zuletzt ans iPhone ging (`publishNotificationStateIfNeeded`). Nur auf dem
    /// Main-Thread.
    @MainActor private var notificationsSentAt: Date?
    /// Prüfung beim Planen läuft (nicht doppelt bei mehreren Rückfällen kurz nacheinander). Nur auf dem Main-Thread.
    @MainActor private var checkingNotifications = false
    /// Spätestens nach dieser Zeit geht der Stand beim Planen nochmals ans iPhone, auch unverändert.
    private static let notificationsResend: TimeInterval = 6 * 3600
    /// Schlüssel der zuletzt geprüften Erlaubnis in `SettingsFile.watchSession` (nicht in der Sicherung).
    private static let notificationsCheckKey = "watchNotificationsCheck"

    /// Zuletzt geprüfte Erlaubnis für Mitteilungen samt Zeitpunkt der Prüfung; `nil` = noch nicht gefragt.
    private static var notificationsCheck: WatchNotificationsCheck? {
        get { WatchNotificationsCheck(stored: SettingsFile.watchSession.object(forKey: notificationsCheckKey)) }
        set { SettingsFile.watchSession.set(newValue?.stored, forKey: notificationsCheckKey) }
    }

    /// Beim Planen einer Nacht (`SmartAlarm.scheduleFallback`): Stand der Mitteilungen ans iPhone, aber nur, wenn er
    /// sich geändert hat oder zuletzt vor über 6 h ging – sonst bei jedem Neuplanen ein neuer Anwendungskontext.
    @MainActor
    func publishNotificationStateIfNeeded() async {
        guard !checkingNotifications else { return }
        checkingNotifications = true
        defer { checkingNotifications = false }
        let state = await notificationState()
        let sent = Self.notificationsCheck?.state
        if state == sent, let at = notificationsSentAt,
           Date().timeIntervalSince(at) < Self.notificationsResend { return }
        publish(notifications: state)
    }

    /// Erlaubnis für Mitteilungen (Rückfall-Wecker) ans iPhone melden (`SyncKeys.watchNotifications`), mit einer Zeile
    /// im Protokoll, wenn sie sich ändert. Noch nicht gefragt: nichts melden, das iPhone warnt dann nicht.
    @MainActor
    func publishNotificationState() async {
        publish(notifications: await notificationState())
    }

    /// Stand der Erlaubnis für Mitteilungen; `nil` = noch nicht gefragt.
    private func notificationState() async -> WatchNotifications? {
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        switch settings.authorizationStatus {
        case .authorized:
            return settings.timeSensitiveSetting == .disabled ? .notTimeSensitive : .allowed
        case .denied, .provisional:
            return .blocked
        case .notDetermined:
            return nil
        @unknown default:
            return nil
        }
    }

    /// Speichert `state` samt Zeitpunkt der Prüfung (jetzt) für jeden Anwendungskontext (mit Zeile im Protokoll bei
    /// einer Änderung) und sendet ihn.
    @MainActor
    private func publish(notifications state: WatchNotifications?) {
        notificationsSentAt = Date()
        if let state, state != loggedNotifications {
            loggedNotifications = state
            let text = switch state {
            case .allowed: "allowed"
            case .blocked: "blocked"
            case .notTimeSensitive: "not time-sensitive"
            }
            DiagStore.shared.log(state.isProblem ? .error : .sync, "notifications \(text)")
        }
        // Ist der Zeitpunkt der Prüfung älter als 3 Tage, weist der Nacht-Check auf dem iPhone darauf hin.
        Self.notificationsCheck = state.map { WatchNotificationsCheck(state: $0, checkedAt: Date()) }
        publishBattery()
    }

    /// Beim Öffnen der App: Akkustand neu melden, auch wenn sich sonst nichts geändert hat.
    @MainActor func publishBattery() {
        ensureAlarms()
        sendContext()
    }

    @MainActor
    func send(night: NightRecord) {
        deliver([SyncKeys.night: Codec.encodeNight(night)], userInfoOnly: true)
    }

    /// Aufzeichnung der Lernphase (`NightRecording.encode`, rund 40 KB) als Benutzerdaten wie eine Nacht.
    @MainActor
    func send(learnRecording text: String) {
        deliver([SyncKeys.learnRecording: text], userInfoOnly: true)
    }

    /// Lernphase zeichnet eine Nacht nicht auf (``LearnSkip``, Lern-Übersicht) als Benutzerdaten: zugestellt, sobald
    /// das iPhone erreichbar ist, auch wenn es nachts aus ist.
    @MainActor
    func send(learnSkip skip: LearnSkip) {
        deliver([SyncKeys.learnSkip: skip.encode()], userInfoOnly: true)
    }

    /// Sofort, wenn das iPhone erreichbar ist; sonst zugestellt, sobald es das wieder ist.
    /// Mit Zielzeit der Nacht und Sendezeit: Das iPhone verwirft ein verspätetes «klingeln» und setzt
    /// die Sicherung genau für diese Nacht aus. `snoozeUntil` nur bei «stop» nach dem Schlummern: Das iPhone stellt
    /// dann eine Sicherung kurz nach dem Schlummer-Ende, statt die Nacht als beendet zu nehmen.
    @MainActor
    func send(command: String, alarmId: String = "", target: Date? = nil, snoozesLeft: Int? = nil,
              snoozeUntil: Date? = nil) {
        var payload: [String: Any] = [SyncKeys.command: command, SyncKeys.alarms: alarmId,
                                      SyncKeys.sentAt: Int64(Date().timeIntervalSince1970 * 1000)]
        if let target { payload[SyncKeys.target] = TimeBounds.millis(target) }
        if let snoozesLeft { payload[SyncKeys.snoozesLeft] = Int64(snoozesLeft) }
        if let snoozeUntil { payload[SyncKeys.snoozeUntil] = TimeBounds.millis(snoozeUntil) }
        deliver(payload)
    }

    /// Befehl mit Inhalt (Powernap, Abbruch, Bestätigung des Probeweckers); sofort oder nachgereicht.
    @MainActor
    func send(command: String, extra: [String: String]) {
        var payload: [String: Any] = [SyncKeys.command: command,
                                      SyncKeys.sentAt: Int64(Date().timeIntervalSince1970 * 1000)]
        for (key, value) in extra { payload[key] = value }
        deliver(payload)
    }

    /// Was vor der Aktivierung anfiel (z. B. App für eine Sitzung im Hintergrund gestartet und gleich geklingelt):
    /// Vorher verwirft WatchConnectivity Nachrichten und Benutzerdaten bzw. wirft einen Fehler. Geht nach
    /// `activationDidComplete` raus, wie auf dem iPhone (`PhoneConnectivity.pending`). Nur auf dem Main-Thread.
    @MainActor var pending: [(payload: [String: Any], userInfoOnly: Bool)] = []

    /// Sofort als Nachricht, wenn das iPhone erreichbar ist (bei einem Fehler als Benutzerdaten), sonst als
    /// Benutzerdaten; `userInfoOnly` immer als Benutzerdaten (Nächte). Vor der Aktivierung gemerkt (`pending`). Die
    /// Sendezeit bleibt die ursprüngliche: Das iPhone verwirft ein zu spätes «klingeln» selbst.
    @MainActor
    private func deliver(_ payload: [String: Any], userInfoOnly: Bool = false) {
        guard WCSession.isSupported() else { return }
        guard WCSession.default.activationState == .activated else {
            pending.append((payload: payload, userInfoOnly: userInfoOnly))
            if WCSession.default.delegate == nil { activate() }
            return
        }
        if !userInfoOnly, WCSession.default.isReachable {
            WCSession.default.sendMessage(payload, replyHandler: nil) { _ in
                WCSession.default.transferUserInfo(payload)
            }
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
            self.context[SyncKeys.recordSince] = Int64((LearnRecorder.shared.since?.timeIntervalSince1970 ?? 0) * 1000)
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

    private func receive(_ context: [String: Any]) {
        if let text = context[SyncKeys.holidays] as? String {
            let list = Holidays.decode(text)
            Task { @MainActor in WatchModel.shared.applyHolidays(list) }
        }
        // Schlafziel nur für den Einschlafpunkt; fehlt es (älteres iPhone), bleibt der gespeicherte Stand.
        if let goal = context[SyncKeys.sleepGoal] as? Int64 {
            Task { @MainActor in WatchModel.shared.applySleepGoal(Int(goal)) }
        }
        // «Einschlafen automatisch erkennen» (fehlt bei älteren iPhone-Versionen; dann bleibt der Stand, Standard ein).
        if let on = context[SyncKeys.autoOnset] as? Bool {
            Task { @MainActor in LearnRecorder.shared.applyAutoOnset(on) }
        }
                // Lernphase (fehlt bei älteren iPhone-Versionen; dann bleibt der Stand).
        if let learning = LearningSnapshot(context: context) {
            Task { @MainActor in LearnRecorder.shared.apply(learning) }
        }
        guard let snapshot = AlarmStamps.snapshot(context: context) else { return }
        Task { @MainActor in WatchModel.shared.applyRemote(snapshot) }
    }
}
