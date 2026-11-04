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
                if self.replansAfterZoneChange(extendedRuntimeSession) { return }
                self.startNight()
            }
        }
    }

    /// Reise: Hat sich die Ortszeit seit dem Planen verschoben (`WatchRules.zoneShifted`), startete die Sitzung zur
    /// alten Zeit, der Rückfall weckt aber zur neuen Ortszeit – es weckte zweimal. Hat das Weckfenster der Nacht schon
    /// begonnen und gilt ihr Wecker in der Zone der Planung unverändert (`keptNight`), misst sie zur geplanten Zeit,
    /// mit festem Rückfall. Sonst neu planen statt messen: Passt die Sitzung danach nicht mehr (anderer Plan, App nicht
    /// aktiv), endet sie, und es weckt der Rückfall zur Ortszeit.
    /// - Returns: ob die Sitzung hier behandelt ist (gehaltene Nacht misst, oder neu geplant und beendet).
    func replansAfterZoneChange(_ started: WKExtendedRuntimeSession) -> Bool {
        let saved = Self.savedPlanZone
        guard WatchRules.zoneShifted(plannedZone: saved?.zone, target: saved?.target, zone: .current) else { return false }
        // Nach einem Neustart der App ist der Plan unbekannt (`idle`): dann die gemerkte Nacht.
        var night: WakePlan?
        if case .scheduled(let current) = phase { night = current }
        if case .waiting(let current) = phase { night = current }
        WatchModel.shared.refresh()
        if let kept = keptNight(night) {
            holdFallback(night ?? kept)
            beginMeasuring(night ?? kept)
            return true
        }
        DiagStore.shared.log(.scheduled, "zone changed since session was planned, replan")
        UserDefaults.standard.removeObject(forKey: Self.sessionZoneKey)
        WatchModel.shared.reschedule()
        if case .scheduled = phase, session === started { return false }
        if session === started { invalidateSession() }
        return true
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
