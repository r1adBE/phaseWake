import PhaseWakeCore
import WatchKit

extension SmartAlarm {
    // MARK: Nicht mehr gültige Sitzungen

    /// Sitzung `old` beenden oder für später merken (`SessionRules.retire`). Gemerkte Sitzungen behalten den Delegate:
    /// So meldet sich ein Start oder ihr Ende, und sie werden dann erkannt (`isStale`, `dropStale`).
    func retire(_ old: WKExtendedRuntimeSession) {
        let active = WKApplication.shared().applicationState == .active
        switch SessionRules.retire(old.state, active: active) {
        case .drop:
            old.delegate = nil
        case .now:
            old.delegate = nil
            old.invalidate()
        case .tryNowAndKeep:
            keepStale(old)
            old.invalidate()
        case .whenActive:
            keepStale(old)
        }
    }

    private func keepStale(_ old: WKExtendedRuntimeSession) {
        guard !isStale(old) else { return }
        staleSessions.append(old)
        DiagStore.shared.log(.scheduled, "session end pending (\(old.state.rawValue))")
    }

    /// Ob `candidate` eine gemerkte, nicht mehr gültige Sitzung ist.
    func isStale(_ candidate: WKExtendedRuntimeSession) -> Bool {
        staleSessions.contains { $0 === candidate }
    }

    /// Ende einer gemerkten Sitzung: vergessen. - Returns: ob `ended` gemerkt war.
    func dropStale(_ ended: WKExtendedRuntimeSession) -> Bool {
        guard isStale(ended) else { return false }
        staleSessions.removeAll { $0 === ended }
        ended.delegate = nil
        return true
    }

    /// App aktiv: gemerkte Sitzungen jetzt beenden (Aufruf bei jedem Aktivwerden, `AppDelegate`).
    func invalidateStaleSessions() {
        guard WKApplication.shared().applicationState == .active, !staleSessions.isEmpty else { return }
        let stale = staleSessions
        staleSessions = []
        for old in stale where old.state != .invalid {
            old.delegate = nil
            old.invalidate()
        }
        DiagStore.shared.log(.scheduled, "\(stale.count) pending session end(s) done")
    }

    // MARK: WKExtendedRuntimeSessionDelegate

    nonisolated func extendedRuntimeSessionDidStart(_ extendedRuntimeSession: WKExtendedRuntimeSession) {
        Task { @MainActor in
            // Verwaiste Sitzung (ersetzt, als die App sie nicht beenden durfte): gleich beenden, ohne zu vibrieren.
            if self.isStale(extendedRuntimeSession) {
                DiagStore.shared.log(.error, "stale session started, ended")
                if self.session === extendedRuntimeSession { self.session = nil }
                extendedRuntimeSession.invalidate()
                return
            }
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
        SettingsFile.watchSession.removeObject(forKey: Self.sessionZoneKey)
        WatchModel.shared.reschedule()
        if case .scheduled = phase, session === started { return false }
        if session === started { invalidateSession() }
        return true
    }

    nonisolated func extendedRuntimeSessionWillExpire(_ extendedRuntimeSession: WKExtendedRuntimeSession) {
        // Kurz vor Ablauf (spätestens nach 30 Min): jetzt wecken, sonst verwirft das System den Alarm.
        Task { @MainActor in
            guard extendedRuntimeSession === self.session, self.phase.isMeasuring else { return }
            self.ring(self.targetReason())
        }
    }

    nonisolated func extendedRuntimeSession(
        _ extendedRuntimeSession: WKExtendedRuntimeSession,
        didInvalidateWith reason: WKExtendedRuntimeSessionInvalidationReason,
        error: Error?
    ) {
        let code = (error as NSError?).map { " \($0.domain) \($0.code)" } ?? ""
        Task { @MainActor in
            if self.dropStale(extendedRuntimeSession) {
                DiagStore.shared.log(.window, "stale session ended \(SessionRules.reasonName(reason))\(code)")
                return
            }
            guard extendedRuntimeSession === self.session else { return }
            self.session = nil
            self.stopSensors()
            // Grund und Fehlercode ins Protokoll (Audit AP-1).
            let ending = SessionRules.ending(reason, ringing: self.phase.isRinging)
            DiagStore.shared.log(ending == .dismissed ? .stop : .error, "session invalidated \(SessionRules.reasonName(reason))\(code)")
            switch self.phase {
            case .ringing where ending == .dismissed:
                // Weckhinweis des Systems gestoppt: wie Stopp auf der Uhr – iPhone hört auf, die Kette fällt weg.
                self.dismiss()
                return
            case .scheduled(let plan):
                // Vom System abgelehnt oder zurückgezogen: Der Rückfall weckt; beim nächsten Öffnen neu planen.
                self.phase = .waiting(plan)
            case .measuring(let plan):
                self.measuringInterrupted(plan)
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
