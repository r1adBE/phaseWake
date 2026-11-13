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
        AlarmStamps.apply(snapshot, to: &context)
        sendContext()
    }

    /// Ohne Wecker im Kontext (erster Versand seit dem Start) den gespeicherten Stand dazulegen.
    private func ensureAlarms() {
        if context[SyncKeys.alarms] == nil { AlarmStamps.apply(PhaseWakeStorage.watch.snapshot(), to: &context) }
    }

    /// Für welche Zielzeit die Uhr scharf ist (Sitzung geplant oder laufend); 0 = nicht scharf.
    func publish(armedTarget: Date?) {
        context[SyncKeys.armedTarget] = armedTarget.map(TimeBounds.millis) ?? 0
        ensureAlarms()
        sendContext()
    }

    /// Laufende Aufzeichnung («Nacht aufzeichnen» oder Lernphase) seit `since`; `nil` = keine (iPhone: «Aufzeichnung läuft
    /// seit …»).
    func publish(recordSince since: Date?) {
        context[SyncKeys.recordSince] = Int64((since?.timeIntervalSince1970 ?? 0) * 1000)
        ensureAlarms()
        sendContext()
    }

    /// Rekorder für eine Nacht nicht gestartet (``RecorderSkip``, Audit F-06) bzw. `nil`, wenn er doch läuft: gespeichert
    /// (geht mit jedem Anwendungskontext ans iPhone, auch nach einem Neustart der App) und nur bei einer Änderung gesendet.
    func publish(recorderSkip skip: RecorderSkip?) {
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
    private func sendContext() {
        guard WCSession.default.activationState == .activated, !context.isEmpty else { return }
        // Stand der Verbindung: Das iPhone erkennt so eine ältere Uhr-App (Nacht-Check «Uhr-App veraltet»).
        context[SyncKeys.watchProtocol] = Int64(WatchProtocol.current)
        // Zuletzt geprüfte Erlaubnis für Mitteilungen samt Zeitpunkt, gespeichert: auch nach einem Neustart vor einer
        // neuen Prüfung dabei (sonst verlöre das iPhone Warnung und Hinweis «veraltet»).
        WatchNotificationsCheck.apply(Self.notificationsCheck, to: &context)
        // Rekorder nicht gestartet (Nacht-Check auf dem iPhone rot), gespeichert wie die Mitteilungen.
        context[SyncKeys.recorderSkip] = Self.recorderSkip
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
    /// Wann der Stand der Mitteilungen zuletzt ans iPhone ging (`publishNotificationStateIfNeeded`). Nur auf dem Main-Thread.
    private var notificationsSentAt: Date?
    /// Prüfung beim Planen läuft (nicht doppelt bei mehreren Rückfällen kurz nacheinander). Nur auf dem Main-Thread.
    private var checkingNotifications = false
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
        if state == sent, let at = notificationsSentAt, Date().timeIntervalSince(at) < Self.notificationsResend { return }
        publish(notifications: state)
    }

    /// Erlaubnis für Mitteilungen (Rückfall-Wecker) ans iPhone melden (`SyncKeys.watchNotifications`), mit einer Zeile im
    /// Protokoll, wenn sie sich ändert. Noch nicht gefragt: nichts melden, das iPhone warnt dann nicht.
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
    func publishBattery() {
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

    /// Lernphase zeichnet eine Nacht nicht auf (``LearnSkip``, Lern-Übersicht) als Benutzerdaten: zugestellt, sobald das
    /// iPhone erreichbar ist, auch wenn es nachts aus ist.
    @MainActor
    func send(learnSkip skip: LearnSkip) {
        deliver([SyncKeys.learnSkip: skip.encode()], userInfoOnly: true)
    }

    /// Sofort, wenn das iPhone erreichbar ist; sonst zugestellt, sobald es das wieder ist.
    /// Mit Zielzeit der Nacht und Sendezeit: Das iPhone verwirft ein verspätetes «klingeln» und setzt
    /// die Sicherung genau für diese Nacht aus. `snoozeUntil` nur bei «stop» nach dem Schlummern: Das iPhone stellt
    /// dann eine Sicherung kurz nach dem Schlummer-Ende, statt die Nacht als beendet zu nehmen.
    @MainActor
    func send(command: String, alarmId: String = "", target: Date? = nil, snoozesLeft: Int? = nil, snoozeUntil: Date? = nil) {
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
        var payload: [String: Any] = [SyncKeys.command: command, SyncKeys.sentAt: Int64(Date().timeIntervalSince1970 * 1000)]
        for (key, value) in extra { payload[key] = value }
        deliver(payload)
    }

    /// Was vor der Aktivierung anfiel (z. B. App für eine Sitzung im Hintergrund gestartet und gleich geklingelt): Vorher
    /// verwirft WatchConnectivity Nachrichten und Benutzerdaten bzw. wirft einen Fehler. Geht nach `activationDidComplete`
    /// raus, wie auf dem iPhone (`PhoneConnectivity.pending`). Nur auf dem Main-Thread.
    var pending: [(payload: [String: Any], userInfoOnly: Bool)] = []

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

    /// Anfrage mit Antwort: nur der Diagnose-Bericht. Alles andere wie eine Nachricht ohne Antwort.
    func session(_ session: WCSession, didReceiveMessage message: [String: Any], replyHandler: @escaping ([String: Any]) -> Void) {
        guard message[SyncKeys.command] as? String == SyncKeys.diagRequest else {
            self.session(session, didReceiveMessage: message)
            replyHandler([:])
            return
        }
        // Aus Quellen, die von jedem Thread lesbar sind; die Antwort geht sofort zurück.
        let text = DiagReports.encode(DiagReport(status: WatchDiagStatus.shared.current, entries: DiagStore.shared.entries()))
        if DiagTransfer.fitsReply(text) {
            replyHandler([SyncKeys.payload: text])
            return
        }
        // Voller Bericht: zu gross für eine Antwort (WatchConnectivity etwa 65 KB), darum als Datei (`DiagTransfer`).
        do {
            let url = try DiagTransfer.write(text, in: FileManager.default.temporaryDirectory)
            session.transferFile(url, metadata: [SyncKeys.command: SyncKeys.diagReport])
            replyHandler([SyncKeys.diagFile: true])
        } catch {
            DiagStore.shared.log(.sync, "diag report not sent: \(error.localizedDescription)")
            replyHandler([:])
        }
    }

    /// Datei des Diagnose-Berichts übertragen oder gescheitert: Datei löschen, einen Fehler protokollieren.
    func session(_ session: WCSession, didFinish fileTransfer: WCSessionFileTransfer, error: Error?) {
        guard fileTransfer.file.metadata?[SyncKeys.command] as? String == SyncKeys.diagReport else { return }
        try? FileManager.default.removeItem(at: fileTransfer.file.fileURL)
        if let error { DiagStore.shared.log(.sync, "diag report not sent: \(error.localizedDescription)") }
    }

    func session(_ session: WCSession, didReceiveMessage message: [String: Any]) {
        guard let command = message[SyncKeys.command] as? String else { return }
        if receiveTransient(command, message) { return }
        if receiveRecord(command, message) { return }
        if command == SyncKeys.stop || command == SyncKeys.snooze {
            // Ohne Sendezeit: veraltete iPhone-App, verwerfen (kein Rückfall). Verspätet zugestellt träfe es sonst eine
            // spätere Nacht.
            // Unplausible Sendezeit (Vollaudit AP2-8): ebenso verwerfen, ohne über `Int64` hinaus zu rechnen.
            guard !SyncTime.stale(message[SyncKeys.sentAt] as? Int64, now: Date()) else { return }
        }
        // Nacht des iPhone-Alarms (fehlt, wenn das iPhone keine kennt): Stopp oder Schlummern nur für dieselbe Nacht.
        let alarmId = (message[SyncKeys.alarms] as? String).flatMap { $0.isEmpty ? nil : $0 }
        let target = SyncTime.date(message[SyncKeys.target])
        let sentAt = SyncTime.date(message[SyncKeys.sentAt])
        Task { @MainActor in
            let alarm = SmartAlarm.shared
            if command == SyncKeys.stop || command == SyncKeys.snooze, !alarm.isThisNight(alarmId: alarmId, target: target) {
                DiagStore.shared.log(.suppressed, "phone \(command) for other night")
                return
            }
            switch command {
            case SyncKeys.stop: alarm.stopFromPhone(alarmId: alarmId, target: target)
            case SyncKeys.snooze: alarm.snoozeFromPhone()
            case SyncKeys.clearHistory:
                WatchModel.shared.clearHistory(sentAt: sentAt)
            default: break
            }
        }
    }

    /// «Nacht aufzeichnen» vom iPhone. Ein Start gilt nur frisch (höchstens 2 Min alt, sonst begänne die Aufzeichnung
    /// Stunden später), nachgereicht bis 10 Min nach dem Tippen (``RecordRetry``); ein Stopp auch verspätet, aber nur
    /// für eine Aufzeichnung, die vor ihm begann. - Returns: ob behandelt.
    private func receiveRecord(_ command: String, _ message: [String: Any]) -> Bool {
        guard command == SyncKeys.recordStart || command == SyncKeys.recordStop else { return false }
        // Ohne Sendezeit (unvollständige Nachricht) nichts tun – wie bei Stopp und Schlummern; sonst gälte sie immer als frisch.
        let sentMs = message[SyncKeys.sentAt] as? Int64
        guard let sentAt = SyncTime.date(sentMs) else {
            Task { @MainActor in DiagStore.shared.log(.suppressed, "record \(command) without sentAt") }
            return true
        }
        let retryTap = (message[SyncKeys.retryTapAt] as? String).flatMap { Int64($0) }
        Task { @MainActor in
            let recorder = LearnRecorder.shared
            if command == SyncKeys.recordStart {
                guard RecordRetry.accepts(sentAtMs: sentMs, retryTapMs: retryTap, now: Date()) else {
                    DiagStore.shared.log(.suppressed, "late record start from phone")
                    return
                }
                await recorder.startManual(by: "phone")
            } else if let since = recorder.since, since <= sentAt {
                recorder.stop(.phone)
            }
            // Stand gleich zurückmelden (auch, wenn nichts startete), damit das iPhone nicht auf eine Antwort wartet.
            recorder.publishSince()
        }
        return true
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
