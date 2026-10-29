import PhaseWakeCore
import WatchKit

extension SmartAlarm {
    // MARK: WKExtendedRuntimeSessionDelegate

    nonisolated func extendedRuntimeSessionDidStart(_ extendedRuntimeSession: WKExtendedRuntimeSession) {
        Task { @MainActor in
            guard extendedRuntimeSession === self.session || self.session == nil else { return }
            self.session = extendedRuntimeSession
            DiagStore.shared.log(.window, "session started")
            switch self.phase {
            case .snoozed:
                // Nach dem Schlummern startet die neue Sitzung direkt mit dem Wecken.
                self.ring(self.reason)
            case .measuring, .ringing:
                break // läuft schon
            case .idle, .waiting, .scheduled:
                self.startNight()
            }
        }
    }

    nonisolated func extendedRuntimeSessionWillExpire(_ extendedRuntimeSession: WKExtendedRuntimeSession) {
        // Kurz vor Ablauf (spätestens nach 30 Min): jetzt wecken, sonst verwirft das System den Alarm.
        Task { @MainActor in if self.phase.isMeasuring { self.ring(self.targetReason()) } }
    }

    nonisolated func extendedRuntimeSession(
        _ extendedRuntimeSession: WKExtendedRuntimeSession,
        didInvalidateWith reason: WKExtendedRuntimeSessionInvalidationReason,
        error: Error?
    ) {
        Task { @MainActor in
            guard extendedRuntimeSession === self.session else { return }
            self.session = nil
            self.stopSensors()
            DiagStore.shared.log(.error, "session invalidated \(reason.rawValue)")
            switch self.phase {
            case .scheduled(let plan):
                // Vom System abgelehnt oder zurückgezogen: Der Rückfall weckt; beim nächsten Öffnen neu planen.
                self.phase = .waiting(plan)
            case .measuring:
                // Abgebrochen mitten in der Messung: sofort per Mitteilung wecken.
                self.scheduleFallback(at: Date(), id: Self.interruptedId)
                self.phase = .idle
            case .ringing, .snoozed:
                // Abgelaufen oder vom System beendet: weiterwecken und die Nacht abschliessen.
                self.finishInterrupted()
                return
            case .idle, .waiting:
                break
            }
            self.publishArmed()
        }
    }
}
