import Foundation
import PhaseWakeCore
import WatchConnectivity

/// Nachrichten und Benutzerdaten vom iPhone: Diagnose-Anfrage, Stopp und Schlummern, «Nacht aufzeichnen»,
/// Probewecker, Powernap und Abbruch.
extension WatchConnectivityBridge {
    /// Anfrage mit Antwort: nur der Diagnose-Bericht. Alles andere wie eine Nachricht ohne Antwort.
    func session(_ session: WCSession, didReceiveMessage message: [String: Any],
                 replyHandler: @escaping ([String: Any]) -> Void) {
        guard message[SyncKeys.command] as? String == SyncKeys.diagRequest else {
            self.session(session, didReceiveMessage: message)
            replyHandler([:])
            return
        }
        // Aus Quellen, die von jedem Thread lesbar sind; die Antwort geht sofort zurück.
        let text = DiagReports.encode(DiagReport(status: WatchDiagStatus.shared.current,
                                                 entries: DiagStore.shared.entries()))
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
            // Unplausible Sendezeit: ebenso verwerfen, ohne über `Int64` hinaus zu rechnen.
            guard !SyncTime.stale(message[SyncKeys.sentAt] as? Int64, now: Date()) else { return }
        }
        // Nacht des iPhone-Alarms (fehlt, wenn das iPhone keine kennt): Stopp oder Schlummern nur für dieselbe Nacht.
        let alarmId = (message[SyncKeys.alarms] as? String).flatMap { $0.isEmpty ? nil : $0 }
        let target = SyncTime.date(message[SyncKeys.target])
        let sentAt = SyncTime.date(message[SyncKeys.sentAt])
        Task { @MainActor in
            let alarm = SmartAlarm.shared
            if command == SyncKeys.stop || command == SyncKeys.snooze,
               !alarm.isThisNight(alarmId: alarmId, target: target) {
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
        // Ohne Sendezeit (unvollständige Nachricht) nichts tun – wie bei Stopp und Schlummern; sonst gälte sie immer
        // als frisch.
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
            guard let text = message[SyncKeys.payload] as? String,
                  let plan = try? Transient.decode(text) else { return true }
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
}
