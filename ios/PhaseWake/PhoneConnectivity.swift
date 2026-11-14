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
    /// `sleepGoal`, `autoOnset`: `nil` lässt den Schlüssel weg, die Uhr behält dann ihren Stand (``afterReset(_:key:in:)``).
    func publish(_ snapshot: AlarmSnapshot, holidays: HolidayList, sleepGoal: Int?, autoOnset: Bool?, learning: LearningSnapshot) {
        guard WCSession.default.activationState == .activated, WCSession.default.isPaired, WCSession.default.isWatchAppInstalled else { return }
        var context: [String: Any] = [SyncKeys.holidays: Holidays.encode(holidays)]
        if let sleepGoal { context[SyncKeys.sleepGoal] = Int64(sleepGoal) }
        AlarmStamps.apply(snapshot, to: &context)
        learning.apply(to: &context)
        if let autoOnset { context[SyncKeys.autoOnset] = autoOnset }
        do {
            try WCSession.default.updateApplicationContext(context)
            DiagStore.shared.log(.sync, "sent \(snapshot.alarms.count) alarms, \(holidays.dates.count) holidays")
        } catch {
            DiagStore.shared.log(.error, "context not sent: \(error.localizedDescription)")
        }
    }

    /// Wert einer Einstellung für die Uhr oder `nil` (weglassen): Nach einer kaputten Einstellungsdatei
    /// (``SettingsFile/wasReset``, Prüfrunde 2 SP-7) nur, was seither hier gesetzt wurde; der Standard des leeren Stands
    /// ersetzte sonst den Stand der Uhr. Wie `sleepGoalStamp`/`autoOnsetStamped` auf Android (Zeitpunkt 0). Feiertage
    /// gehen wie auf Android weiter mit (das iPhone plant mit ihnen; sonst wichen iPhone und Uhr voneinander ab).
    static func afterReset<T>(_ value: T, key: String, in settings: SettingsFile) -> T? {
        settings.wasReset && settings.object(forKey: key) == nil ? nil : value
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
        var payload: [String: Any] = [SyncKeys.command: command, SyncKeys.sentAt: TimeBounds.millis(Date())]
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
    /// Uhr-App erst jetzt installiert (Fix S-4): den Stand gleich senden; `publish` bricht ohne installierte Uhr-App ab,
    /// sonst käme er erst mit der nächsten Änderung oder dem nächsten Start.
    func sessionWatchStateDidChange(_ session: WCSession) {
        BackupAlarm.noteWatchState(session)
        guard session.activationState == .activated, session.isPaired, session.isWatchAppInstalled else { return }
        Task { @MainActor in PhoneModel.shared.publishToWatch() }
    }

    /// Uhr wieder erreichbar (App dort offen): einen unbeantworteten Start von «Nacht aufzeichnen» nachreichen
    /// (``WatchNight/retryRecordStart(now:)``, Vollaudit AP2-10).
    func sessionReachabilityDidChange(_ session: WCSession) {
        guard session.isReachable else { return }
        Task { @MainActor in WatchNight.shared.retryRecordStart() }
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
        // Über der Grenze nicht verarbeiten (Audit S-2, wie Android).
        for key in [SyncKeys.learnRecording, SyncKeys.night, SyncKeys.learnSkip] {
            if let text = userInfo[key] as? String, text.utf8.count > SyncKeys.maxPeerBytes {
                DiagStore.shared.log(.suppressed, "\(key) too large (\(text.utf8.count) bytes)")
                return
            }
        }
        // Lern-Übersicht: Die Uhr zeichnet eine Nacht nicht auf (Grund, ggf. Akku).
        if let text = userInfo[SyncKeys.learnSkip] as? String {
            Task { @MainActor in LearnLogStore.shared.receive(text) }
            return
        }
        // Aufzeichnung der Lernphase (Uhr-App ab `WatchProtocol.learning`).
        if let text = userInfo[SyncKeys.learnRecording] as? String {
            Task { @MainActor in await PhoneLearning.shared.receive(text) }
            return
        }
        guard let text = userInfo[SyncKeys.night] as? String, let night = try? Codec.decodeNight(text) else { return }
        Task { @MainActor in
            // Endete vor «Verlauf löschen» (spät zugestellt): nicht zurückholen (Audit D-02/AP-6, wie Android). Die
            // Sicherung dieser Nacht bleibt trotzdem ausgesetzt – die Uhr hat geweckt.
            if HistoryClear.keeps(end: night.wokeAt, clearedAt: PhoneModel.shared.historyClearedAt) {
                PhoneModel.shared.add(night: PhoneBattery.withPhone(night))
            } else {
                DiagStore.shared.log(.suppressed, "night before history clear dropped")
            }
            // Die Uhr hat geweckt: Sicherung dieser Nacht aussetzen (falls «ring» verloren ging).
            await PhoneModel.shared.backup.watchHandled(alarmId: night.alarmId, target: night.target)
        }
    }

    func session(_ session: WCSession, didReceiveMessage message: [String: Any]) {
        seenWatch()
        guard let command = message[SyncKeys.command] as? String else { return }
        let alarmId = (message[SyncKeys.alarms] as? String).flatMap { $0.isEmpty ? nil : $0 }
        // Zielzeit der Nacht (fehlt, wenn die Uhr keine Nacht kennt) und Sendezeit; unplausible Zeiten gelten als fehlend
        // (Vollaudit AP2-8, ``SyncTime``).
        let target = SyncTime.date(message[SyncKeys.target])
        let sentAt = message[SyncKeys.sentAt] as? Int64
        // «klingeln» und «stop» ohne Sendezeit (bzw. «klingeln» ohne Kennung oder Schlummer-Runden) stammen von einer
        // veralteten Uhr-App: verwerfen, kein Rückfall (der Nacht-Check meldet sie als veraltet). Wie Android.
        let snoozesLeftValue = (message[SyncKeys.snoozesLeft] as? Int64).map { Int(clamping: $0) }
        if command == SyncKeys.ring || command == SyncKeys.stop,
           sentAt == nil || (command == SyncKeys.ring && (alarmId == nil || snoozesLeftValue == nil)) {
            DiagStore.shared.log(.suppressed, "\(command) from outdated watch")
            return
        }
        // Älter als 2 Minuten (nachgereicht, z. B. per `transferUserInfo`): klingelt bzw. stoppt nicht mehr.
        let stale = sentAt.map { SyncTime.stale($0, now: Date()) } ?? false
        // Nur bei «stop» nach dem Schlummern auf der Uhr.
        let snoozeUntil = SyncTime.date(message[SyncKeys.snoozeUntil])
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
                // Endgültig und frisch: Ende der Nacht für den Akku des iPhones.
                if snoozeUntil == nil, !stale { PhoneBattery.stopped() }
            case SyncKeys.nightBegin:
                // Akku je Nacht: Die Uhr beginnt zu messen, das iPhone auch – nur frisch angekommen gilt «jetzt» als Beginn.
                if sentAt != nil, !stale { PhoneBattery.begin() } else { DiagStore.shared.log(.battery, "night battery begin ignored (late)") }
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

    /// Holt den Diagnose-Bericht der Uhr: klein in der Antwort, voll als Datei (`DiagTransfer`, Antwort
    /// `SyncKeys.diagFile`). `nil`, wenn sie nicht erreichbar ist oder nicht antwortet; dann steht «watch report missing»
    /// im Protokoll.
    @MainActor
    func requestWatchDiag() async -> DiagReport? {
        let report = await fetchWatchDiag()
        if report == nil { DiagStore.shared.log(.sync, "watch report missing") }
        return report
    }

    /// ``requestWatchDiag()`` ohne Protokoll.
    @MainActor
    private func fetchWatchDiag() async -> DiagReport? {
        guard WCSession.isSupported(), WCSession.default.activationState == .activated, WCSession.default.isReachable else { return nil }
        // Eine Datei einer früheren Anfrage zählt nicht.
        diagFileReport = nil
        let reply: DiagReply = await withCheckedContinuation { continuation in
            WCSession.default.sendMessage([SyncKeys.command: SyncKeys.diagRequest], replyHandler: { reply in
                continuation.resume(returning: reply[SyncKeys.diagFile] as? Bool == true ? .file : .text(reply[SyncKeys.payload] as? String))
            }, errorHandler: { _ in
                continuation.resume(returning: .text(nil))
            })
        }
        switch reply {
        case .text(let text): return DiagReports.decode(text)
        case .file: return await awaitDiagFile()
        }
    }

    /// Antwort der Uhr auf `SyncKeys.diagRequest`: Bericht als Text oder Hinweis, dass er als Datei folgt.
    private enum DiagReply: Sendable {
        case text(String?)
        case file
    }

    /// Bericht aus einer Datei der Uhr, der vor dem Warten ankam (die Datei kann die Antwort überholen). Nur Main-Thread.
    private var diagFileReport: DiagReport?
    /// Wartet auf die Datei des Berichts (``awaitDiagFile()``), mit Kennung gegen eine abgelaufene Frist. Nur Main-Thread.
    private var diagWaiter: (id: UUID, continuation: CheckedContinuation<DiagReport?, Never>)?

    /// Wartet höchstens `DiagTransfer.fileTimeout` auf die Datei des Berichts.
    @MainActor
    private func awaitDiagFile() async -> DiagReport? {
        if let report = diagFileReport {
            diagFileReport = nil
            return report
        }
        let id = UUID()
        return await withCheckedContinuation { continuation in
            // Eine ältere Anfrage, die noch wartet (zweimal getippt), endet ohne Bericht.
            if let old = diagWaiter {
                diagWaiter = nil
                old.continuation.resume(returning: nil)
            }
            diagWaiter = (id: id, continuation: continuation)
            Task { @MainActor in
                try? await Task.sleep(for: .seconds(DiagTransfer.fileTimeout))
                self.finishDiagWait(id: id, report: nil)
            }
        }
    }

    /// Beendet das Warten `id` (bzw. jedes, wenn `id` fehlt) mit `report`; ohne Wartenden wird der Bericht gemerkt.
    @MainActor
    private func finishDiagWait(id: UUID?, report: DiagReport?) {
        guard let waiter = diagWaiter, id == nil || waiter.id == id else {
            if id == nil { diagFileReport = report }
            return
        }
        diagWaiter = nil
        waiter.continuation.resume(returning: report)
    }

    /// Datei der Uhr: nur der Diagnose-Bericht (``requestWatchDiag()``). Gleich hier lesen: WatchConnectivity löscht die
    /// Datei nach der Rückkehr. Über `SyncKeys.maxPeerBytes` oder unlesbar: verworfen (das Warten endet mit der Frist).
    func session(_ session: WCSession, didReceive file: WCSessionFile) {
        guard file.metadata?[SyncKeys.command] as? String == SyncKeys.diagReport else { return }
        seenWatch()
        guard let report = DiagTransfer.read(fileAt: file.fileURL) else {
            DiagStore.shared.log(.suppressed, "diag report unreadable or too large")
            return
        }
        Task { @MainActor in self.finishDiagWait(id: nil, report: report) }
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
            let date = armed > 0 ? SyncTime.date(armed) : nil
            Task { @MainActor in PhoneModel.shared.watchArmed(date) }
        }
        // «Nacht aufzeichnen» auf der Uhr (fehlt bei älteren Uhr-Versionen).
        if let since = context[SyncKeys.recordSince] as? Int64 {
            let date = since > 0 ? SyncTime.date(since) : nil
            Task { @MainActor in WatchNight.shared.noteRecording(since: date) }
        }
        // Rekorder für eine Nacht nicht gestartet (Audit F-06); fehlt bei älteren Uhren (dann keine Warnung).
        if let text = context[SyncKeys.recorderSkip] as? String {
            let skip = RecorderSkip.decode(text)
            Task { @MainActor in NightCheck.watchRecorderSkip = skip }
        }
        // Ohne Akkustand (Uhr kennt ihn nicht, ältere Uhr beim Laden) gilt er als unbekannt.
        var battery: (percent: Int, at: Date)?
        if let percent = context[SyncKeys.battery] as? Int64, let at = SyncTime.date(context[SyncKeys.batteryAt]) {
            battery = (percent: Int(clamping: percent), at: at)
        }
        let level = battery
        // Uhr lädt (F-6; ältere Uhren melden beim Laden keinen Akkustand).
        let charging = context[SyncKeys.batteryCharging] as? Bool ?? false
        // Stand der Verbindung; fehlt er in einem Kontext der Uhr (sehr alte Uhr-App), zählt er als 0: Der Nacht-Check
        // meldet «Uhr-App veraltet» (wie Android, Status ohne Angabe).
        let version = Int((context[SyncKeys.watchProtocol] as? Int64) ?? 0)
        // Erlaubnis der Mitteilungen auf der Uhr; fehlt sie (ältere Uhr, noch nicht gefragt), warnt der Nacht-Check nicht.
        // Mit dem Zeitpunkt der Prüfung auf der Uhr; ohne ihn (ältere Uhr) kein Hinweis «veraltet».
        let checkedAt = SyncTime.date(context[SyncKeys.watchNotificationsAt])
        let notifications: (state: WatchNotifications, checkedAt: Date?)? = (context[SyncKeys.watchNotifications] as? Int64)
            .flatMap(WatchNotifications.init(rawValue:)).map { (state: $0, checkedAt: checkedAt) }
        if !context.isEmpty {
            Task { @MainActor in PhoneModel.shared.setWatchStatus(battery: level, version: version, notifications: notifications, charging: charging) }
        }
        guard let snapshot = AlarmStamps.snapshot(context: context) else { return }
        Task { @MainActor in PhoneModel.shared.applyRemote(snapshot) }
    }
}
